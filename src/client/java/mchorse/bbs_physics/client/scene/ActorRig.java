package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.JoltShapes;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * One actor's collision as kinematic bodies: physics never moves them, they move physics. This is
 * what makes an animated character part of the simulated world — a falling crate lands on a
 * shoulder, and later a ragdoll's limbs have something to be driven towards.
 *
 * <p>Kinematic rather than static because a static body has no velocity: things resting on it
 * would be left behind when it moves, instead of being carried and shoved. {@code moveKinematic}
 * gives Jolt a target for the next tick and lets it work out the velocity that gets there, which
 * is what makes the contact push.</p>
 *
 * <p><b>Only what is marked up.</b> Every piece here comes from the form tree's collision markup
 * (§5.2) — nothing is measured behind the author's back. A model whose bones nobody marked has no
 * bodies at all, which is the intended default: marking every cube costs contacts on geometry that
 * was never meant to collide, and it would take ownership of the bones hair and cloth are about to
 * be driven by. One body per marked slot, so a hand can shove a crate without the knee joining in.
 * </p>
 *
 * <p><b>Known limit, deliberate for now:</b> the pose is only known at the tick the film is on. A
 * seek that re-simulates twenty ticks stands the bodies still at the pose of the tick it is heading
 * for, because computing the pose of every tick in between would mean replaying the whole property
 * track per step. So a tick arrived at by scrubbing is not bit-for-bit the tick arrived at by
 * playing, for any actor that moves — the props are exact either way, the character's shoving of
 * them is not. Making it exact means sampling the pose per tick, which belongs with the ragdoll
 * work in Э2. What it must never do is <em>steer</em> across a jump: a kinematic body keeps the
 * velocity it was given, so a target one tick away, integrated over twenty, throws the body
 * twentyfold across the scene, raking everything on the way. Hence {@code place}.</p>
 */
public class ActorRig
{
    /** Shared, never written to — the velocity a placed body is stopped with. */
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    private final List<Part> parts = new ArrayList<>();

    private final Matrix4f worldMatrix = new Matrix4f();
    private final Vector3f translation = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final RVec3 target = new RVec3();
    private final Quat targetRotation = new Quat();

    private ActorRig()
    {}

    /**
     * Builds a rig from the marked-up pieces handed to it, or returns null when there are none —
     * which is what an unmarked actor looks like, and is not an error.
     *
     * <p>The pieces arrive collected rather than being collected here because the scene divides
     * one actor's markup between owners: a ragdoll-enabled model's bones go to its
     * {@link ActorRagdoll}, and only the rest — other forms' slots, the models' own shapes —
     * become plain kinematic bones.</p>
     *
     * @param matrices the actor's pose, already evaluated for the tick the scene is being built at
     * @param group    the actor's collision group — these bodies are in it so that its ragdolls'
     *                 parts can be excused from them once released, which they must be: the two
     *                 sets of shapes were cut from one skeleton and overlap wherever bones join,
     *                 and a kinematic body cannot give way (see {@link ActorCollisionGroup})
     */
    public static ActorRig build(PhysicsWorld physics, Form root, MatrixCache matrices, FilmScene scene, List<CollisionCollector.Piece> pieces, ActorCollisionGroup group)
    {
        if (root == null)
        {
            return null;
        }

        ActorRig rig = new ActorRig();
        BodyInterface bodies = physics.getBodies();

        for (CollisionCollector.Piece piece : pieces)
        {
            MatrixCacheEntry entry = matrices.get(piece.path());

            /* No frame means nothing will ever tell this body where to stand, and a kinematic body
             * left at the scene's origin is a collider in the middle of the set. */
            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            ConstShape shape = JoltShapes.build(piece.shapes());

            if (shape == null)
            {
                continue;
            }

            BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(0D, 0D, 0D), Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.BONE);

            settings.setFriction(0.6F);
            settings.setCollisionGroup(group.of(group.claimBone()));

            int id = bodies.createAndAddBody(settings, EActivation.Activate);

            rig.parts.add(new Part(piece.path(), id));

            SceneBody debug = new SceneBody(id, 0.3F, 0.7F, 1F);

            debug.addShapes(piece.shapes());
            scene.addDebugBody(debug);
        }

        return rig.parts.isEmpty() ? null : rig;
    }

    public boolean isEmpty()
    {
        return this.parts.isEmpty();
    }

    /**
     * Points every body at where the animation has its slot this tick. The pose arrives as the
     * shared {@code MatrixCache} the scene evaluated once for this actor; its matrices are
     * form-local, so the actor's world placement is applied on top — the same transform BBS's own
     * bone physics resolves gravity against, so both agree on where the character stands.
     *
     * @param place whether to set the bodies down where they belong and stop them, instead of
     *              steering them there over the coming tick. True whenever the world is about to
     *              run more (or fewer) than the one step a steer is aimed at: the rig's first
     *              placement, and every scrub — see the class note on what steering across a jump
     *              does
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean place)
    {
        if (this.parts.isEmpty() || matrices == null)
        {
            return;
        }

        BodyInterface bodies = physics.getBodies();

        for (Part part : this.parts)
        {
            MatrixCacheEntry entry = matrices.get(part.path);

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

            if (place)
            {
                bodies.setPositionAndRotation(part.id, this.target, this.targetRotation, EActivation.Activate);

                /* And stop it there. A kinematic body's velocity is state like any other: left
                 * over from the last steer — or restored along with the rest of the world from a
                 * checkpoint — it would carry the body away from where it was just put, once per
                 * step, for the whole of the re-simulation. */
                bodies.setLinearAndAngularVelocity(part.id, ZERO, ZERO);
            }
            else
            {
                /* A target for one tick, so Jolt derives the velocity that reaches it — that
                 * velocity is what shoves whatever the body runs into. */
                bodies.moveKinematic(part.id, this.target, this.targetRotation, PhysicsWorld.TICK);
            }
        }
    }

    /** One marked-up slot as a body: the matrix-cache path it follows, and the body following it. */
    private record Part(String path, int id)
    {}
}
