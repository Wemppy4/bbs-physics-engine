package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BodyLockWrite;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotionProperties;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EAllowedDofs;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.client.collision.CollisionCollector;
import wemppy.bbs_physics.client.collision.CollisionShapes;
import wemppy.bbs_physics.client.collision.JoltShapes;
import wemppy.bbs_physics.engine.BodyDrive;
import wemppy.bbs_physics.engine.KinematicDrive;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsMath;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.engine.SwingWindow;
import wemppy.bbs_physics.forms.FormBody;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsForms;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Ties one form carrying the rigid body modifier to its body in the simulation, in both
 * directions: while the animation owns the form, the keyframes drive the body; once it is let go,
 * the body drives what is drawn.
 *
 * <p>The handover is what makes a thrown object work: drop the authority from 1 to 0 on the frame
 * the hand opens and the object continues at the speed the keyframes were carrying it. Jolt does
 * most of that for free — a kinematic body keeps the velocity {@code moveKinematic} gave it, so a
 * body switched to dynamic is already flying — but that velocity is the <em>last tick's</em>, and
 * an eased keyframe arrives at rest by construction, so on the very frame the object is let go the
 * animation is often doing almost nothing. The release therefore reads the last few ticks and takes
 * the swing at its fastest ({@link SwingWindow}), which is the motion the author drew rather than
 * its dying tail.</p>
 *
 * <p><b>The handover is gradual, not a switch, and it is gradual in two places.</b> Below a full 1
 * the body is dynamic and is <em>pulled</em> towards the animated pose: each tick it is given the
 * velocity that would carry it there, blended with the velocity it already has by the handle's grip
 * ({@link PhysicsMath#grip}). At 1 that blend is the pose exactly (and the body is simply made
 * kinematic, which is the same thing for free and immovable); at 0 nothing is applied and the body
 * is on its own; in between it lags, sags under gravity and gives way to whatever it hits, by that
 * much. The second place is the drawing: what is on screen is the animated frame blended with the
 * simulated one by the handle, the way a ragdoll's bones have always been drawn.</p>
 *
 * <p>Both were needed, and having only the first is what left a fade still reading as a snap. The
 * pull is a deadbeat one — at half strength a body is back on its keyframes in three ticks — so
 * almost the whole travel of the handle looked identical to a full one, the fade lived in its last
 * few percent, and all the way down the pull was rubbing off the very speed the throw was made of.
 * Now the grip lets go early and the picture carries the rest, which costs the body no momentum at
 * all.</p>
 *
 * <p><b>The body has no shape of its own.</b> It is a mark saying "this falls"; what it collides as
 * is gathered from the collision markup of everything nested inside it (§5.1), welded into one
 * compound in the body's own frame. A body with nothing marked up inside it falls through the
 * world — the same thing a rigid body without a collider does in every other engine, and chosen
 * over quietly handing it a box the author never described. The debug overlay draws such a body in
 * its own colour so that it reads as unmarked rather than as broken.</p>
 *
 * <p>While the animation owns the body, the compound is rebuilt whenever the pose moves a piece
 * relative to the body, so a model welded into one lump keeps up with its own animation; the
 * moment it is let go it stops, and the lump keeps the shape it had at that instant — the pose it
 * was released in. Rebuilding is a shape swap on the same body, which leaves the world's set of
 * bodies untouched and therefore leaves the recording's channels where they are.</p>
 *
 * <p>A body can sit anywhere in an actor's form tree, not just at its root — a crate in a hand, a
 * helmet on a head. It is addressed by its <em>path</em> in the tree (the same path convention the
 * matrix walk uses), driven from the walk's evaluation of that path, and its simulated result is
 * carried back through the <em>parent frame</em> the renderer captured during the walk, because
 * the renderer substitutes a local transform and only the walk knows the chain above it.</p>
 *
 * <p>Known approximations. A released nested body is re-anchored to its parent frame every tick, so
 * on a fast-moving parent the draw interpolation composes two lerps and can wobble a touch. A body
 * nested inside <em>another physics body</em> is placed against the outer body's <em>animated</em>
 * frame, because the walk the frame is read from is the simulation's own and answers with animation
 * throughout — the outer body works, the inner one follows the animation of the thing it sits in. The throw, on the other hand, is no longer an
 * approximation of anything: every tick of the film is simulated exactly once, in order, so the
 * velocity a body inherits on release is the velocity the keyframes actually gave it — arriving at
 * that frame by scrubbing and arriving by playing are the same arrival now (§6).</p>
 */
public class BodyRig implements SceneRig
{
    private final Form form;
    private final String path;
    private final int bodyId;

    /**
     * The bone this body hangs on, named the way the pose walk names bones, or null when it hangs
     * on no bone. Only used to ask whether that bone is being ragdolled — the Р13 delta, which
     * cloth got first and this rig gets now (Э5): without it a crate strapped to a falling arm is
     * driven towards the arm's <em>animated</em> place while the renderer draws the arm fallen.
     */
    private final String anchor;

    /** This body's slot in the film's recording — see {@link PhysicsCache}. */
    private final int channel;

    /** The marked-up slots this body is made of, each in its own frame. Empty for a ghost. */
    private final List<CollisionCollector.Piece> pieces;

    /** Where those slots sat relative to the body when its shape was last built. */
    private final List<Matrix4f> builtFrom = new ArrayList<>();

    private final Matrix4f actorWorld = new Matrix4f();
    private final Matrix4f parentFrame = new Matrix4f();
    private final Matrix4f bodyWorld = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix4f local = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();

    /** The velocity blend that pulls this body towards its pose — held, because it carries scratch. */
    private final BodyDrive drive = new BodyDrive();

    /** The steer-or-place move of the kinematic phase — held, because it carries scratch. */
    private final KinematicDrive move = new KinematicDrive();

    /** The last few ticks of animated pose, for the speed the body is let go with. */
    private final SwingWindow swing = new SwingWindow();

    private final SceneBody debug;

    private boolean kinematic;

    /** Whether a broken drive velocity has already been reported — see {@link #drive}. */
    private boolean misfed;

    /* What the body in Jolt was last told. Compared against the form every tick, because the
     * author edits these while the film is open — and a setting that only took effect when the
     * scene happened to be rebuilt would read as a setting that does nothing. */
    private float lastMass;
    private float lastFriction;
    private float lastRestitution;
    private float lastLinearDamping;
    private float lastAngularDamping;
    private float lastGravity;
    private int lastLockMove;
    private int lastLockSpin;

    /** The sub-step count the damping was converted for — the rate depends on it. */
    private int dampedSteps;

    private BodyRig(Form form, String path, int bodyId, int channel, List<CollisionCollector.Piece> pieces, boolean kinematic, SceneBody debug, FormBody settings, String anchor)
    {
        this.anchor = anchor;
        this.form = form;
        this.path = path;
        this.bodyId = bodyId;
        this.channel = channel;
        this.pieces = pieces;
        this.kinematic = kinematic;
        this.debug = debug;

        this.lastMass = settings.mass();
        this.lastFriction = settings.friction();
        this.lastRestitution = settings.restitution();
        this.lastLinearDamping = settings.linearDamping();
        this.lastAngularDamping = settings.angularDamping();
        this.lastGravity = settings.gravity();
        this.lastLockMove = settings.lockMove();
        this.lastLockSpin = settings.lockSpin();
    }

    /**
     * Jolt's degrees of freedom for the axes the author froze. A frozen axis is a door, a wheel, a
     * swing, a puck on a table — without a joint and without the joint's jitter.
     */
    private static int allowedDofs(FormBody body)
    {
        int dofs = EAllowedDofs.All;

        if ((body.lockMove() & FormBody.AXIS_X) != 0) dofs &= ~EAllowedDofs.TranslationX;
        if ((body.lockMove() & FormBody.AXIS_Y) != 0) dofs &= ~EAllowedDofs.TranslationY;
        if ((body.lockMove() & FormBody.AXIS_Z) != 0) dofs &= ~EAllowedDofs.TranslationZ;
        if ((body.lockSpin() & FormBody.AXIS_X) != 0) dofs &= ~EAllowedDofs.RotationX;
        if ((body.lockSpin() & FormBody.AXIS_Y) != 0) dofs &= ~EAllowedDofs.RotationY;
        if ((body.lockSpin() & FormBody.AXIS_Z) != 0) dofs &= ~EAllowedDofs.RotationZ;

        return dofs;
    }

    /** Builds the body for a form carrying the rigid body modifier, found at {@code path}. */
    public static BodyRig build(PhysicsWorld physics, Form form, String path, MatrixCache matrices, FilmScene scene, String anchor)
    {
        FormBody body = PhysicsForms.getBody(form);

        List<CollisionCollector.Piece> pieces = CollisionCollector.collectBody(form, path, matrices);
        List<CollisionShapes.SubShape> subs = compose(pieces, path, matrices, new Matrix4f(), new ArrayList<>());

        ConstShape shape = JoltShapes.build(subs);
        boolean ghost = shape == null;

        if (ghost)
        {
            shape = JoltShapes.speck();
        }

        boolean kinematic = PhysicsForms.isKinematic(form);

        BodyCreationSettings settings = new BodyCreationSettings(
            shape,
            new RVec3(0D, 0D, 0D), Quat.sIdentity(),
            kinematic ? EMotionType.Kinematic : EMotionType.Dynamic,
            ghost ? PhysicsLayers.GHOST : PhysicsLayers.MOVING);

        settings.setFriction(body.friction());
        settings.setRestitution(body.restitution());

        /* Both damping knobs are a share of speed lost per film tick, like every damping number
         * in the addon, converted to Jolt's per-second rate for the sub-step count in use. */
        settings.setLinearDamping(PhysicsMath.bodyDamping(body.linearDamping(), physics.getCollisionSteps()));
        settings.setAngularDamping(PhysicsMath.bodyDamping(body.angularDamping(), physics.getCollisionSteps()));
        settings.setGravityFactor(body.gravity());
        settings.setAllowedDofs(allowedDofs(body));

        /* Swept collision rather than the default, which only asks where a body is at the end of a
         * step. A film tick is fifty milliseconds — long — so anything thrown or dropped from a
         * height covers more than its own thickness in one step and, tested only at the ends,
         * appears on the far side of the floor having touched nothing. That is most of what an
         * author reports as things falling through the world, and it costs a few percent of a step
         * to stop happening. */
        settings.setMotionQuality(EMotionQuality.LinearCast);

        /* Jolt would otherwise weigh the shape by its volume, which makes a big prop absurdly
         * heavy and a small one weightless. The author gives a mass; the inertia is worked out
         * from the assembled shape and scaled to it, so the thing tumbles like what it looks like
         * rather than like the box it used to be given. */
        settings.setMassPropertiesOverride(new MassProperties().setMass(body.mass()));
        settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

        /* Asleep until touched, when asked: a stack that starts awake shivers on the spot for a
         * second while the solver finds its rest, and a body the animation still owns is woken by
         * the handle anyway. */
        boolean asleep = body.asleep() && !kinematic;
        int id = physics.getBodies().createAndAddBody(settings, asleep ? EActivation.DontActivate : EActivation.Activate);

        /* An unmarked body is drawn in a colour of its own: it is about to fall through the floor,
         * and the overlay is the only place that can say "nothing was marked up here" before it
         * looks like the engine losing objects. */
        SceneBody debug = ghost
            ? new SceneBody(id, 0.05F, 0.05F, 0.05F, 1F, 0.25F, 0.25F)
            : new SceneBody(id, 1F, 0.55F, 0.2F);

        if (!ghost)
        {
            debug.addShapes(subs);
        }

        scene.addDebugBody(debug);

        PhysicsForms.setState(form, new PhysicsBodyState());

        BodyRig rig = new BodyRig(form, path, id, scene.addChannel(), ghost ? List.of() : pieces, kinematic, debug, body, anchor);

        compose(rig.pieces, path, matrices, rig.inverse, rig.builtFrom);

        return rig;
    }

    /**
     * The body's shape for the pose in {@code matrices}: every piece carried from its own frame
     * into the body's. The relative frames used are left in {@code frames}, so the next tick can
     * tell whether anything actually moved before paying for a rebuild.
     */
    private static List<CollisionShapes.SubShape> compose(List<CollisionCollector.Piece> pieces, String path, MatrixCache matrices, Matrix4f inverse, List<Matrix4f> frames)
    {
        frames.clear();

        List<CollisionShapes.SubShape> subs = new ArrayList<>();

        if (pieces.isEmpty() || matrices == null)
        {
            return subs;
        }

        MatrixCacheEntry body = matrices.get(path);

        if (body == null || body.matrix() == null)
        {
            return subs;
        }

        inverse.set(body.matrix()).invert();

        for (CollisionCollector.Piece piece : pieces)
        {
            MatrixCacheEntry entry = matrices.get(piece.path());

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            Matrix4f relative = new Matrix4f(inverse).mul(entry.matrix());

            frames.add(relative);

            for (CollisionShapes.SubShape sub : piece.shapes())
            {
                subs.add(CollisionShapes.carry(sub, relative));
            }
        }

        return subs;
    }

    /**
     * Runs before the world steps: keeps the body's motion type in step with the authority track
     * and, while the animation is in charge, steers the body along the keyframes and keeps its
     * shape in step with the pose.
     *
     * <p>The keyframed placement is read from the shared matrix walk at this body's own path —
     * which folds in the whole chain above it (parent transforms, the bone it hangs on) — rather
     * than from the form's transform alone. The walk also just captured the parent frame through
     * the renderer, which is copied out here for {@link #read} to carry the simulation's answer
     * back through.</p>
     *
     * <p>A reset — the scene starting over — stands <em>every</em> body at its animated pose and
     * stops it, the simulated ones included. That is the only moment a released body's keyframes are
     * read: from there on its placement is the simulation's answer, not the author's.</p>
     */
    @Override
    public void update(RigUpdate update)
    {
        PhysicsWorld physics = update.physics;
        FilmScene scene = update.scene;
        MatrixCache matrices = update.matrices;
        Matrix4f actorWorld = update.actorWorld;
        boolean reset = update.reset;
        PhysicsBodyState state = PhysicsForms.getState(this.form);

        /* The bone this body hangs on may be falling, and the pose walk cannot say so — it runs
         * with the ragdoll's substitution off. The published delta is multiplied onto the frame
         * everything here is composed on, which fixes both halves of the Р13 problem at once: the
         * drive's target (the crate follows the fallen arm) and the frame the answer is carried
         * back through (the renderer's own stack is the fallen one, so a return frame built on the
         * animated arm would count the fall twice). One matrix, both uses, no double counting. */
        Matrix4f delta = this.anchor == null ? null : update.deltas.get(this.anchor);

        if (delta == null)
        {
            this.actorWorld.set(actorWorld);
        }
        else
        {
            this.actorWorld.set(delta).mul(actorWorld);
        }

        if (state != null)
        {
            this.parentFrame.set(state.getWalkParentFrame());
        }

        BodyInterface bodies = physics.getBodies();

        /* Read once per tick rather than per question: every one of these parses the form's stored
         * map, and the author can be editing them while the film is open. */
        FormBody body = PhysicsForms.getBody(this.form);
        float authority = PhysicsForms.getAuthority(this.form);
        boolean wanted = authority >= 1F;

        /* Whether the body is to be stood at its pose and stopped rather than moved towards it over
         * the coming tick. Every step is exactly one tick now, so this is no longer about jumps: it
         * is the scene starting over, or a body the animation has just taken back. */
        boolean put = reset;

        if (wanted != this.kinematic)
        {
            /* The moment of release (or of being grabbed back). Jolt keeps the velocity across the
             * change, which is exactly the throw. */
            bodies.setMotionType(this.bodyId, wanted ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);

            this.kinematic = wanted;

            if (!wanted)
            {
                /* Let go with the swing rather than with the last tick of it — see SwingWindow.
                 * The velocity Jolt carried across the change is that last tick, and on an eased
                 * keyframe the last tick is where the animation was already stopping. */
                this.swing.release(bodies, this.bodyId);
            }

            /* Taken back by the animation, after however long living its own life: it is nowhere
             * near its keyframe any more. Steering it back over a single tick would send it there
             * at the whole distance divided by a twentieth of a second, scattering everything in
             * between. Put down, not driven. */
            put |= wanted;
        }

        if (this.kinematic)
        {
            this.reshape(bodies, matrices);
        }

        this.applySettings(physics, bodies, body);

        if (!put && !this.kinematic && authority <= 0F)
        {
            /* Nothing to say to a body that is entirely on its own. */
            return;
        }

        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.path);

        if (entry == null || entry.matrix() == null)
        {
            /* A tick with no pose at all is a hole in the window: the ticks either side of it are
             * two ticks apart and would read as half the speed they were. */
            this.swing.clear();

            return;
        }

        this.bodyWorld.set(this.actorWorld).mul(entry.matrix());
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

        if (put)
        {
            this.move.place(bodies, this.bodyId, this.scratchPosition, this.scratchRotation);

            /* Put down rather than moved: the gap between this pose and the last is a jump, not a
             * swing, and the window starts again from here. */
            this.swing.clear();
            this.swing.push(this.scratchPosition, this.scratchRotation);
        }
        else if (this.kinematic)
        {
            /* Steered when the keyframes moved, placed when they cut — see KinematicDrive: a
             * crate keyed across the set in one frame must not sweep the whole way there. */
            if (this.move.move(bodies, this.bodyId, this.scratchPosition, this.scratchRotation))
            {
                this.swing.clear();
            }

            this.swing.push(this.scratchPosition, this.scratchRotation);
        }
        else
        {
            this.drive(bodies, authority);
        }
    }

    /**
     * Pulls a body the animation only partly owns towards the pose its keyframes describe — the
     * shared velocity blend, whose reasoning lives in {@link BodyDrive}.
     */
    private void drive(BodyInterface bodies, float authority)
    {
        /* By the grip rather than by the handle itself: what holds the object through the middle of
         * a fade is the drawn blend, and a pull that held it there too would be taking the throw's
         * momentum away to do work the picture is already doing. */
        if (this.drive.apply(bodies, this.bodyId, this.scratchPosition, this.scratchRotation, PhysicsMath.grip(authority)))
        {
            return;
        }

        if (!this.misfed)
        {
            this.misfed = true;

            BBSPhysics.LOGGER.warn(
                "The drive for the physics body '{}' at '{}' came out unusable — {}, handle {} — so the body falls free instead. The pose it is pulled towards is broken.",
                this.form.getDisplayName(), this.path, this.drive.describe(), authority);
        }
    }

    /**
     * Re-welds the body's shape for the current pose, when the pose actually moved one of its
     * pieces. The check is worth making: a crate in a hand never moves relative to its own body, so
     * the common case pays for a few matrix comparisons and nothing else, while an animated model
     * welded into one lump gets a shape that keeps up with it.
     */
    private void reshape(BodyInterface bodies, MatrixCache matrices)
    {
        if (this.pieces.isEmpty() || matrices == null)
        {
            return;
        }

        List<Matrix4f> frames = new ArrayList<>(this.pieces.size());
        List<CollisionShapes.SubShape> subs = compose(this.pieces, this.path, matrices, this.inverse, frames);

        if (frames.equals(this.builtFrom) || subs.isEmpty())
        {
            return;
        }

        ConstShape shape = JoltShapes.build(subs);

        if (shape == null)
        {
            return;
        }

        /* The mass properties are not recomputed by the swap — the author's mass would be replaced
         * by one derived from the volume. They are set from the new shape just below. */
        bodies.setShape(this.bodyId, shape, false, EActivation.Activate);

        this.builtFrom.clear();
        this.builtFrom.addAll(frames);
        this.debug.setShapes(subs);

        /* A new shape means a new inertia, so the mass has to be reapplied against it. */
        this.lastMass = Float.NaN;
    }

    /**
     * Pushes any setting the author has changed into the live body. Everything here can be changed
     * on a body that is already in the world, which matters: rebuilding it would hand it a new
     * identity and a new slot in the recording, and the whole film would have to be re-recorded for
     * a change of friction.
     *
     * <p>Run while recording, which is the only time the body is touched at all. An edit throws the
     * recording away (see {@link FilmScene#invalidate()}), so a slider moved on a paused film is
     * picked up by the re-recording that follows rather than by a special case here — which is what
     * the old {@code freeze()} was.</p>
     */
    private void applySettings(PhysicsWorld physics, BodyInterface bodies, FormBody body)
    {
        float friction = body.friction();

        if (friction != this.lastFriction)
        {
            bodies.setFriction(this.bodyId, friction);

            this.lastFriction = friction;
        }

        float restitution = body.restitution();

        if (restitution != this.lastRestitution)
        {
            bodies.setRestitution(this.bodyId, restitution);

            this.lastRestitution = restitution;
        }

        float mass = body.mass();

        /* The frozen axes ride on the same call as the mass — Jolt sets both at once. */
        if (mass != this.lastMass || body.lockMove() != this.lastLockMove || body.lockSpin() != this.lastLockSpin)
        {
            this.applyMass(physics, bodies, mass, allowedDofs(body));

            this.lastMass = mass;
            this.lastLockMove = body.lockMove();
            this.lastLockSpin = body.lockSpin();
        }

        float gravity = body.gravity();

        if (gravity != this.lastGravity)
        {
            bodies.setGravityFactor(this.bodyId, gravity);

            this.lastGravity = gravity;
        }

        int steps = physics.getCollisionSteps();

        if (body.linearDamping() != this.lastLinearDamping || body.angularDamping() != this.lastAngularDamping || steps != this.dampedSteps)
        {
            this.applyDamping(physics, body.linearDamping(), body.angularDamping(), steps);

            this.lastLinearDamping = body.linearDamping();
            this.lastAngularDamping = body.angularDamping();
            this.dampedSteps = steps;
        }
    }

    /** Damping lives on the motion properties, behind the same lock as the mass. */
    private void applyDamping(PhysicsWorld physics, float linear, float angular, int steps)
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
                motion.setLinearDamping(PhysicsMath.bodyDamping(linear, steps));
                motion.setAngularDamping(PhysicsMath.bodyDamping(angular, steps));
            }
        }
        finally
        {
            lock.releaseLock();
        }
    }

    /**
     * Mass lives on the body's motion properties, which the body interface does not expose — it
     * has to be reached through a write lock on the body itself. The inertia comes from the body's
     * own shape, scaled to the mass the author asked for, so a long plank tumbles like a plank.
     */
    private void applyMass(PhysicsWorld physics, BodyInterface bodies, float mass, int dofs)
    {
        BodyLockWrite lock = new BodyLockWrite(physics.getSystem().getBodyLockInterface(), this.bodyId);

        try
        {
            if (!lock.succeeded())
            {
                return;
            }

            MotionProperties motion = lock.getBody().getMotionProperties();
            ConstShape shape = lock.getBody().getShape();

            if (motion == null || shape == null)
            {
                return;
            }

            MassProperties properties = shape.getMassProperties();

            properties.scaleToMass(mass);
            motion.setMassProperties(dofs, properties);
        }
        finally
        {
            lock.releaseLock();
        }
    }

    /**
     * Runs right after the world stepped: works out where the body ended up, in the frame the
     * renderer will substitute it into, and writes that into the recording under {@code tick}.
     *
     * <p><b>The conversion happens here, not at draw time, and that is what makes the recording
     * cheap.</b> The frame a body's answer is expressed in — the actor's placement times the chain
     * of forms above it — is a function of the tick, and the tick has just been posed to be
     * simulated, so both are at hand for free. Storing the converted numbers means playing a
     * recorded film back evaluates no poses at all: the renderer takes seven floats and applies
     * them.</p>
     *
     * <p>Written on every tick, the kinematic ones included, even though the renderer ignores the
     * answer while the animation is in charge. That is what keeps a release smooth: a drawn frame
     * sits between two ticks, so the tick a body is let go on needs the tick before it to be
     * interpolated from, and while the body is kinematic what is recorded is exactly what was being
     * drawn anyway.</p>
     */
    @Override
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
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

        cache.write(tick, this.channel, this.position, this.rotation, PhysicsForms.getAuthority(this.form));
    }

    /**
     * Hands the form the recorded transform for the frame being drawn, or tells it there is none.
     *
     * <p>No recording for this tick means plain animation (Р8.1): the form is drawn from its
     * keyframes, as though it had no physics, until the background catch-up reaches it.</p>
     */
    @Override
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        PhysicsBodyState state = PhysicsForms.getState(this.form);

        if (state == null)
        {
            return;
        }

        if (cache.read(tick, this.channel, this.position, this.rotation))
        {
            state.set(this.position, this.rotation, cache.readAuthority(tick, this.channel), teleport);
        }
        else
        {
            state.setUnsimulated();
        }
    }

    /** The recorded transform for {@code tick}, reported to the bake instead of the form. */
    @Override
    public void bake(PhysicsCache cache, int tick, PhysicsBake bake)
    {
        if (cache.read(tick, this.channel, this.position, this.rotation))
        {
            bake.body(this.form, this.path, this.position, this.rotation, cache.readAuthority(tick, this.channel));
        }
    }

    /**
     * An impulse clip's push (Э5). The push itself refuses anything not dynamic, so a body the
     * animation holds takes nothing — kicking the keyframes would move nothing and lie about it.
     */
    @Override
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        push.apply(physics.getBodies(), this.bodyId);
    }

    /**
     * A crate strapped to a falling arm has to be driven towards where the arm actually is — the Р13
     * delta, which cloth got first and this rig got with Э5. Without it the body is pulled towards
     * the arm's <em>animated</em> place while the renderer draws the arm fallen.
     */
    @Override
    public boolean readsBoneDeltas()
    {
        return true;
    }

    /**
     * Whether nothing inside this body was marked up as collidable, so it collides with nothing and
     * falls through the world. Deliberate (§5.1) and reported rather than hidden — it is one of the
     * few states that looks exactly like the engine being broken.
     */
    @Override
    public boolean isGhost()
    {
        return this.pieces.isEmpty();
    }

    /** Where the body stands, in the scene's own coordinates — for the status readout. */
    @Override
    public boolean getScenePosition(Vector3f out)
    {
        this.debug.getPosition(1F, out);

        return true;
    }

    /** The path this body's form lives at in the actor's tree — "" for the root form. */
    public String getPath()
    {
        return this.path;
    }

    /** The body's id in the world — what a chain's bottom end is tied to. */
    public int getBodyId()
    {
        return this.bodyId;
    }

    /**
     * Lets go of the form, so it goes back to being drawn from its keyframes. Called when the
     * scene is closed: the body behind this rig is about to stop existing.
     */
    @Override
    public void release()
    {
        PhysicsForms.setState(this.form, null);
    }
}
