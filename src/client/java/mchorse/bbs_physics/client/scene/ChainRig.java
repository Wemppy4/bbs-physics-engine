package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotorSettings;
import com.github.stephengold.joltjni.PointConstraint;
import com.github.stephengold.joltjni.PointConstraintSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.SwingTwistConstraint;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EMotorState;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.chain.ChainForm;
import mchorse.bbs_physics.chain.ChainState;
import mchorse.bbs_physics.client.collision.CollisionShapes;
import mchorse.bbs_physics.client.collision.JoltShapes;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ties one chain form to its strand of rigid bodies in the simulation: capsule segments held
 * together by cone joints, a spring in every joint pulling the strand back to its authored line,
 * and two ends — the top riding the form's frame, the bottom free, pinned to whatever the anchor
 * track names, or tied to a physics body it then honestly drags.
 *
 * <p><b>Rigid segments rather than a soft rope</b>, deliberately: a segment has an orientation, so
 * the strand can twist and a link form drawn on it turns with it — a soft body's vertices are
 * points and know no such thing. It also buys everything the rigid bodies already have: LinearCast
 * against the floor, impulse clips, the proven velocity-blend drive.</p>
 *
 * <p><b>The shape of the strand is the joints' business, not the drive's.</b> Between neighbours
 * sits a wide cone ({@code SwingTwistConstraint}) whose motor, when the stiffness knob is up, is a
 * position spring toward the straight rest pose — Jolt's own way of saying "return to the shape you
 * were built in", which is what makes the difference between a rope and a garden hose. The motor is
 * a spring with a frequency, not our hand-rolled velocity mix, so the stiffness costs nothing new
 * and cannot poison anything.</p>
 *
 * <p><b>The ends.</b> The top hangs from a small kinematic body driven along the form's frame each
 * tick — proven by the TearSmoke stand: a kinematic parent honestly drags a dynamic strand. The
 * bottom is a {@link PointConstraint} whose far side is either another kinematic pin (driven to
 * wherever the anchor track resolves — a bone of any actor) or a simulated body itself, in which
 * case the rope <em>pulls</em>: a crate on a rope swings the crate. The anchor is keyframable, so
 * re-tying mid-film is a keyframe, and a fade between keys walks the pin from where the strand's
 * tip is to where it is going instead of snapping it — the old chain solver's own trick, redone
 * in Jolt terms.</p>
 *
 * <p>The one animation handle (§4) means what it means everywhere: at 1 the segments are kinematic
 * and stand on the authored straight line; below 1 they are dynamic and pulled toward it by the
 * velocity blend every rigid body here uses; at 0 the strand is entirely the world's.</p>
 */
public class ChainRig
{
    /** Shared, never written to — the velocity a placed body is stopped with. */
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    /** How far a joint lets neighbours lean — wide on purpose: a chain's shape is the spring's job. */
    private static final float CONE_DEGREES = 85F;
    private static final float TWIST_DEGREES = 60F;

    /** Spin bleeds off faster than travel, the same tuning every rigid body here carries. */
    private static final float ANGULAR_DAMPING = 0.3F;

    /**
     * How the stiffness knob maps to the joint spring, in Hz — 1.0 is a hose that barely bends.
     *
     * <p>The joints deliberately carry <b>no friction</b>, unlike the ragdoll's: friction stalls
     * the spring short of its target — measured on the ChainSmoke stand, 0.05 N·m of it parked
     * every joint about a degree and a half off straight, which across eight joints bent a fully
     * stiff strand twelve degrees sideways for good. Settling is the damping knob's job here (the
     * bodies' own damping, and the motor spring's), not friction's.</p>
     */
    private static final float SPRING_TOP_HZ = 12F;

    /** What the bottom end is doing this tick — resolved by the scene, acted on here. */
    public static final int ATTACH_NONE = 0;
    public static final int ATTACH_PIN = 1;
    public static final int ATTACH_BODY = 2;

    /**
     * The bottom end's marching orders for one tick: free, pinned to a resolved point (scene
     * coordinates), or tied to a simulated body. The weight is the anchor track's fade — the pin
     * walks from the strand's own tip to the resolved point by that much.
     */
    public record Attach(int mode, float weight, float x, float y, float z, int bodyId)
    {
        public static final Attach NONE = new Attach(ATTACH_NONE, 0F, 0F, 0F, 0F, -1);
    }

    private final ChainForm form;
    private final String path;
    private final String anchor;

    private final int segments;
    private final float segmentLength;

    private final int[] bodies;
    private final int[] channels;
    private final SwingTwistConstraint[] joints;

    /** The last segment's Body reference — constraint settings take a Body, not an id. */
    private final Body lastBody;

    /** The kinematic body the top of the strand hangs from, or -1 when the strand is loose. */
    private final int rootId;
    private final SwingTwistConstraint rootJoint;

    /** The kinematic body the bottom end is pinned to when the anchor names a bone or a point. */
    private final int pinId;
    private final PointConstraint pinJoint;

    /** The bottom end's tie to a simulated body, one per candidate, made in the second build pass. */
    private final Map<Integer, PointConstraint> targetJoints = new HashMap<>(0);

    /** Who inside this strand is excused from colliding with whom. Held: Jolt keeps it by pointer. */
    private final GroupFilterTable filter;

    private final Matrix4f formWorld = new Matrix4f();
    private final Matrix4f formWorldInverse = new Matrix4f();
    private final Quaternionf formRotation = new Quaternionf();
    private final Vector3f point = new Vector3f();
    private final Vector3f tip = new Vector3f();
    private final Quaternionf targetRotation = new Quaternionf();
    private final Quaternionf currentQuat = new Quaternionf();
    private final Quaternionf delta = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();
    private final RVec3 currentPosition = new RVec3();
    private final Quat currentRotation = new Quat();
    private final Vec3 linear = new Vec3();
    private final Vec3 angular = new Vec3();

    /** Where the strand last was in scene coordinates — what the readout judges against the window. */
    private final Vector3f recordedCenter = new Vector3f();
    private boolean centered;

    private boolean kinematic;
    private boolean lost;
    private boolean misfed;

    /** What the bottom end was doing last tick — the state machine that re-ties on a keyframe. */
    private int attachMode = ATTACH_NONE;
    private int attachBody = -1;

    /* What the live bodies were last told, checked per tick — an author edits these with the film
     * open, and a slider that does nothing until the scene is rebuilt reads as broken. */
    private float lastFriction;
    private float lastGravity;
    private float lastStiffness = Float.NaN;
    private float lastDamping = Float.NaN;

    private ChainRig(ChainForm form, String path, String anchor, int[] bodies, int[] channels, SwingTwistConstraint[] joints, Body lastBody, int rootId, SwingTwistConstraint rootJoint, int pinId, PointConstraint pinJoint, GroupFilterTable filter, boolean kinematic)
    {
        this.form = form;
        this.path = path;
        this.anchor = anchor;
        this.segments = form.segments.get();
        this.segmentLength = form.getSegmentLength();
        this.bodies = bodies;
        this.channels = channels;
        this.joints = joints;
        this.lastBody = lastBody;
        this.rootId = rootId;
        this.rootJoint = rootJoint;
        this.pinId = pinId;
        this.pinJoint = pinJoint;
        this.filter = filter;
        this.kinematic = kinematic;

        this.lastFriction = form.friction.get();
        this.lastGravity = form.gravity.get();
    }

    /**
     * Builds the strand for a chain form found at {@code path} in an actor's tree. Null when the
     * pose has no frame for that path — the scene will be rebuilt when the cast changes.
     *
     * @param group a collision group id no other strand or actor in the scene uses
     */
    public static ChainRig build(PhysicsWorld physics, ChainForm form, String path, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, int group, String anchor)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        Matrix4f formWorld = new Matrix4f(actorWorld).mul(entry.matrix());
        Quaternionf formRotation = rotationOf(formWorld);

        int segments = form.segments.get();
        float segmentLength = form.getSegmentLength();
        float radius = Math.min(form.radius.get(), segmentLength * 0.45F);
        float segmentMass = Math.max(form.mass.get(), 0.01F) / segments;
        boolean kinematic = PhysicsForms.isKinematic(form);

        BodyInterface bodies = physics.getBodies();

        /* Segments and their two service pins, excused pair by pair below. */
        GroupFilterTable filter = new GroupFilterTable(segments);

        int[] ids = new int[segments];
        int[] channels = new int[segments];
        Body[] built = new Body[segments];

        /* The capsule stands along its local Y; at rest the strand hangs straight down the form's
         * own -Y, so every segment starts with the form's rotation. */
        CollisionShapes.SubShape capsule = new CollisionShapes.SubShape(
            CollisionKind.CAPSULE,
            new Vector3f(radius, Math.max(segmentLength / 2F - radius, 0.005F), radius),
            new Vector3f(),
            new Quaternionf());

        Vector3f center = new Vector3f();

        for (int i = 0; i < segments; i++)
        {
            center.set(0F, form.restY(i), 0F);
            formWorld.transformPosition(center);

            BodyCreationSettings settings = new BodyCreationSettings(
                JoltShapes.leaf(capsule),
                new RVec3(
                    center.x - scene.getOriginX(),
                    center.y - scene.getOriginY(),
                    center.z - scene.getOriginZ()),
                new Quat(formRotation.x, formRotation.y, formRotation.z, formRotation.w),
                kinematic ? EMotionType.Kinematic : EMotionType.Dynamic,
                PhysicsLayers.MOVING);

            settings.setFriction(form.friction.get());
            settings.setRestitution(0.05F);
            settings.setGravityFactor(form.gravity.get());
            settings.setAngularDamping(ANGULAR_DAMPING);
            settings.setLinearDamping(0.05F + 0.45F * form.damping.get());

            /* A film tick is fifty milliseconds; a swung rope end covers many times its own
             * thickness in one, and tested only at the ends it passes through what it whipped. */
            settings.setMotionQuality(EMotionQuality.LinearCast);

            /* The author gives the strand's mass; Jolt would weigh the capsule by volume and make
             * a thin rope weightless. Inertia still comes from the shape, scaled to the mass. */
            settings.setMassPropertiesOverride(new MassProperties().setMass(segmentMass));
            settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

            settings.setCollisionGroup(new CollisionGroup(filter, group, i));

            Body body = bodies.createBody(settings);

            bodies.addBody(body.getId(), EActivation.Activate);

            built[i] = body;
            ids[i] = body.getId();
            channels[i] = scene.addChannel();

            SceneBody debug = new SceneBody(body.getId(), 0.35F, 0.9F, 0.6F);

            debug.addShapes(List.of(capsule));
            scene.addDebugBody(debug);
        }

        /* The strand's down direction in the world — every joint's twist axis at rest. */
        Vector3f down = formWorld.transformDirection(new Vector3f(0F, -1F, 0F));

        if (down.lengthSquared() < 1e-12F)
        {
            down.set(0F, -1F, 0F);
        }

        down.normalize();

        Vector3f plane = perpendicular(down);
        float stiffness = form.stiffness.get();
        float damping = form.damping.get();

        SwingTwistConstraint[] joints = new SwingTwistConstraint[Math.max(segments - 1, 0)];

        for (int i = 1; i < segments; i++)
        {
            Vector3f at = new Vector3f(0F, -i * segmentLength, 0F);

            formWorld.transformPosition(at);

            joints[i - 1] = cone(physics, built[i - 1], built[i], at, down, plane, scene, stiffness, damping);

            /* Neighbours share a joint and their capsules meet at it by design; letting them also
             * collide would have every joint permanently fighting its own limits. */
            filter.disableCollision(i - 1, i);
        }

        /* The top of the strand: a kinematic speck the first segment is jointed to, driven along
         * the form's frame every tick. GHOST — it holds through the joint and must meet nothing. */
        int rootId = -1;
        SwingTwistConstraint rootJoint = null;

        if (form.heldStart.get())
        {
            Vector3f at = new Vector3f(0F, 0F, 0F);

            formWorld.transformPosition(at);

            BodyCreationSettings settings = new BodyCreationSettings(
                new SphereShape(0.02F),
                new RVec3(at.x - scene.getOriginX(), at.y - scene.getOriginY(), at.z - scene.getOriginZ()),
                new Quat(formRotation.x, formRotation.y, formRotation.z, formRotation.w),
                EMotionType.Kinematic,
                PhysicsLayers.GHOST);

            Body root = bodies.createBody(settings);

            bodies.addBody(root.getId(), EActivation.Activate);

            rootId = root.getId();
            rootJoint = cone(physics, root, built[0], at, down, plane, scene, stiffness, damping);
        }

        /* The bottom end's pin: another kinematic speck, and one point constraint to the last
         * segment, disabled until the anchor track names something. Created now rather than when
         * needed, so the recording's world never changes shape. */
        Vector3f tipRest = new Vector3f(0F, -segments * segmentLength, 0F);

        formWorld.transformPosition(tipRest);

        BodyCreationSettings pinSettings = new BodyCreationSettings(
            new SphereShape(0.02F),
            new RVec3(tipRest.x - scene.getOriginX(), tipRest.y - scene.getOriginY(), tipRest.z - scene.getOriginZ()),
            Quat.sIdentity(),
            EMotionType.Kinematic,
            PhysicsLayers.GHOST);

        Body pin = bodies.createBody(pinSettings);

        bodies.addBody(pin.getId(), EActivation.Activate);

        /* Anchored in each body's own space, once, for good: "the last segment's tip end sits on
         * the pin's centre". The live constraint offers no way to re-anchor its points, and none
         * is needed — where the tip is held is wherever the pin is driven, and the pin is ours. */
        PointConstraintSettings pinJointSettings = new PointConstraintSettings();

        pinJointSettings.setSpace(EConstraintSpace.LocalToBodyCOM);
        pinJointSettings.setPoint1(new RVec3(0D, -segmentLength / 2D, 0D));
        pinJointSettings.setPoint2(new RVec3(0D, 0D, 0D));

        PointConstraint pinJoint = (PointConstraint) pinJointSettings.create(built[segments - 1], pin);

        pinJoint.setEnabled(false);
        physics.getSystem().addConstraint(pinJoint);

        form.state = new ChainState(segments);

        return new ChainRig(form, path, anchor, ids, channels, joints, built[segments - 1], rootId, rootJoint, pin.getId(), pinJoint, filter, kinematic);
    }

    /** One cone joint between neighbours, with the stiffness spring on its motors. */
    private static SwingTwistConstraint cone(PhysicsWorld physics, Body parent, Body child, Vector3f at, Vector3f down, Vector3f plane, FilmScene scene, float stiffness, float damping)
    {
        SwingTwistConstraintSettings settings = new SwingTwistConstraintSettings();

        RVec3 point = new RVec3(
            at.x - scene.getOriginX(),
            at.y - scene.getOriginY(),
            at.z - scene.getOriginZ());

        settings.setPosition1(point);
        settings.setPosition2(point);
        settings.setTwistAxis1(new Vec3(down.x, down.y, down.z));
        settings.setTwistAxis2(new Vec3(down.x, down.y, down.z));
        settings.setPlaneAxis1(new Vec3(plane.x, plane.y, plane.z));
        settings.setPlaneAxis2(new Vec3(plane.x, plane.y, plane.z));
        settings.setNormalHalfConeAngle((float) Math.toRadians(CONE_DEGREES));
        settings.setPlaneHalfConeAngle((float) Math.toRadians(CONE_DEGREES));
        settings.setTwistMinAngle((float) Math.toRadians(-TWIST_DEGREES));
        settings.setTwistMaxAngle((float) Math.toRadians(TWIST_DEGREES));

        SwingTwistConstraint constraint = (SwingTwistConstraint) settings.create(parent, child);

        physics.getSystem().addConstraint(constraint);

        /* The spring toward the built shape. Both bodies were created in the same orientation, so
         * the rest relative rotation is the identity — the default target — and the motor in
         * Position mode is precisely "return to straight". */
        tune(constraint, stiffness, damping);

        return constraint;
    }

    /** Sets one joint's motors to the stiffness and damping knobs — at build, and live when they move. */
    private static void tune(SwingTwistConstraint constraint, float stiffness, float damping)
    {
        EMotorState state = stiffness > 0F ? EMotorState.Position : EMotorState.Off;

        constraint.setSwingMotorState(state);
        constraint.setTwistMotorState(state);

        if (stiffness > 0F)
        {
            float frequency = 0.5F + stiffness * (SPRING_TOP_HZ - 0.5F);
            float ratio = 0.1F + damping * 0.9F;

            for (MotorSettings motor : new MotorSettings[] {constraint.getSwingMotorSettings(), constraint.getTwistMotorSettings()})
            {
                motor.getSpringSettings().setFrequency(frequency);
                motor.getSpringSettings().setDamping(ratio);
            }
        }
    }

    /**
     * The second build pass, run once every actor's bodies exist: a disabled tie from the strand's
     * last segment to every simulated body the anchor track could name. Made up front — never
     * mid-recording — so the world's set of constraints stays the same shape whatever the keys say.
     */
    public void linkTargets(PhysicsWorld physics, Map<Integer, Body> candidates)
    {
        for (Map.Entry<Integer, Body> candidate : candidates.entrySet())
        {
            /* Anchored in local space once, like the pin: the strand's tip end onto the target's
             * centre of mass. The live constraint cannot be re-anchored, so "the rope grabs the
             * crate by its middle" is the deal — named in the UI hint rather than discovered. */
            PointConstraintSettings settings = new PointConstraintSettings();

            settings.setSpace(EConstraintSpace.LocalToBodyCOM);
            settings.setPoint1(new RVec3(0D, -this.segmentLength / 2D, 0D));
            settings.setPoint2(new RVec3(0D, 0D, 0D));

            PointConstraint constraint = (PointConstraint) settings.create(this.lastBody, candidate.getValue());

            constraint.setEnabled(false);
            physics.getSystem().addConstraint(constraint);

            this.targetJoints.put(candidate.getKey(), constraint);
        }
    }

    /**
     * Runs before the world steps: drives the strand toward its authored line by however much of
     * it the animation owns, stands the top pin on the form's frame, and ties or unties the bottom
     * end as the anchor track says.
     *
     * @param reset whether the scene itself is starting over at this tick, in which case the whole
     *              strand is stood on its straight line and stopped — the film's opening pose
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean reset, Map<String, Matrix4f> deltas, Attach attach)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.path);

        if (entry != null && entry.matrix() != null)
        {
            /* The bone this strand hangs on may be falling — same delta, same reason as cloth. */
            Matrix4f delta = this.anchor == null ? null : deltas.get(this.anchor);

            if (delta == null)
            {
                this.formWorld.set(actorWorld).mul(entry.matrix());
            }
            else
            {
                this.formWorld.set(delta).mul(actorWorld).mul(entry.matrix());
            }

            this.formRotation.set(rotationOf(this.formWorld));
        }

        BodyInterface bodies = physics.getBodies();

        this.applySettings(physics);

        float authority = PhysicsForms.getAuthority(this.form);
        boolean wanted = authority >= 1F;

        /* The top pin rides the form's frame — teleported on a reset, steered otherwise, so the
         * velocity of the hand that holds the rope is real and shoves the strand along. */
        if (this.rootId != -1)
        {
            this.point.set(0F, 0F, 0F);
            this.formWorld.transformPosition(this.point);

            if (this.placeable(this.point))
            {
                this.scratchPosition.set(
                    this.point.x - scene.getOriginX(),
                    this.point.y - scene.getOriginY(),
                    this.point.z - scene.getOriginZ());
                this.scratchRotation.set(this.formRotation.x, this.formRotation.y, this.formRotation.z, this.formRotation.w);

                if (reset)
                {
                    bodies.setPositionAndRotation(this.rootId, this.scratchPosition, this.scratchRotation, EActivation.Activate);
                    bodies.setLinearAndAngularVelocity(this.rootId, ZERO, ZERO);
                }
                else
                {
                    bodies.moveKinematic(this.rootId, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
                }
            }
        }

        /* Motion type first, exactly like the ragdoll: switching to dynamic keeps the kinematic
         * velocity, so a strand released mid-swing inherits the swing. */
        if (wanted != this.kinematic || reset)
        {
            this.kinematic = wanted;

            for (int id : this.bodies)
            {
                bodies.setMotionType(id, wanted ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);
            }
        }

        for (int i = 0; i < this.segments; i++)
        {
            this.point.set(0F, this.form.restY(i), 0F);
            this.formWorld.transformPosition(this.point);

            if (!this.placeable(this.point))
            {
                continue;
            }

            this.scratchPosition.set(
                this.point.x - scene.getOriginX(),
                this.point.y - scene.getOriginY(),
                this.point.z - scene.getOriginZ());
            this.scratchRotation.set(this.formRotation.x, this.formRotation.y, this.formRotation.z, this.formRotation.w);

            if (reset || wanted)
            {
                if (reset)
                {
                    bodies.setPositionAndRotation(this.bodies[i], this.scratchPosition, this.scratchRotation, EActivation.Activate);
                    bodies.setLinearAndAngularVelocity(this.bodies[i], ZERO, ZERO);
                }
                else
                {
                    bodies.moveKinematic(this.bodies[i], this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
                }
            }
            else if (authority > 0F)
            {
                this.drive(bodies, i, authority);
            }
        }

        this.applyAttach(physics, scene, reset ? Attach.NONE : attach);
    }

    /** Whether a drive target is a place at all — a broken pose must not become the solver's problem. */
    private boolean placeable(Vector3f target)
    {
        if (Float.isFinite(target.x) && Float.isFinite(target.y) && Float.isFinite(target.z))
        {
            return true;
        }

        if (!this.misfed)
        {
            this.misfed = true;

            BBSPhysics.LOGGER.warn(
                "The drive for the chain '{}' at '{}' came out unusable ({}, {}, {}), so the strand is left to itself. The pose it is pulled towards is broken.",
                this.form.getDisplayName(), this.path, target.x, target.y, target.z);
        }

        return false;
    }

    /**
     * The velocity blend every rigid body here uses, with the safe axis-angle math from the NaN
     * hunt (`9e8337a`): the delta of two nearly identical rotations can round its w a hair above 1,
     * and JOML's conversion walks a NaN straight into the solver.
     */
    private void drive(BodyInterface bodies, int i, float authority)
    {
        int id = this.bodies[i];

        bodies.getPositionAndRotation(id, this.currentPosition, this.currentRotation);

        Vec3 velocity = bodies.getLinearVelocity(id);
        Vec3 spin = bodies.getAngularVelocity(id);

        this.linear.set(
            mix(velocity.getX(), (float) (this.scratchPosition.xx() - this.currentPosition.xx()) / PhysicsWorld.TICK, authority),
            mix(velocity.getY(), (float) (this.scratchPosition.yy() - this.currentPosition.yy()) / PhysicsWorld.TICK, authority),
            mix(velocity.getZ(), (float) (this.scratchPosition.zz() - this.currentPosition.zz()) / PhysicsWorld.TICK, authority));

        this.targetRotation.set(this.formRotation).normalize();
        this.delta.set(this.currentRotation.getX(), this.currentRotation.getY(), this.currentRotation.getZ(), this.currentRotation.getW()).conjugate();
        this.targetRotation.mul(this.delta, this.delta);

        if (this.delta.w < 0F)
        {
            this.delta.set(-this.delta.x, -this.delta.y, -this.delta.z, -this.delta.w);
        }

        float w = Math.min(this.delta.w, 1F);
        float sinHalfSquared = 1F - w * w;
        float speed = 0F;
        float axisX = 0F;
        float axisY = 0F;
        float axisZ = 0F;

        if (sinHalfSquared > 1e-12F)
        {
            float invSinHalf = (float) (1D / Math.sqrt(sinHalfSquared));

            speed = 2F * (float) Math.acos(w) / PhysicsWorld.TICK;
            axisX = this.delta.x * invSinHalf;
            axisY = this.delta.y * invSinHalf;
            axisZ = this.delta.z * invSinHalf;
        }

        this.angular.set(
            mix(spin.getX(), axisX * speed, authority),
            mix(spin.getY(), axisY * speed, authority),
            mix(spin.getZ(), axisZ * speed, authority));

        if (!finite(this.linear) || !finite(this.angular))
        {
            if (!this.misfed)
            {
                this.misfed = true;

                BBSPhysics.LOGGER.warn(
                    "The drive for segment {} of the chain '{}' came out unusable — linear ({}, {}, {}), angular ({}, {}, {}) — so the segment falls free instead.",
                    i, this.form.getDisplayName(),
                    this.linear.getX(), this.linear.getY(), this.linear.getZ(),
                    this.angular.getX(), this.angular.getY(), this.angular.getZ());
            }

            return;
        }

        bodies.setLinearAndAngularVelocity(id, this.linear, this.angular);
        bodies.activateBody(id);
    }

    /**
     * Ties, moves or unties the bottom end for this tick. The state machine's whole job is the
     * moment of switching on: the tie's anchor points are set where they are needed <em>then</em>,
     * off the current tip and target, so re-recording lands them identically tick for tick.
     */
    private void applyAttach(PhysicsWorld physics, FilmScene scene, Attach attach)
    {
        BodyInterface bodies = physics.getBodies();

        int mode = attach.mode();

        /* A fade that has died out is a free end, whatever the key says. */
        if (mode != ATTACH_NONE && attach.weight() <= 0F)
        {
            mode = ATTACH_NONE;
        }

        int body = mode == ATTACH_BODY ? attach.bodyId() : -1;

        if (mode == ATTACH_BODY && !this.targetJoints.containsKey(body))
        {
            /* The anchor names an actor with no simulated body — resolved as a pin by the scene,
             * so reaching here means the target body was not built. A free end is the honest
             * answer. */
            mode = ATTACH_NONE;
        }

        /* Leaving a state: switch the old tie off. */
        if (this.attachMode == ATTACH_PIN && mode != ATTACH_PIN)
        {
            this.pinJoint.setEnabled(false);
        }

        if (this.attachMode == ATTACH_BODY && (mode != ATTACH_BODY || body != this.attachBody))
        {
            PointConstraint old = this.targetJoints.get(this.attachBody);

            if (old != null)
            {
                old.setEnabled(false);
            }
        }

        if (mode == ATTACH_NONE)
        {
            this.attachMode = ATTACH_NONE;
            this.attachBody = -1;

            return;
        }

        /* Where the strand's tip end actually is — the fade's near end, and the tie-on point. */
        this.tipEnd(bodies, this.tip);

        if (mode == ATTACH_PIN)
        {
            float weight = Math.min(attach.weight(), 1F);

            float x = this.tip.x + (attach.x() - this.tip.x) * weight;
            float y = this.tip.y + (attach.y() - this.tip.y) * weight;
            float z = this.tip.z + (attach.z() - this.tip.z) * weight;

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            {
                return;
            }

            this.scratchPosition.set(x, y, z);

            if (this.attachMode != ATTACH_PIN)
            {
                /* Switching on: stand the pin where the tie begins — at a fading key that is the
                 * strand's own tip, so the first tick pulls by nothing at all — and switch the
                 * constraint on. Its anchors are local and fixed: tip end onto pin centre. */
                bodies.setPositionAndRotation(this.pinId, this.scratchPosition, Quat.sIdentity(), EActivation.Activate);
                bodies.setLinearAndAngularVelocity(this.pinId, ZERO, ZERO);

                this.pinJoint.setEnabled(true);
            }
            else
            {
                bodies.moveKinematic(this.pinId, this.scratchPosition, Quat.sIdentity(), PhysicsWorld.TICK);
            }

            bodies.activateBody(this.bodies[this.segments - 1]);

            this.attachMode = ATTACH_PIN;
            this.attachBody = -1;

            return;
        }

        /* A simulated body: the tie grabs it by its middle — the constraint's anchors were fixed
         * at build — and from then on the constraint does all the work: the rope pulls the crate,
         * the crate swings the rope. */
        if (this.attachMode != ATTACH_BODY || body != this.attachBody)
        {
            this.targetJoints.get(body).setEnabled(true);

            bodies.activateBody(this.bodies[this.segments - 1]);
            bodies.activateBody(body);
        }

        this.attachMode = ATTACH_BODY;
        this.attachBody = body;
    }

    /** The strand's very tip — the free end of the last segment — in scene coordinates. */
    private void tipEnd(BodyInterface bodies, Vector3f out)
    {
        bodies.getPositionAndRotation(this.bodies[this.segments - 1], this.currentPosition, this.currentRotation);

        this.currentQuat.set(this.currentRotation.getX(), this.currentRotation.getY(), this.currentRotation.getZ(), this.currentRotation.getW());

        if (this.currentQuat.lengthSquared() < 1e-6F)
        {
            this.currentQuat.identity();
        }

        out.set(0F, -this.segmentLength / 2F, 0F);
        this.currentQuat.transform(out);
        out.add((float) this.currentPosition.xx(), (float) this.currentPosition.yy(), (float) this.currentPosition.zz());
    }

    /**
     * An impulse clip's push (Э5), segment by segment. Held strands take nothing — the segments
     * are kinematic then, and physics has no business kicking keyframes.
     */
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        if (PhysicsForms.getAuthority(this.form) >= 1F)
        {
            return;
        }

        BodyInterface bodies = physics.getBodies();
        boolean pushed = false;

        for (int id : this.bodies)
        {
            bodies.getPositionAndRotation(id, this.currentPosition, this.currentRotation);

            if (!push.velocityAt((float) this.currentPosition.xx(), (float) this.currentPosition.yy(), (float) this.currentPosition.zz(), this.point))
            {
                continue;
            }

            Vec3 velocity = bodies.getLinearVelocity(id);

            this.linear.set(velocity.getX() + this.point.x, velocity.getY() + this.point.y, velocity.getZ() + this.point.z);

            if (finite(this.linear))
            {
                bodies.setLinearVelocity(id, this.linear);
                bodies.activateBody(id);

                pushed = true;
            }
        }

        if (pushed)
        {
            bodies.activateBody(this.bodies[0]);
        }
    }

    /** Pushes the settings that can change on a live body — the author edits them with the film open. */
    private void applySettings(PhysicsWorld physics)
    {
        BodyInterface bodies = physics.getBodies();

        float friction = this.form.friction.get();

        if (friction != this.lastFriction)
        {
            for (int id : this.bodies)
            {
                bodies.setFriction(id, friction);
            }

            this.lastFriction = friction;
        }

        float gravity = this.form.gravity.get();

        if (gravity != this.lastGravity)
        {
            for (int id : this.bodies)
            {
                bodies.setGravityFactor(id, gravity);
            }

            this.lastGravity = gravity;
        }

        float stiffness = this.form.stiffness.get();
        float damping = this.form.damping.get();

        if (stiffness != this.lastStiffness || damping != this.lastDamping)
        {
            for (SwingTwistConstraint joint : this.joints)
            {
                tune(joint, stiffness, damping);
            }

            if (this.rootJoint != null)
            {
                tune(this.rootJoint, stiffness, damping);
            }

            this.lastStiffness = stiffness;
            this.lastDamping = damping;
        }
    }

    /**
     * Runs right after the world stepped: reads every segment, expresses it in the form's own
     * frame, and writes the strand into the recording under {@code tick} — the same bargain as
     * everywhere (§6): playback evaluates no poses.
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        BodyInterface bodies = physics.getBodies();

        this.formWorldInverse.set(this.formWorld).invert();

        Quaternionf inverseRotation = rotationOf(this.formWorld).conjugate();

        float authority = PhysicsForms.getAuthority(this.form);

        boolean sound = true;
        double sumX = 0D;
        double sumY = 0D;
        double sumZ = 0D;

        for (int i = 0; i < this.segments; i++)
        {
            bodies.getPositionAndRotation(this.bodies[i], this.currentPosition, this.currentRotation);

            float x = (float) this.currentPosition.xx();
            float y = (float) this.currentPosition.yy();
            float z = (float) this.currentPosition.zz();
            float qx = this.currentRotation.getX();
            float qy = this.currentRotation.getY();
            float qz = this.currentRotation.getZ();
            float qw = this.currentRotation.getW();

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(qx) || !Float.isFinite(qy) || !Float.isFinite(qz) || !Float.isFinite(qw))
            {
                sound = false;

                break;
            }

            sumX += x;
            sumY += y;
            sumZ += z;

            /* Scene coordinates → world → the form's own frame. */
            this.point.set(
                (float) (x + scene.getOriginX()),
                (float) (y + scene.getOriginY()),
                (float) (z + scene.getOriginZ()));
            this.formWorldInverse.transformPosition(this.point);

            this.currentQuat.set(qx, qy, qz, qw);
            inverseRotation.mul(this.currentQuat, this.currentQuat);

            cache.write(tick, this.channels[i], this.point, this.currentQuat, authority);
        }

        if (sound)
        {
            this.recordedCenter.set((float) (sumX / this.segments), (float) (sumY / this.segments), (float) (sumZ / this.segments));
            this.centered = true;
        }
        else
        {
            /* A strand the solver lost is recorded as silence, whole: the frame draws the straight
             * strand rather than nothing, and the loss is a count the readout can say out loud. */
            this.point.zero();
            this.currentQuat.identity();

            for (int i = 0; i < this.segments; i++)
            {
                cache.write(tick, this.channels[i], this.point, this.currentQuat, PhysicsCache.SILENT);
            }
        }

        this.lost = !sound;
    }

    /** Whether the simulation lost this strand on the tick it last recorded. */
    public boolean isLost()
    {
        return this.lost;
    }

    /** The strand's centre on the last recorded tick, in scene coordinates; false until one exists. */
    public boolean getRecordedCenter(Vector3f out)
    {
        if (this.centered)
        {
            out.set(this.recordedCenter);
        }

        return this.centered;
    }

    /**
     * Hands the form the recorded strand for the frame being drawn, or the news that there is none
     * — in which case the renderer draws the straight strand (Р8.1).
     */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        ChainState state = this.form.state;

        if (state == null)
        {
            return;
        }

        state.roll();

        for (int i = 0; i < this.segments; i++)
        {
            if (!cache.read(tick, this.channels[i], this.point, this.currentQuat) || cache.readAuthority(tick, this.channels[i]) == PhysicsCache.SILENT)
            {
                state.setUnsimulated();

                return;
            }

            state.stage(i, this.point, this.currentQuat);
        }

        state.push(teleport);
    }

    /** The chain form this rig simulates — what the scene resolves the anchor track off. */
    public ChainForm getForm()
    {
        return this.form;
    }

    /**
     * Lets go of the form, so it draws its straight strand again. Called when the scene is closed:
     * the bodies behind this rig are about to stop existing.
     */
    public void release()
    {
        this.form.state = null;
    }

    /** The rotation of a frame that may carry scale — the ragdoll's proven extraction. */
    private static Quaternionf rotationOf(Matrix4f matrix)
    {
        Quaternionf rotation = matrix.getUnnormalizedRotation(new Quaternionf());

        if (!Float.isFinite(rotation.x) || !Float.isFinite(rotation.y) || !Float.isFinite(rotation.z) || !Float.isFinite(rotation.w)
            || rotation.lengthSquared() < 1e-12F)
        {
            return rotation.identity();
        }

        return rotation.normalize();
    }

    /** Any unit vector perpendicular to {@code axis} — the joint's plane axis. */
    private static Vector3f perpendicular(Vector3f axis)
    {
        Vector3f other = Math.abs(axis.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);
        Vector3f plane = new Vector3f();

        axis.cross(other, plane);

        if (plane.lengthSquared() < 1e-12F)
        {
            return new Vector3f(1F, 0F, 0F);
        }

        return plane.normalize();
    }

    private static boolean finite(Vec3 velocity)
    {
        return Float.isFinite(velocity.getX()) && Float.isFinite(velocity.getY()) && Float.isFinite(velocity.getZ());
    }

    private static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }
}
