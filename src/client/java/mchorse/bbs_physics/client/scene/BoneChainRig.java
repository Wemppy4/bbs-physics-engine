package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.MassProperties;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.SwingTwistConstraint;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
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
import mchorse.bbs_physics.engine.BodyDrive;
import mchorse.bbs_physics.engine.KinematicDrive;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsJoints;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsMath;
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
public class BoneChainRig implements SceneRig
{
    /** How far a joint lets a strand bend — wide, because the strand's shape is the spring's job. */
    private static final float TWIST_DEGREES = 45F;

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
    private final List<Joint> joints = new ArrayList<>();

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
    private final Vec3 linear = new Vec3();

    /** The velocity blend that pulls a segment towards its pose — held, because it carries scratch. */
    private final BodyDrive drive = new BodyDrive();

    /** The steer-or-place move of everything kinematic here — held, because it carries scratch. */
    private final KinematicDrive move = new KinematicDrive();

    /**
     * The bone this whole model form hangs on, or null when it rides no bone — the cloth's Р13
     * case, one level up: a hair-carrying model as a body part on a bone another model's ragdoll
     * is dropping.
     */
    private final String formAnchor;

    private boolean kinematic;
    private boolean recorded;
    private boolean lost;
    private boolean misfed;

    private float lastStiffness = Float.NaN;
    private float lastDamping = Float.NaN;
    private float lastGravity = Float.NaN;
    private float lastFalloff = Float.NaN;
    private float lastBend = Float.NaN;

    /** The sub-step count the bones' damping was converted for — part of the rate's arithmetic. */
    private int dampedSteps;

    private BoneChainRig(ModelForm form, String formPath, String formAnchor)
    {
        this.form = form;
        this.formPath = formPath;
        this.formAnchor = formAnchor;
    }

    /**
     * Builds the strands of one chain-enabled model form, or returns null when nothing could be
     * built — a model that has not loaded, or a modifier with no bones ticked yet.
     *
     * @param rig    the actor's kinematic bones, for hanging a strand off a marked-up bone; may be null
     * @param group  the actor's collision group, shared with its bones and ragdolls
     * @param anchor the bone this model form itself hangs on, or null — the Р13 delta's address
     */
    public static BoneChainRig build(PhysicsWorld physics, ModelForm form, String formPath, List<CollisionCollector.Piece> claimed, BoneRig rig, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group, String anchor)
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

        BoneChainRig chain = new BoneChainRig(form, formPath, anchor);
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
            /* Both axes off the one knob, through the scale conversion — a strand's swing is its
             * bones turning about their joints as much as it is them travelling, and damping only
             * the travel leaves the swing to ring on. What the knob means as a rate is spelled out
             * in {@link PhysicsMath#bodyDamping}; read raw, as it was, it bit some thirty times too
             * softly — 0.7% of a strand's speed per tick where the knob said 20% — which is most of
             * why hair never calmed down. */
            settings.setAngularDamping(PhysicsMath.bodyDamping(config.damping(), physics.getCollisionSteps()));
            settings.setLinearDamping(PhysicsMath.bodyDamping(config.damping(), physics.getCollisionSteps()));
            settings.setMotionQuality(EMotionQuality.LinearCast);

            /* The author gives the strand's weight; Jolt would weigh a thin capsule by volume and
             * make hair weightless. Inertia still comes from the shape, scaled to the mass. */
            settings.setMassPropertiesOverride(new MassProperties().setMass(Math.max(config.mass(), 0.01F) / config.bones().size()));
            settings.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia);

            int sub = group.claimChain();

            settings.setCollisionGroup(group.of(sub));

            Body body = bodies.createBody(settings);

            bodies.addBody(body.getId(), EActivation.Activate);

            Segment segment = new Segment(bone, path, body.getId(), body, sub, scene.addChannel(), collides, deltaKeys(boneGroup, formPath));

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

        /* Where every bone sits along its own strand, and how long that strand is — what the
         * spring's falloff towards the tip is measured on. The modifier's bones are a forest and
         * not a list: an author ticks two pigtails and a fringe in one go, and each of those has
         * its own root and its own tip. A bone's depth is how many ticked bones stand above it in
         * an unbroken line; its strand is named by the topmost of them. */
        Map<String, Integer> depths = new HashMap<>();
        Map<String, String> roots = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();

        for (Segment segment : chain.segments)
        {
            ModelGroup above = groups.get(segment.bone);
            String root = segment.bone;
            int depth = 0;

            above = above == null ? null : above.parent;

            while (above != null && byBone.containsKey(above.id))
            {
                root = above.id;
                depth += 1;
                above = above.parent;
            }

            depths.put(segment.bone, depth);
            roots.put(segment.bone, root);
            lengths.merge(root, depth + 1, Math::max);
        }

        /* Then the joints: each segment onto its parent bone — a fellow segment, the parent's
         * kinematic body, or a pin of our own following that bone. */
        for (Segment segment : chain.segments)
        {
            ModelGroup boneGroup = groups.get(segment.bone);
            ModelGroup parentGroup = boneGroup == null ? null : boneGroup.parent;
            Segment parent = parentGroup == null ? null : byBone.get(parentGroup.id);

            Body anchorBody;
            int anchorSub;

            if (parent != null)
            {
                anchorBody = parent.body;
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

                anchorBody = made.body();
                anchorSub = made.sub();
            }

            int index = depths.getOrDefault(segment.bone, 0);
            int count = lengths.getOrDefault(roots.get(segment.bone), index + 1);

            chain.joints.add(new Joint(chain.joint(physics, scene, anchorBody, segment, matrices, actorWorld, config, index, count), index, count));

            /* Neighbours meet at the joint by construction, and the anchor bone especially: it
             * cannot give way, so the overlap would shove the strand out of the head every step. */
            group.excuse(segment.sub, anchorSub);
        }

        FormChains.setState(form, chain.state);

        chain.dampedSteps = physics.getCollisionSteps();

        return chain;
    }

    /**
     * The matrix-cache paths a ragdoll's published deltas could carry this bone by: the bone
     * itself and every ancestor up the model's tree, nearest first. A strand's bone is never a
     * ragdoll part itself (the builder gives every bone one owner), but the bone it grows from —
     * a head, a hip — very much can be, and hair that ignored the head's fall was simulated
     * hanging in the air where the animation had the head while the renderer drew the head on the
     * floor (the cloth's Р13 problem, one rig later).
     */
    private static String[] deltaKeys(ModelGroup group, String formPath)
    {
        List<String> keys = new ArrayList<>(4);

        for (ModelGroup at = group; at != null; at = at.parent)
        {
            keys.add(StringUtils.combinePaths(formPath, at.id));
        }

        return keys.toArray(new String[0]);
    }

    /**
     * The fall to carry a body by: the nearest ragdolled ancestor's published delta, or the model
     * form's own anchor delta when the whole model rides someone else's falling bone, or null when
     * nothing above it is falling.
     */
    private Matrix4f lift(RigUpdate update, String[] keys)
    {
        if (update.deltas.isEmpty())
        {
            return null;
        }

        for (String key : keys)
        {
            Matrix4f delta = update.deltas.get(key);

            if (delta != null)
            {
                return delta;
            }
        }

        return this.formAnchor == null ? null : update.deltas.get(this.formAnchor);
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
    private Anchor anchorFor(PhysicsWorld physics, ModelGroup parentGroup, String formPath, BoneRig rig, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group)
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
            BoneRig.Part part = rig.find(path);

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

        this.pins.add(new Pin(path, body.getId(), body, sub, deltaKeys(parentGroup, formPath)));

        return new Anchor(body, sub);
    }

    /**
     * One cone joint at a bone's pivot, with the stiffness spring on its motors.
     *
     * @param index how far down its strand this bone sits, the strand's top bone being 0 — the
     *              spring softens towards the tip, see {@link PhysicsJoints#tune}
     */
    private SwingTwistConstraint joint(PhysicsWorld physics, FilmScene scene, Body anchor, Segment segment, MatrixCache matrices, Matrix4f actorWorld, FormChain config, int index, int count)
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
        Vector3f plane = PhysicsMath.perpendicular(axis);

        SwingTwistConstraintSettings settings = new SwingTwistConstraintSettings();

        settings.setPosition1(point);
        settings.setPosition2(point);
        settings.setTwistAxis1(new Vec3(axis.x, axis.y, axis.z));
        settings.setTwistAxis2(new Vec3(axis.x, axis.y, axis.z));
        settings.setPlaneAxis1(new Vec3(plane.x, plane.y, plane.z));
        settings.setPlaneAxis2(new Vec3(plane.x, plane.y, plane.z));
        /* The bend is the author's: wide for hair, narrow for a braid that must not fold. */
        settings.setNormalHalfConeAngle((float) Math.toRadians(config.bend()));
        settings.setPlaneHalfConeAngle((float) Math.toRadians(config.bend()));
        settings.setTwistMinAngle((float) Math.toRadians(-TWIST_DEGREES));
        settings.setTwistMaxAngle((float) Math.toRadians(TWIST_DEGREES));

        SwingTwistConstraint constraint = (SwingTwistConstraint) settings.create(anchor, segment.body);

        physics.getSystem().addConstraint(constraint);

        PhysicsJoints.tune(constraint, config.stiffness(), config.damping(), index, count, 1F - config.falloff());

        return constraint;
    }

    /**
     * Runs before the world steps: keeps the pins on the bones they follow, the segments' motion
     * type in step with the handle, and drives them — kinematically at 1, by the velocity blend
     * below it.
     */
    @Override
    public void update(RigUpdate update)
    {
        PhysicsWorld physics = update.physics;
        FilmScene scene = update.scene;
        MatrixCache matrices = update.matrices;
        Matrix4f actorWorld = update.actorWorld;
        boolean reset = update.reset;

        this.captureBase(update);
        this.applySettings(physics);

        BodyInterface bodies = physics.getBodies();

        float authority = PhysicsForms.getAuthority(this.form);
        boolean wanted = authority >= 1F;
        boolean put = reset;

        /* The pins ride whatever the strand actually hangs from: the animation — or, through the
         * published delta, the bone a ragdoll has carried away. Hair pinned to a head that is on
         * the floor has to be simulated at the floor, not at standing height where the keyframes
         * still have the head; the renderer already draws it composed on the fallen head, and the
         * two disagreeing was a strand visibly detached from its own scalp. */
        for (Pin pin : this.pins)
        {
            MatrixCacheEntry entry = matrices == null ? null : matrices.get(pin.path);

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            Matrix4f delta = this.lift(update, pin.above);

            if (delta == null)
            {
                this.worldMatrix.set(actorWorld).mul(entry.matrix());
            }
            else
            {
                this.worldMatrix.set(delta).mul(actorWorld).mul(entry.matrix());
            }

            this.worldMatrix.getTranslation(this.translation);
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.scratchPosition.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.scratchRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (reset)
            {
                this.move.place(bodies, pin.id, this.scratchPosition, this.scratchRotation);
            }
            else
            {
                /* Steered when the bone moved, placed when its keyframes cut — see KinematicDrive. */
                this.move.move(bodies, pin.id, this.scratchPosition, this.scratchRotation);
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

            /* The same lift as the pins: the drive's target — the combed rest shape — moves with
             * the fallen bone the strand grows from, or the strand is pulled towards a hairstyle
             * hanging in mid-air. */
            Matrix4f delta = this.lift(update, segment.above);

            if (delta == null)
            {
                this.worldMatrix.set(actorWorld).mul(entry.matrix());
            }
            else
            {
                this.worldMatrix.set(delta).mul(actorWorld).mul(entry.matrix());
            }

            this.worldMatrix.getTranslation(this.translation);
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.scratchPosition.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.scratchRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (put)
            {
                this.move.place(bodies, segment.id, this.scratchPosition, this.scratchRotation);
            }
            else if (this.kinematic)
            {
                this.move.move(bodies, segment.id, this.scratchPosition, this.scratchRotation);
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
    @Override
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

            if (PhysicsMath.finite(this.linear))
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
        float falloff = config.falloff();
        float bend = config.bend();
        int steps = physics.getCollisionSteps();

        if (stiffness != this.lastStiffness || damping != this.lastDamping || falloff != this.lastFalloff)
        {
            for (Joint joint : this.joints)
            {
                PhysicsJoints.tune(joint.constraint(), stiffness, damping, joint.index(), joint.count(), 1F - falloff);
            }

            this.lastFalloff = falloff;
        }

        if (bend != this.lastBend)
        {
            for (Joint joint : this.joints)
            {
                joint.constraint().setNormalHalfConeAngle((float) Math.toRadians(bend));
                joint.constraint().setPlaneHalfConeAngle((float) Math.toRadians(bend));
            }

            this.lastBend = bend;
        }

        /* The knob's larger half: what the bones themselves shed. Pushed here as well as at build
         * time, or an author would drag the slider through a whole take and see only the springs
         * answer. The sub-step count is part of the rate's arithmetic, so a changed quality
         * setting re-pushes the same knob too. */
        if (damping != this.lastDamping || steps != this.dampedSteps)
        {
            float rate = PhysicsMath.bodyDamping(damping, steps);

            for (Segment segment : this.segments)
            {
                segment.body().getMotionProperties().setLinearDamping(rate);
                segment.body().getMotionProperties().setAngularDamping(rate);
            }

            this.dampedSteps = steps;
        }

        this.lastStiffness = stiffness;
        this.lastDamping = damping;
    }

    /** The velocity blend every driven body here uses — the reasoning lives in {@link BodyDrive}. */
    private void drive(BodyInterface bodies, Segment segment, float authority)
    {
        if (this.drive.apply(bodies, segment.id, this.scratchPosition, this.scratchRotation, authority))
        {
            return;
        }

        if (!this.misfed)
        {
            this.misfed = true;

            BBSPhysics.LOGGER.warn(
                "The drive for the chain bone '{}' of '{}' came out unusable — {} — so the strand is left to itself. The pose it is pulled towards is broken.",
                segment.bone, this.form.getDisplayName(), this.drive.describe());
        }
    }

    /**
     * Runs right after the world stepped: reads every strand bone, expresses it in the model's own
     * group space and writes it into the recording — the ragdoll's conversion, step for step (§10.1),
     * because the renderer substitutes both through the same applier.
     */
    @Override
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

            if (!PhysicsMath.finite(this.scratchPosition.xx()) || !PhysicsMath.finite(this.scratchPosition.yy()) || !PhysicsMath.finite(this.scratchPosition.zz())
                || !PhysicsMath.finite(this.scratchRotation.getX()) || !PhysicsMath.finite(this.scratchRotation.getY())
                || !PhysicsMath.finite(this.scratchRotation.getZ()) || !PhysicsMath.finite(this.scratchRotation.getW()))
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

    /**
     * Puts the recorded strands for {@code tick} into the model's state, as a jump, and tells the
     * bake the model has bones to read off — the same way the ragdoll does, since the bake runs
     * the substitution for both at once.
     */
    @Override
    public void bake(PhysicsCache cache, int tick, PhysicsBake bake)
    {
        this.readCache(cache, tick, true);

        if (this.recorded)
        {
            bake.bones(this.form, this.formPath);
        }
    }

    /** Hands the renderer the recorded strands for the frame being drawn. */
    @Override
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
    @Override
    public boolean isLost()
    {
        return this.lost;
    }

    /**
     * Hair cares where the ragdoll of the same actor carried the bones it grows from — the pins
     * and the drive targets are lifted by the published deltas, see {@link #lift}. Without this
     * the deltas are never published for an actor that is only a ragdoll and its hair, and the
     * strands stay anchored at standing height while the head lies on the floor.
     */
    @Override
    public boolean readsBoneDeltas()
    {
        return true;
    }

    /**
     * The frame the answer is expressed against — the ragdoll's, for the same reason. The model
     * form's own anchor delta is folded in when the whole model rides a falling bone: the recorded
     * frames have to come out relative to the root the renderer actually composes on, which in
     * that case is the fallen one.
     */
    private void captureBase(RigUpdate update)
    {
        MatrixCacheEntry entry = update.matrices == null ? null : update.matrices.get(this.formPath);

        if (entry == null || entry.matrix() == null)
        {
            this.baseValid = false;

            return;
        }

        Matrix4f delta = this.formAnchor == null || update.deltas.isEmpty() ? null : update.deltas.get(this.formAnchor);

        if (delta == null)
        {
            this.base.set(update.actorWorld).mul(entry.matrix()).rotateY(MathUtils.PI);
        }
        else
        {
            this.base.set(delta).mul(update.actorWorld).mul(entry.matrix()).rotateY(MathUtils.PI);
        }

        this.baseInverse.set(this.base).invert();
        this.baseValid = true;
    }

    /** Lets go of the form: the strands go back to being drawn from their keyframes. */
    @Override
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

    /**
     * One claimed bone as a strand segment. {@code collides} is whether the Collision tab gave it
     * a shape — an unshaped bone hangs in the layer that meets nothing. {@code above} is the
     * bone's own ancestry as delta keys — see {@link #lift}.
     */
    private record Segment(String bone, String path, int id, Body body, int sub, int channel, boolean collides, String[] above)
    {}

    /** A kinematic handle following a bone that has no body of its own — see {@link #anchorFor}. */
    private record Pin(String path, int id, Body body, int sub, String[] above)
    {}

    /** What a strand's top was jointed to, and its subgroup — for the collision excuse. */
    /**
     * One joint with where it sits along its own strand: the root of a strand is 0 and the tip is
     * {@code count - 1}, which is what the spring's falloff is measured on. Kept rather than
     * recomputed because an author dragging the stiffness slider re-tunes every joint per tick.
     */
    private record Joint(SwingTwistConstraint constraint, int index, int count)
    {}

    private record Anchor(Body body, int sub)
    {}
}
