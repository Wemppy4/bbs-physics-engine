package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BodyLockWrite;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotionProperties;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAllowedDofs;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import mchorse.bbs_physics.forms.PhysicsBodyState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ties one {@link PhysicsBodyForm} to its body in the simulation, in both directions: while the
 * animation owns the form, the keyframes drive the body; once it is let go, the body drives what
 * is drawn.
 *
 * <p>The handover is what makes a thrown object work, and it costs nothing to arrange: a kinematic
 * body in Jolt carries the velocity {@code moveKinematic} gave it, so switching it to dynamic
 * leaves it flying at exactly the speed the keyframes were moving it. Animate the authority from 1
 * to 0 on the frame the hand opens and the object simply continues.</p>
 *
 * <p>A body can sit anywhere in an actor's form tree, not just at its root — a crate in a hand, a
 * helmet on a head. It is addressed by its <em>path</em> in the tree (the same path convention the
 * matrix walk uses), driven from the walk's evaluation of that path, and its simulated result is
 * carried back through the <em>parent frame</em> the renderer captured during the walk, because
 * the renderer substitutes a local transform and only the walk knows the chain above it.</p>
 *
 * <p>Known approximations. A released nested body is re-anchored to its parent frame every tick, so
 * on a fast-moving parent the draw interpolation composes two lerps and can wobble a touch. A body
 * nested inside <em>another physics body</em> reads its parent's frame a tick late — the outer body
 * works, the inner one follows approximately. And the throw only carries its speed when the film
 * <em>played</em> into the release: a scrub lands the body on the release tick with no velocity to
 * inherit, because the tick before it was never simulated, so the object drops instead of flying.
 * Play the shot and it throws; that is the same per-tick pose sampling Э2 owes {@link ActorRig}.</p>
 */
public class PhysicsBodyRig
{
    /** Reference density before the mass override rescales it; any positive value works. */
    private static final float DENSITY = 1000F;

    /** Shared, never written to — the velocity a placed body is stopped with. */
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    private final IEntity entity;
    private final PhysicsBodyForm form;
    private final String path;
    private final int bodyId;

    private final Matrix4f actorWorld = new Matrix4f();
    private final Matrix4f parentFrame = new Matrix4f();
    private final Matrix4f bodyWorld = new Matrix4f();
    private final Matrix4f local = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();

    private final SceneBody debug;

    private boolean kinematic;

    /**
     * Whether the next read is the first since the simulation took the body over. The drawn
     * transform is interpolated between the last two ticks, and until this read there is no last
     * tick — the form was being drawn from its keyframes. Interpolating from that empty slot draws
     * the object streaking in from the actor's own origin on the very frame it is let go.
     */
    private boolean justReleased;

    /* What the body in Jolt was last told. Compared against the form every tick, because the
     * author edits these while the film is open — and a setting that only took effect when the
     * scene happened to be rebuilt would read as a setting that does nothing. */
    private float lastSizeX;
    private float lastSizeY;
    private float lastSizeZ;
    private float lastMass;
    private float lastFriction;
    private float lastRestitution;

    private PhysicsBodyRig(IEntity entity, PhysicsBodyForm form, String path, int bodyId, boolean kinematic, SceneBody debug)
    {
        this.entity = entity;
        this.form = form;
        this.path = path;
        this.bodyId = bodyId;
        this.kinematic = kinematic;
        this.justReleased = !kinematic;
        this.debug = debug;

        this.lastSizeX = form.sizeX.get();
        this.lastSizeY = form.sizeY.get();
        this.lastSizeZ = form.sizeZ.get();
        this.lastMass = form.mass.get();
        this.lastFriction = form.friction.get();
        this.lastRestitution = form.restitution.get();
    }

    /** Builds the body for a physics body form found at {@code path} in the actor's form tree. */
    public static PhysicsBodyRig build(PhysicsWorld physics, IEntity entity, PhysicsBodyForm form, String path, FilmScene scene)
    {
        float halfX = form.sizeX.get() * 0.5F;
        float halfY = form.sizeY.get() * 0.5F;
        float halfZ = form.sizeZ.get() * 0.5F;

        boolean kinematic = form.isKinematic();

        BodyCreationSettings settings = new BodyCreationSettings(
            new BoxShape(new Vec3(halfX, halfY, halfZ)),
            new RVec3(0D, 0D, 0D), Quat.sIdentity(),
            kinematic ? EMotionType.Kinematic : EMotionType.Dynamic,
            PhysicsLayers.MOVING);

        settings.setFriction(form.friction.get());
        settings.setRestitution(form.restitution.get());

        /* Jolt would otherwise weigh the box by its volume, which makes a big prop absurdly heavy
         * and a small one weightless. The author gives a mass; the inertia is derived from the
         * same box so it tumbles like the shape it is. */
        settings.setMassPropertiesOverride(massOf(halfX, halfY, halfZ, form.mass.get()));
        settings.setOverrideMassProperties(EOverrideMassProperties.MassAndInertiaProvided);

        int id = physics.getBodies().createAndAddBody(settings, EActivation.Activate);

        SceneBody debug = new SceneBody(id, halfX, halfY, halfZ, 1F, 0.55F, 0.2F);

        scene.addDebugBody(debug);

        form.state = new PhysicsBodyState();

        return new PhysicsBodyRig(entity, form, path, id, kinematic, debug);
    }

    /** A box's mass properties for a given total mass: the shape sets the inertia, the mass scales it. */
    private static MassProperties massOf(float halfX, float halfY, float halfZ, float mass)
    {
        MassProperties properties = new MassProperties();

        properties.setMassAndInertiaOfSolidBox(new Vec3(halfX * 2F, halfY * 2F, halfZ * 2F), DENSITY);
        properties.scaleToMass(mass);

        return properties;
    }

    /**
     * Runs before the world steps: keeps the body's motion type in step with the authority track
     * and, while the animation is in charge, steers the body along the keyframes.
     *
     * <p>The keyframed placement is read from the shared matrix walk at this body's own path —
     * which folds in the whole chain above it (parent transforms, the bone it hangs on) — rather
     * than from the form's transform alone. The walk also just captured the parent frame through
     * the renderer, which is copied out here for {@link #read} to carry the simulation's answer
     * back through.</p>
     *
     * @param place whether to set the body down and stop it rather than steer it over the coming
     *              tick — see {@link ActorRig#update} for why steering across a jump is ruinous
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean place)
    {
        this.actorWorld.set(actorWorld);

        if (this.form.state != null)
        {
            this.parentFrame.set(this.form.state.getWalkParentFrame());
        }

        BodyInterface bodies = physics.getBodies();

        this.applySettings(physics, bodies);

        boolean wanted = this.form.isKinematic();

        if (wanted != this.kinematic)
        {
            /* The moment of release (or of being grabbed back). Jolt keeps the velocity across the
             * change, which is exactly the throw. */
            bodies.setMotionType(this.bodyId, wanted ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);

            this.kinematic = wanted;
            this.justReleased = !wanted;

            /* Taken back by the animation, after however long living its own life: it is nowhere
             * near its keyframe any more. Steering it back over a single tick would send it there
             * at the whole distance divided by a twentieth of a second, scattering everything in
             * between. Put down, not driven. */
            place |= wanted;
        }

        if (!this.kinematic)
        {
            return;
        }

        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.path);

        if (entry == null || entry.matrix() == null)
        {
            return;
        }

        this.bodyWorld.set(actorWorld).mul(entry.matrix());
        this.bodyWorld.getTranslation(this.position);

        /* Unnormalized, deliberately: the chain may carry scale, and JOML's normalized variant
         * assumes an orthonormal basis — on a scaled matrix it returns a wrong rotation that also
         * jumps as the model turns. BBS hit exactly this in its own bone physics. */
        this.bodyWorld.getUnnormalizedRotation(this.rotation);

        this.scratchPosition.set(
            this.position.x - scene.getOriginX(),
            this.position.y - scene.getOriginY(),
            this.position.z - scene.getOriginZ());
        this.scratchRotation.set(this.rotation.x, this.rotation.y, this.rotation.z, this.rotation.w);

        if (place)
        {
            bodies.setPositionAndRotation(this.bodyId, this.scratchPosition, this.scratchRotation, EActivation.Activate);
            bodies.setLinearAndAngularVelocity(this.bodyId, ZERO, ZERO);
        }
        else
        {
            bodies.moveKinematic(this.bodyId, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
        }
    }

    /**
     * Pushes any setting the author has changed into the live body. Everything here can be changed
     * on a body that is already in the world, which matters: rebuilding it would hand it a new
     * identity, and Jolt refuses to restore a snapshot whose bodies no longer match — every rewind
     * past that point would break.
     */
    private void applySettings(PhysicsWorld physics, BodyInterface bodies)
    {
        float friction = this.form.friction.get();

        if (friction != this.lastFriction)
        {
            bodies.setFriction(this.bodyId, friction);

            this.lastFriction = friction;
        }

        float restitution = this.form.restitution.get();

        if (restitution != this.lastRestitution)
        {
            bodies.setRestitution(this.bodyId, restitution);

            this.lastRestitution = restitution;
        }

        float sizeX = this.form.sizeX.get();
        float sizeY = this.form.sizeY.get();
        float sizeZ = this.form.sizeZ.get();
        float mass = this.form.mass.get();

        boolean resized = sizeX != this.lastSizeX || sizeY != this.lastSizeY || sizeZ != this.lastSizeZ;

        if (resized)
        {
            float halfX = sizeX * 0.5F;
            float halfY = sizeY * 0.5F;
            float halfZ = sizeZ * 0.5F;

            /* Mass properties are not recomputed by the shape swap — the author's mass would be
             * replaced by one derived from the volume. They are set below instead. */
            bodies.setShape(this.bodyId, new BoxShape(new Vec3(halfX, halfY, halfZ)), false, EActivation.Activate);

            this.debug.setHalfExtents(halfX, halfY, halfZ);

            this.lastSizeX = sizeX;
            this.lastSizeY = sizeY;
            this.lastSizeZ = sizeZ;
        }

        if (resized || mass != this.lastMass)
        {
            this.applyMass(physics, sizeX, sizeY, sizeZ, mass);

            this.lastMass = mass;
        }
    }

    /**
     * Mass lives on the body's motion properties, which the body interface does not expose — it
     * has to be reached through a write lock on the body itself.
     */
    private void applyMass(PhysicsWorld physics, float sizeX, float sizeY, float sizeZ, float mass)
    {
        BodyLockWrite lock = new BodyLockWrite(physics.getSystem().getBodyLockInterface(), this.bodyId);

        try
        {
            if (!lock.succeeded())
            {
                return;
            }

            MotionProperties motion = lock.getBody().getMotionProperties();

            if (motion != null)
            {
                motion.setMassProperties(EAllowedDofs.All, massOf(sizeX * 0.5F, sizeY * 0.5F, sizeZ * 0.5F, mass));
            }
        }
        finally
        {
            lock.releaseLock();
        }
    }

    /**
     * Runs after the world stepped: hands the body's transform back to the form, expressed in the
     * parent frame the walk captured, because that is the frame the renderer substitutes the
     * form's transform in.
     *
     * <p>Skipped while the animation owns the body — there the keyframes already say where it is,
     * and writing the simulation's answer over them would fight the author for control.</p>
     */
    public void read(PhysicsWorld physics, FilmScene scene, boolean teleport)
    {
        if (this.kinematic || this.form.state == null)
        {
            return;
        }

        teleport |= this.justReleased;
        this.justReleased = false;

        physics.getBodies().getPositionAndRotation(this.bodyId, this.scratchPosition, this.scratchRotation);

        this.rotation.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW());
        this.bodyWorld.translationRotate(
            (float) (this.scratchPosition.xx() + scene.getOriginX()),
            (float) (this.scratchPosition.yy() + scene.getOriginY()),
            (float) (this.scratchPosition.zz() + scene.getOriginZ()),
            this.rotation);

        /* World → the frame the renderer applies the form's transform in: the actor's placement
         * times the chain above the form. For a root body the chain is the identity and this is
         * simply actor-local, as before. */
        this.local.set(this.actorWorld).mul(this.parentFrame).invert().mul(this.bodyWorld);

        this.local.getTranslation(this.position);
        this.local.getUnnormalizedRotation(this.rotation);

        this.form.state.set(this.position, this.rotation, teleport);
    }

    /**
     * Lets go of the form, so it goes back to being drawn from its keyframes. Called when the
     * scene is closed: the body behind this rig is about to stop existing.
     */
    public void release()
    {
        this.form.state = null;
    }

    /**
     * Pins the drawn transform to where it is, for a film that is not advancing — and takes the
     * author's edits to the body while it is standing still, which is exactly when they are made.
     * A collider resized on a paused film has to change under the cursor, or the slider reads as
     * doing nothing until playback is nudged.
     */
    public void freeze(PhysicsWorld physics)
    {
        this.applySettings(physics, physics.getBodies());

        if (this.form.state != null)
        {
            this.form.state.freeze();
        }
    }
}
