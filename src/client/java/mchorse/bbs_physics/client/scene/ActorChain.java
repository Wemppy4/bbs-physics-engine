package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.MotorSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.SwingTwistConstraint;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.enumerate.EMotorState;
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.chain.FormChain;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.CollisionShapes;
import mchorse.bbs_physics.client.collision.JoltShapes;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One model's hanging strands: the bones an author ticked into the chain modifier, each as a rigid
 * capsule on a springy cone joint, hanging off whatever the bone tree says they hang off.
 *
 * <p><b>The same machinery as the chain form, aimed at bones instead of at segments of its own</b>
 * (Э8's second half). A strand is worked out from the bone tree rather than stored: a claimed bone
 * whose parent is not claimed starts one, and it runs down through claimed children, so two braids
 * off one root are simply two strands sharing an anchor. What holds the top is, in order of
 * preference: the kinematic body of the parent bone when the author marked that bone up for
 * collision, and otherwise a kinematic pin of our own driven along the parent bone's frame. The
 * second case is the common one — nobody marks up a scalp — and it is what makes "add the modifier,
 * tick the hair, it swings" true without any collision setup at all.</p>
 *
 * <p><b>The pose comes back exactly the way the ragdoll's does</b>: recorded in the model's flipped
 * group space and substituted into {@code ModelGroup.orient}/{@code offset} between IK and the old
 * chain solver, through the very same applier. Which is also why the old solver has to be silenced
 * on these bones — see {@code ChainMute}: two owners for one strand is one strand doing neither
 * thing convincingly.</p>
 *
 * <p><b>What the strands collide with is the actor's own markup</b> (Вемпи's call): the segments sit
 * in the props layer, so they land on the marked bones of the character they belong to — mark the
 * body up and the hair rests on the shoulders. The one pair that must not exist is a strand's first
 * segment against the bone it hangs from: those shapes overlap by construction at the anchor, and
 * a kinematic bone cannot give way, so the depenetration would fire the hair off the head.</p>
 */
public class ActorChain
{
    /** Shared, never written to — the velocity a placed body is stopped with. */
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    /** How far a joint lets a strand bend — wide, because the strand's shape is the spring's job. */
    private static final float CONE_DEGREES = 80F;
    private static final float TWIST_DEGREES = 45F;

    /** Spin bleeds off faster than travel, the same tuning every rigid body here carries. */
    private static final float ANGULAR_DAMPING = 0.4F;

    /**
     * How the stiffness knob maps to the joint spring, in Hz. No joint friction anywhere, for the
     * reason the ChainSmoke stand established: friction stalls a weak spring short of its target,
     * and a strand of eight joints ends up permanently bent by the sum of those little errors.
     */
    private static final float SPRING_TOP_HZ = 12F;

    /** The shortest a bone may be measured as, so a leaf with no child still gets a body. */
    private static final float MIN_LENGTH = 0.05F;

    /**
     * The thickness of the invisible stand-in an unshaped bone is weighed by. Never collides with
     * anything — it exists so that Jolt has a shape to take inertia from, and a thin rod's inertia
     * is what makes a strand swing like a strand rather than spin like a needle.
     */
    private static final float INERTIA_RADIUS = 0.02F;

    private final ModelForm form;
    private final String formPath;
    private final RagdollState state = new RagdollState();

    private final List<Segment> segments = new ArrayList<>();
    private final List<SwingTwistConstraint> joints = new ArrayList<>();

    /** The pins this rig drives itself: one per strand whose anchor bone has no body of its own. */
    private final List<Pin> pins = new ArrayList<>();

    /* The frame the answer is expressed against — the model's flipped group space, per tick. */
    private final Matrix4f base = new Matrix4f();
    private final Matrix4f baseInverse = new Matrix4f();
    private boolean baseValid;

    /* Scratch: all of this runs per segment per tick. */
    private final Matrix4f worldMatrix = new Matrix4f();
    private final Matrix4f poseFrame = new Matrix4f();
    private final Vector3f translation = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();
    private final RVec3 currentPosition = new RVec3();
    private final Quat currentRotation = new Quat();
    private final Quaternionf target = new Quaternionf();
    private final Quaternionf delta = new Quaternionf();
    private final Vec3 linear = new Vec3();
    private final Vec3 angular = new Vec3();

    private boolean kinematic;
    private boolean recorded;
    private boolean lost;
    private boolean misfed;

    private float lastStiffness = Float.NaN;
    private float lastDamping = Float.NaN;
    private float lastGravity = Float.NaN;

    private ActorChain(ModelForm form, String formPath)
    {
        this.form = form;
        this.formPath = formPath;
    }

    /**
     * Builds the strands of one chain-enabled model form, or returns null when nothing could be
     * built — a model that has not loaded, or a modifier with no bones ticked yet.
     *
     * @param rig   the actor's kinematic bones, for hanging a strand off a marked-up bone; may be null
     * @param group the actor's collision group, shared with its bones and ragdolls
     */
    public static ActorChain build(PhysicsWorld physics, ModelForm form, String formPath, List<CollisionCollector.Piece> claimed, ActorRig rig, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group)
    {
        FormChain config = FormChains.get(form);

        if (!config.enabled() || config.bones().isEmpty())
        {
            return null;
        }

        /* What each claimed bone collides as, by the bone's name — straight from the Collision tab
         * and nowhere else. A bone the author has not shaped is absent here and gets no collision. */
        Map<String, CollisionCollector.Piece> marked = new HashMap<>();

        for (CollisionCollector.Piece piece : claimed)
        {
            marked.put(piece.label(), piece);
        }

        ModelInstance instance = ModelFormRenderer.getModel(form);

        if (instance == null)
        {
            BBSPhysics.LOGGER.info("A chain modifier is on '{}', whose model has not finished loading; the scene will be built again when it has.", form.getDisplayName());

            return null;
        }

        if (!(instance.model instanceof Model model))
        {
            BBSPhysics.LOGGER.warn("A chain modifier is on '{}', but only cubic models can hand the simulated pose back to the renderer; its bones stay animated.", form.getDisplayName());

            return null;
        }

        Map<String, ModelGroup> groups = new HashMap<>();

        for (ModelGroup modelGroup : model.getAllGroups())
        {
            groups.put(modelGroup.id, modelGroup);
        }

        ActorChain chain = new ActorChain(form, formPath);
        BodyInterface bodies = physics.getBodies();
        Map<String, Segment> byBone = new HashMap<>();

        /* The bodies first, every claimed bone that the model actually has and the pose can place. */
        for (String bone : config.bones())
        {
            ModelGroup boneGroup = groups.get(bone);
            String path = StringUtils.combinePaths(formPath, bone);
            MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);

            if (boneGroup == null || entry == null || entry.matrix() == null)
            {
                continue;
            }

            /* 🔴 The strand describes no shapes of its own — the Collision tab does, exactly as it
             * does for the ragdoll. The modifier used to build a capsule from a "thickness" knob,
             * and an author's first live run said why that is wrong: the capsule was thicker than
             * the hair it stood for, so the strands floated off the shoulders they were supposed to
             * lie on, and colliders appeared on a model nobody had marked up at all (against Р6).
             *
             * A claimed bone the author has not shaped still hangs and swings — it simply meets
             * nothing until it is given a shape, which is the same bargain a rigid body without a
             * collider gets (§5.1). Its invisible stand-in exists only so Jolt can weigh the bone:
             * a body has to have some shape, and inertia taken from a speck would make the strand
             * spin like a compass needle. */
            CollisionCollector.Piece piece = marked.get(bone);
            List<CollisionShapes.SubShape> shapes = piece == null ? null : piece.shapes();
            boolean collides = shapes != null && !shapes.isEmpty();

            Vector3f towards = childDirection(boneGroup, formPath, matrices, entry);
            float length = Math.max(towards.length(), MIN_LENGTH);

            towards.normalize();

            ConstShape shape = collides ? JoltShapes.build(shapes) : JoltShapes.leaf(new CollisionShapes.SubShape(
                CollisionKind.CAPSULE,
                new Vector3f(INERTIA_RADIUS, Math.max(length / 2F - INERTIA_RADIUS, 0.005F), INERTIA_RADIUS),
                new Vector3f(towards).mul(length / 2F),
                new Quaternionf().rotationTo(new Vector3f(0F, 1F, 0F), towards)));

            if (shape == null)
            {
                continue;
            }

            chain.worldMatrix.set(actorWorld).mul(entry.matrix());
            chain.worldMatrix.getTranslation(chain.translation);
            chain.worldMatrix.getUnnormalizedRotation(chain.orientation);

            boolean kinematic = PhysicsForms.isKinematic(form);

            BodyCreationSettings settings = new BodyCreationSettings(
                shape,
                new RVec3(
                    chain.translation.x - scene.getOriginX(),
                    chain.translation.y - scene.getOriginY(),
                    chain.translation.z - scene.getOriginZ()),
                new Quat(chain.orientation.x, chain.orientation.y, chain.orientation.z, chain.orientation.w),
                kinematic ? EMotionType.Kinematic : EMotionType.Dynamic,
                layer(collides, kinematic));

            settings.setFriction(0.4F);
            settings.setRestitution(0.05F);
            settings.setGravityFactor(config.gravity());
            settings.setAngularDamping(ANGULAR_DAMPING);
            settings.setLinearDamping(0.05F + 0.45F * config.damping());
            settings.setMotionQuality(EMotionQuality.LinearCast);

            /* The author gives the strand's weight; Jolt would weigh a thin capsule by volume and
             * make hair weightless. Inertia still comes from the shape, scaled to the mass. */
            settings.setMassPropertiesOverride(new MassProperties().setMass(Math.max(config.mass(), 0.01F) / config.bones().size()));
            settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

            int sub = group.claimChain();

            settings.setCollisionGroup(group.of(sub));

            Body body = bodies.createBody(settings);

            bodies.addBody(body.getId(), EActivation.Activate);

            Segment segment = new Segment(bone, path, body.getId(), body, sub, scene.addChannel(), collides);

            chain.segments.add(segment);
            byBone.put(bone, segment);

            /* Only what really collides is drawn: the invisible stand-in of an unshaped bone is not
             * a collider, and drawing it would be the overlay claiming a shape the author never
             * described — which is how the thickness capsules read in the first place. */
            if (collides)
            {
                SceneBody debug = new SceneBody(body.getId(), 0.4F, 1F, 0.75F);

                debug.addShapes(shapes);
                scene.addDebugBody(debug);
            }
        }

        if (chain.segments.isEmpty())
        {
            return null;
        }

        chain.kinematic = PhysicsForms.isKinematic(form);

        /* 🔴 The "strands collide with each other" switch, which until now was stored and never
         * read — so strands always collided, whatever it said. Off means every pair of this
         * modifier's segments is excused: not only the far ones, but the segments of one strand
         * against each other, which is what a strand curling onto itself is. On, only neighbours
         * are excused (below, where the joints are made), because those overlap by construction.
         *
         * Per modifier rather than per actor: the switch sits on this form and describes its own
         * hair — two models on one character each answer for their own. */
        if (!config.selfCollision())
        {
            for (int i = 0; i < chain.segments.size(); i++)
            {
                for (int j = i + 1; j < chain.segments.size(); j++)
                {
                    group.excuse(chain.segments.get(i).sub, chain.segments.get(j).sub);
                }
            }
        }

        /* Then the joints: each segment onto its parent bone — a fellow segment, the parent's
         * kinematic body, or a pin of our own following that bone. */
        for (Segment segment : chain.segments)
        {
            ModelGroup boneGroup = groups.get(segment.bone);
            ModelGroup parentGroup = boneGroup == null ? null : boneGroup.parent;
            Segment parent = parentGroup == null ? null : byBone.get(parentGroup.id);

            Body anchor;
            int anchorSub;

            if (parent != null)
            {
                anchor = parent.body;
                anchorSub = parent.sub;
            }
            else
            {
                Anchor made = chain.anchorFor(physics, parentGroup, formPath, rig, matrices, actorWorld, scene, group);

                if (made == null)
                {
                    /* Nothing to hang this strand from — a claimed bone whose parent the pose
                     * cannot place. Left as a free body would look like hair falling off, so it is
                     * simply not jointed and stays wherever the drive puts it. */
                    continue;
                }

                anchor = made.body();
                anchorSub = made.sub();
            }

            chain.joints.add(chain.joint(physics, scene, anchor, segment, matrices, actorWorld, config));

            /* Neighbours meet at the joint by construction, and the anchor bone especially: it
             * cannot give way, so the overlap would shove the strand out of the head every step. */
            group.excuse(segment.sub, anchorSub);
        }

        FormChains.setState(form, chain.state);

        return chain;
    }

    /**
     * Where this bone's capsule points, in the bone's own frame: towards its first child's pivot.
     * A leaf — the last bone of a strand — has nothing to point at, so it continues straight down
     * the bone's own axis, which is where a strand of hair goes anyway.
     */
    private static Vector3f childDirection(ModelGroup group, String formPath, MatrixCache matrices, MatrixCacheEntry entry)
    {
        Matrix4f inverse = new Matrix4f(entry.matrix()).invert();

        for (ModelGroup child : group.children)
        {
            MatrixCacheEntry childEntry = matrices.get(StringUtils.combinePaths(formPath, child.id));

            if (childEntry == null || childEntry.matrix() == null)
            {
                continue;
            }

            Vector3f local = new Matrix4f(inverse).mul(childEntry.matrix()).getTranslation(new Vector3f());

            if (local.lengthSquared() > 1.0e-8F)
            {
                return local;
            }
        }

        return new Vector3f(0F, -0.15F, 0F);
    }

    /**
     * What a strand's top hangs from when the bone above it is not itself a strand: the marked-up
     * bone's kinematic body if the author gave it one, and otherwise a kinematic pin built here and
     * driven along that bone every tick.
     *
     * <p>The pin is what makes the modifier work with no collision setup at all. A scalp is not a
     * thing anyone marks up, so without it the common case — tick the hair, nothing else — would
     * leave every strand hanging from nothing.</p>
     */
    private Anchor anchorFor(PhysicsWorld physics, ModelGroup parentGroup, String formPath, ActorRig rig, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group)
    {
        if (parentGroup == null)
        {
            return null;
        }

        String path = StringUtils.combinePaths(formPath, parentGroup.id);
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        if (rig != null)
        {
            ActorRig.Part part = rig.find(path);

            if (part != null)
            {
                return new Anchor(part.body(), part.sub());
            }
        }

        for (Pin pin : this.pins)
        {
            if (pin.path.equals(path))
            {
                return new Anchor(pin.body, pin.sub);
            }
        }

        this.worldMatrix.set(actorWorld).mul(entry.matrix());
        this.worldMatrix.getTranslation(this.translation);
        this.worldMatrix.getUnnormalizedRotation(this.orientation);

        int sub = group.claimChain();

        /* GHOST: it holds the strand through the joint and must meet nothing itself — an invisible
         * handle, not a collider the author never described. */
        BodyCreationSettings settings = new BodyCreationSettings(
            new SphereShape(0.02F),
            new RVec3(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ()),
            new Quat(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w),
            EMotionType.Kinematic,
            PhysicsLayers.GHOST);

        settings.setCollisionGroup(group.of(sub));

        Body body = physics.getBodies().createBody(settings);

        physics.getBodies().addBody(body.getId(), EActivation.Activate);

        this.pins.add(new Pin(path, body.getId(), body, sub));

        return new Anchor(body, sub);
    }

    /** One cone joint at a bone's pivot, with the stiffness spring on its motors. */
    private SwingTwistConstraint joint(PhysicsWorld physics, FilmScene scene, Body anchor, Segment segment, MatrixCache matrices, Matrix4f actorWorld, FormChain config)
    {
        MatrixCacheEntry entry = matrices.get(segment.path);

        this.worldMatrix.set(actorWorld).mul(entry.matrix());
        this.worldMatrix.getTranslation(this.translation);
        this.worldMatrix.getUnnormalizedRotation(this.orientation);

        RVec3 point = new RVec3(
            this.translation.x - scene.getOriginX(),
            this.translation.y - scene.getOriginY(),
            this.translation.z - scene.getOriginZ());

        /* The cone leans around the strand's own direction at rest — the bone's local down carried
         * into the world, so a strand keeps whatever way the author combed it. */
        Vector3f axis = this.orientation.transform(new Vector3f(0F, -1F, 0F)).normalize();
        Vector3f plane = perpendicular(axis);

        SwingTwistConstraintSettings settings = new SwingTwistConstraintSettings();

        settings.setPosition1(point);
        settings.setPosition2(point);
        settings.setTwistAxis1(new Vec3(axis.x, axis.y, axis.z));
        settings.setTwistAxis2(new Vec3(axis.x, axis.y, axis.z));
        settings.setPlaneAxis1(new Vec3(plane.x, plane.y, plane.z));
        settings.setPlaneAxis2(new Vec3(plane.x, plane.y, plane.z));
        settings.setNormalHalfConeAngle((float) Math.toRadians(CONE_DEGREES));
        settings.setPlaneHalfConeAngle((float) Math.toRadians(CONE_DEGREES));
        settings.setTwistMinAngle((float) Math.toRadians(-TWIST_DEGREES));
        settings.setTwistMaxAngle((float) Math.toRadians(TWIST_DEGREES));

        SwingTwistConstraint constraint = (SwingTwistConstraint) settings.create(anchor, segment.body);

        physics.getSystem().addConstraint(constraint);

        tune(constraint, config.stiffness(), config.damping());

        return constraint;
    }

    /**
     * Sets one joint's motors to the stiffness and damping knobs. Position mode with a spring is
     * "return to the shape you were built in" — the hairstyle — and Off is a strand that hangs
     * wherever physics takes it.
     */
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
     * Runs before the world steps: keeps the pins on the bones they follow, the segments' motion
     * type in step with the handle, and drives them — kinematically at 1, by the velocity blend
     * below it.
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean reset)
    {
        this.captureBase(matrices, actorWorld);
        this.applySettings(physics);

        BodyInterface bodies = physics.getBodies();

        float authority = PhysicsForms.getAuthority(this.form);
        boolean wanted = authority >= 1F;
        boolean put = reset;

        /* The pins ride the animation whatever the handle says — they are the head the hair hangs
         * from, not part of the hair. */
        for (Pin pin : this.pins)
        {
            MatrixCacheEntry entry = matrices == null ? null : matrices.get(pin.path);

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            this.worldMatrix.set(actorWorld).mul(entry.matrix());
            this.worldMatrix.getTranslation(this.translation);
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.scratchPosition.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.scratchRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (reset)
            {
                bodies.setPositionAndRotation(pin.id, this.scratchPosition, this.scratchRotation, EActivation.Activate);
                bodies.setLinearAndAngularVelocity(pin.id, ZERO, ZERO);
            }
            else
            {
                bodies.moveKinematic(pin.id, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
            }
        }

        if (wanted != this.kinematic)
        {
            for (Segment segment : this.segments)
            {
                bodies.setMotionType(segment.id, wanted ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);
                bodies.setObjectLayer(segment.id, layer(segment.collides, wanted));
            }

            this.kinematic = wanted;

            /* Taken back by the animation after hanging free: nowhere near its keyframes, and
             * steered there over one tick it would rake the whole set on the way. */
            put |= wanted;
        }

        for (Segment segment : this.segments)
        {
            MatrixCacheEntry entry = matrices == null ? null : matrices.get(segment.path);

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            this.worldMatrix.set(actorWorld).mul(entry.matrix());
            this.worldMatrix.getTranslation(this.translation);
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.scratchPosition.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.scratchRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (put)
            {
                bodies.setPositionAndRotation(segment.id, this.scratchPosition, this.scratchRotation, EActivation.Activate);
                bodies.setLinearAndAngularVelocity(segment.id, ZERO, ZERO);
            }
            else if (this.kinematic)
            {
                bodies.moveKinematic(segment.id, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
            }
            else if (authority > 0F)
            {
                this.drive(bodies, segment, authority);
            }
        }
    }

    /**
     * An impulse clip's push (Э5). A strand the animation owns outright takes nothing, the same
     * rule every other simulated thing follows.
     */
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        if (PhysicsForms.getAuthority(this.form) >= 1F)
        {
            return;
        }

        BodyInterface bodies = physics.getBodies();

        for (Segment segment : this.segments)
        {
            bodies.getPositionAndRotation(segment.id, this.currentPosition, this.currentRotation);

            if (!push.velocityAt((float) this.currentPosition.xx(), (float) this.currentPosition.yy(), (float) this.currentPosition.zz(), this.translation))
            {
                continue;
            }

            Vec3 velocity = bodies.getLinearVelocity(segment.id);

            this.linear.set(
                velocity.getX() + this.translation.x,
                velocity.getY() + this.translation.y,
                velocity.getZ() + this.translation.z);

            if (finite(this.linear))
            {
                bodies.setLinearVelocity(segment.id, this.linear);
                bodies.activateBody(segment.id);
            }
        }
    }

    /** Pushes the knobs that can change on a live strand — an author edits them with the film open. */
    private void applySettings(PhysicsWorld physics)
    {
        FormChain config = FormChains.get(this.form);

        float gravity = config.gravity();

        if (gravity != this.lastGravity)
        {
            for (Segment segment : this.segments)
            {
                physics.getBodies().setGravityFactor(segment.id, gravity);
            }

            this.lastGravity = gravity;
        }

        float stiffness = config.stiffness();
        float damping = config.damping();

        if (stiffness != this.lastStiffness || damping != this.lastDamping)
        {
            for (SwingTwistConstraint joint : this.joints)
            {
                tune(joint, stiffness, damping);
            }

            this.lastStiffness = stiffness;
            this.lastDamping = damping;
        }
    }

    /**
     * The velocity blend, with the safe axis-angle maths from the NaN hunt (`9e8337a`): the delta of
     * two nearly identical rotations can round its w a hair above 1, and JOML's conversion walks a
     * NaN straight into the solver.
     */
    private void drive(BodyInterface bodies, Segment segment, float authority)
    {
        bodies.getPositionAndRotation(segment.id, this.currentPosition, this.currentRotation);

        Vec3 velocity = bodies.getLinearVelocity(segment.id);
        Vec3 spin = bodies.getAngularVelocity(segment.id);

        this.linear.set(
            mix(velocity.getX(), (float) (this.scratchPosition.xx() - this.currentPosition.xx()) / PhysicsWorld.TICK, authority),
            mix(velocity.getY(), (float) (this.scratchPosition.yy() - this.currentPosition.yy()) / PhysicsWorld.TICK, authority),
            mix(velocity.getZ(), (float) (this.scratchPosition.zz() - this.currentPosition.zz()) / PhysicsWorld.TICK, authority));

        this.target.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW()).normalize();
        this.delta.set(this.currentRotation.getX(), this.currentRotation.getY(), this.currentRotation.getZ(), this.currentRotation.getW()).conjugate();
        this.target.mul(this.delta, this.delta);

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
                    "The drive for the chain bone '{}' of '{}' came out unusable, so the strand is left to itself. The pose it is pulled towards is broken.",
                    segment.bone, this.form.getDisplayName());
            }

            return;
        }

        bodies.setLinearAndAngularVelocity(segment.id, this.linear, this.angular);
        bodies.activateBody(segment.id);
    }

    /**
     * Runs right after the world stepped: reads every strand bone, expresses it in the model's own
     * group space and writes it into the recording — the ragdoll's conversion, step for step (§10.1),
     * because the renderer substitutes both through the same applier.
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        float authority = PhysicsForms.getAuthority(this.form);

        if (!this.baseValid)
        {
            this.translation.zero();
            this.orientation.identity();

            for (Segment segment : this.segments)
            {
                cache.write(tick, segment.channel, this.translation, this.orientation, PhysicsCache.SILENT);
            }

            return;
        }

        BodyInterface bodies = physics.getBodies();

        for (Segment segment : this.segments)
        {
            bodies.getPositionAndRotation(segment.id, this.scratchPosition, this.scratchRotation);

            if (!finite(this.scratchPosition.xx()) || !finite(this.scratchPosition.yy()) || !finite(this.scratchPosition.zz())
                || !finite(this.scratchRotation.getX()) || !finite(this.scratchRotation.getY())
                || !finite(this.scratchRotation.getZ()) || !finite(this.scratchRotation.getW()))
            {
                if (!this.lost)
                {
                    this.lost = true;

                    BBSPhysics.LOGGER.warn("The chain bone '{}' of '{}' left the world at tick {}; it is drawn from its keyframes from here on.", segment.bone, this.form.getDisplayName(), tick);
                }

                this.translation.zero();
                this.orientation.identity();

                cache.write(tick, segment.channel, this.translation, this.orientation, PhysicsCache.SILENT);

                continue;
            }

            this.orientation.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW());
            this.poseFrame.translationRotate(
                (float) (this.scratchPosition.xx() + scene.getOriginX()),
                (float) (this.scratchPosition.yy() + scene.getOriginY()),
                (float) (this.scratchPosition.zz() + scene.getOriginZ()),
                this.orientation);

            /* The cubic flip back off the right, the actor and the form off the left — what is left
             * is the bone's pivot frame in the model's flipped group space (§10.1). */
            this.poseFrame.rotateY(MathUtils.PI);
            this.baseInverse.mul(this.poseFrame, this.poseFrame);

            this.poseFrame.getTranslation(this.translation);
            this.poseFrame.getUnnormalizedRotation(this.orientation);

            cache.write(tick, segment.channel, this.translation, this.orientation, authority);
        }
    }

    /** Hands the renderer the recorded strands for the frame being drawn. */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        boolean jumped = teleport || !this.recorded;
        boolean recorded = false;
        float authority = 1F;

        for (Segment segment : this.segments)
        {
            if (cache.read(tick, segment.channel, this.translation, this.orientation))
            {
                float own = cache.readAuthority(tick, segment.channel);

                this.state.set(segment.bone, this.translation, this.orientation, own, jumped);

                authority = recorded ? Math.min(authority, own) : own;
                recorded = true;
            }
        }

        this.recorded = recorded;

        this.state.setRecorded(recorded);
        this.state.setAuthority(authority, jumped);
    }

    /** Whether the simulation lost a strand bone on the tick it last recorded. */
    public boolean isLost()
    {
        return this.lost;
    }

    /** The frame the answer is expressed against — the ragdoll's, for the same reason. */
    private void captureBase(MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.formPath);

        if (entry == null || entry.matrix() == null)
        {
            this.baseValid = false;

            return;
        }

        this.base.set(actorWorld).mul(entry.matrix()).rotateY(MathUtils.PI);
        this.baseInverse.set(this.base).invert();
        this.baseValid = true;
    }

    /** Lets go of the form: the strands go back to being drawn from their keyframes. */
    public void release()
    {
        FormChains.setState(this.form, null);
    }

    /**
     * Which layer a segment belongs in: the props layer while it is loose and collides, the bone
     * layer while the animation owns it, and the layer that meets nothing when the author has given
     * it no shape — a strand with no markup swings through everything, deliberately.
     */
    private static int layer(boolean collides, boolean kinematic)
    {
        if (!collides)
        {
            return PhysicsLayers.GHOST;
        }

        return kinematic ? PhysicsLayers.BONE : PhysicsLayers.MOVING;
    }

    /** Any unit vector perpendicular to {@code axis} — the joint's plane axis. */
    private static Vector3f perpendicular(Vector3f axis)
    {
        Vector3f helper = Math.abs(axis.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);

        return helper.cross(axis, new Vector3f()).normalize();
    }

    private static boolean finite(Vec3 velocity)
    {
        return Float.isFinite(velocity.getX()) && Float.isFinite(velocity.getY()) && Float.isFinite(velocity.getZ());
    }

    private static boolean finite(double value)
    {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }

    /**
     * One claimed bone as a strand segment. {@code collides} is whether the Collision tab gave it
     * a shape — an unshaped bone hangs in the layer that meets nothing.
     */
    private record Segment(String bone, String path, int id, Body body, int sub, int channel, boolean collides)
    {}

    /** A kinematic handle following a bone that has no body of its own — see {@link #anchorFor}. */
    private record Pin(String path, int id, Body body, int sub)
    {}

    /** What a strand's top was jointed to, and its subgroup — for the collision excuse. */
    private record Anchor(Body body, int sub)
    {}
}
