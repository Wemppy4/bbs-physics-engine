package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CapsuleShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.RotatedTranslatedShape;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * One actor's bones as kinematic bodies: physics never moves them, they move physics. This is what
 * makes an animated character part of the simulated world — a falling crate lands on a shoulder,
 * and later a ragdoll's limbs have something to be driven towards.
 *
 * <p>Kinematic rather than static because a static body has no velocity: things resting on it
 * would be left behind when it moves, instead of being carried and shoved. {@code moveKinematic}
 * gives Jolt a target for the next tick and lets it work out the velocity that gets there, which
 * is what makes the contact push.</p>
 *
 * <p>Each bone's shape comes from {@link BoneCollider} — the bone's own cubes for cubic models, a
 * capsule along the bone for BOBJ ones — so the character collides as the silhouette it is drawn
 * as, not as a cloud of beads.</p>
 *
 * <p><b>Known limit, deliberate for now:</b> the pose is only known at the tick the film is on. A
 * seek that re-simulates twenty ticks runs them all against the pose of the tick it is heading
 * for, because computing the pose of every tick in between would mean replaying the whole property
 * track per step. It shows as a rewind through fast movement settling slightly differently; making
 * it exact means sampling the pose per tick, which belongs with the ragdoll work in Э2.</p>
 */
public class ActorRig
{
    /** The bead a bone falls back to when it has no measurable geometry, in blocks. */
    private static final float BONE_RADIUS = 0.12F;

    /** Jolt's corner rounding, clamped small so thin bones (a finger, a strap) still fit it. */
    private static final float CONVEX_RADIUS = 0.02F;

    private final IEntity entity;
    private final Form form;
    private final List<Bone> bones = new ArrayList<>();

    private final Matrix4f worldMatrix = new Matrix4f();
    private final Vector3f translation = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final RVec3 target = new RVec3();
    private final Quat targetRotation = new Quat();

    private ActorRig(IEntity entity, Form form)
    {
        this.entity = entity;
        this.form = form;
    }

    /**
     * Builds a rig for {@code entity}, or returns null when there is nothing to build one from —
     * no form, or a form whose model has not loaded yet and so reports no bones.
     */
    public static ActorRig build(PhysicsWorld physics, IEntity entity, FilmScene scene)
    {
        Form form = entity == null ? null : entity.getForm();

        if (form == null)
        {
            return null;
        }

        List<String> names = FormUtilsClient.getRenderer(form).getBones();

        if (names == null || names.isEmpty())
        {
            return null;
        }

        ActorRig rig = new ActorRig(entity, form);
        BodyInterface bodies = physics.getBodies();

        ModelInstance instance = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;
        IModel model = instance == null ? null : instance.model;
        Vector3f scale = instance == null ? new Vector3f(1F) : instance.getScale();

        for (String name : names)
        {
            BoneCollider collider = BoneCollider.of(model, name, scale);

            if (collider == null)
            {
                /* A bone with nothing drawn from it — a control or pivot bone. It exists to steer
                 * other bones, not to be hit, so it gets no body at all. */
                continue;
            }

            ConstShape shape = buildShape(collider);

            if (shape == null)
            {
                continue;
            }

            BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(0D, 0D, 0D), Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.MOVING);

            settings.setFriction(0.6F);

            int id = bodies.createAndAddBody(settings, EActivation.Activate);

            rig.bones.add(new Bone(name, id));

            SceneBody debug = new SceneBody(id, 0.3F, 0.7F, 1F);

            for (BoneCollider.SubShape sub : collider.shapes())
            {
                debug.addShape(debugHalf(sub), sub.offset(), sub.rotation());
            }

            scene.addDebugBody(debug);
        }

        return rig.bones.isEmpty() ? null : rig;
    }

    /** A Jolt shape for a bone's collider: its one shape directly, or a compound of all of them. */
    private static ConstShape buildShape(BoneCollider collider)
    {
        List<BoneCollider.SubShape> subs = collider.shapes();

        if (subs.size() == 1)
        {
            BoneCollider.SubShape sub = subs.get(0);

            return new RotatedTranslatedShape(vec(sub.offset()), quat(sub.rotation()), leafShape(sub));
        }

        StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();

        for (BoneCollider.SubShape sub : subs)
        {
            compound.addShape(vec(sub.offset()), quat(sub.rotation()), leafShape(sub));
        }

        ShapeResult result = compound.create();

        if (result.hasError())
        {
            BBSPhysics.LOGGER.warn("Could not build a bone's compound collider: {}", result.getError());

            return null;
        }

        return result.get();
    }

    private static ConstShape leafShape(BoneCollider.SubShape sub)
    {
        Vector3f half = sub.half();

        return switch (sub.kind())
        {
            case BOX -> new BoxShape(vec(half), Math.min(CONVEX_RADIUS, Math.min(half.x, Math.min(half.y, half.z))));
            case CAPSULE -> new CapsuleShape(half.y, half.x);
            case SPHERE -> new SphereShape(half.x);
        };
    }

    /** The box the debug overlay draws for a shape — capsules and spheres get their bounding box. */
    private static Vector3f debugHalf(BoneCollider.SubShape sub)
    {
        Vector3f half = sub.half();

        return switch (sub.kind())
        {
            case BOX -> half;
            case CAPSULE -> new Vector3f(half.x, half.y + half.x, half.x);
            case SPHERE -> new Vector3f(half.x, half.x, half.x);
        };
    }

    private static Vec3 vec(Vector3f v)
    {
        return new Vec3(v.x, v.y, v.z);
    }

    private static Quat quat(Quaternionf q)
    {
        return new Quat(q.x, q.y, q.z, q.w);
    }

    public boolean isEmpty()
    {
        return this.bones.isEmpty();
    }

    /**
     * Points every bone body at where the animation has that bone this tick. The pose arrives as
     * the shared {@code MatrixCache} the scene evaluated once for this actor; its matrices are
     * form-local, so the actor's world placement is applied on top — the same transform BBS's own
     * bone physics resolves gravity against, so both agree on where the character stands.
     *
     * @param teleport whether to place the bones outright instead of steering them there. True
     *                 when the rig is first built — the bodies start at the origin, and letting
     *                 them travel from there would sweep a character-sized rake through the scene
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean teleport)
    {
        if (this.bones.isEmpty() || matrices == null)
        {
            return;
        }

        BodyInterface bodies = physics.getBodies();

        for (Bone bone : this.bones)
        {
            MatrixCacheEntry entry = matrices.get(bone.name);

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            this.worldMatrix.set(actorWorld).mul(entry.matrix());
            this.worldMatrix.getTranslation(this.translation);

            /* Unnormalized: the matrix carries the model's scale, and JOML's normalized variant
             * assumes it does not — on a scaled model it returns a wrong rotation that also jumps
             * as the model turns. BBS hit exactly this in its own bone physics. */
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.target.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.targetRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (teleport)
            {
                bodies.setPositionAndRotation(bone.id, this.target, this.targetRotation, EActivation.Activate);
            }
            else
            {
                /* A target for one tick, so Jolt derives the velocity that reaches it — that
                 * velocity is what shoves whatever the bone runs into. */
                bodies.moveKinematic(bone.id, this.target, this.targetRotation, PhysicsWorld.TICK);
            }
        }
    }

    private record Bone(String name, int id)
    {}
}
