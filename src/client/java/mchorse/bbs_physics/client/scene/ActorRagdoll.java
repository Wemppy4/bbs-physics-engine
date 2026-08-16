package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.FixedConstraintSettings;
import com.github.stephengold.joltjni.HingeConstraintSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.TwoBodyConstraintSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
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
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.JoltShapes;
import mchorse.bbs_physics.client.ragdoll.RagdollAttachment;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollJoint;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One model's ragdoll: its marked-up bones as rigid bodies held together by joints, and the single
 * "animation strength" handle deciding who owns the pose — the keyframes or the fall.
 *
 * <p><b>Which bones become bodies is the collision markup's decision</b>, not a second markup of
 * its own (§5.2): a bone marked in the collision tab is a ragdoll part, an unmarked one does not
 * exist to physics. The ragdoll tab only says how the parts are jointed — a soft cone by default,
 * so an enabled ragdoll works with nothing configured, just drunkenly: knees bend every way until
 * they are told they are hinges.</p>
 *
 * <p><b>The skeleton is held by Jolt's own joints</b> — {@code SwingTwistConstraint} for the cone,
 * {@code HingeConstraint} for knees and elbows, {@code FixedConstraint} for welds — created once at
 * scene build and living as long as the world, so the recording's shape never changes. Adjacent
 * parts are excused from colliding with each other through a group filter; everything else
 * collides normally, which is what keeps an arm from folding through the chest.</p>
 *
 * <p><b>The handle drives exactly like a physics body's.</b> At 1 the parts are kinematic bones —
 * the same thing {@link ActorRig} builds, shoving props and ignoring gravity. Below 1 they turn
 * dynamic and are pulled towards the animated pose by the same velocity blend
 * {@link PhysicsBodyRig} was proven with: each tick every part is offered the velocity that would
 * carry it to its keyframed place, mixed with what it already has in the handle's proportion. The
 * joints stay out of that bargain and only enforce their limits, so at 0.6 the character walks
 * where it is told while sagging and stumbling into what it hits, and at 0 it is entirely the
 * world's problem.</p>
 *
 * <p><b>The handle is the drawn pose's weight as well as the drive's</b>, and both ends of it are
 * exact: at 1 nothing is substituted and the animation draws itself, at 0 the bodies are the pose
 * outright, and in between the two are mixed by that much (see {@code RagdollPoseApplier}). It has
 * to be a weight rather than a threshold in both places or the fade has a cliff at whichever end
 * the threshold sits — which it did, at the top, where a ragdoll being taken back by the animation
 * jerked the last of the way home. Blending rather than overruling also settles what happens to IK
 * on a ragdolled bone for free: it is faded out with everything else instead of being muted, and
 * the pose the parts are pulled towards includes it either way.</p>
 *
 * <p><b>Cubic models only, for now.</b> The simulated pose is handed back through the model's
 * group frames ({@code orient}/{@code offset}), which BOBJ models do not have — a BOBJ model would
 * fall invisibly, so it does not get a ragdoll at all and its bones stay kinematic.</p>
 */
public class ActorRagdoll
{
    /** Shared, never written to — the velocity a placed body is stopped with. */
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    /**
     * A light resistance in every joint, in newton-metres. Without any, a free ragdoll's limbs
     * swing like pendulums in a vacuum and the whole body jitters against its limits; this is the
     * difference between a body and a wind chime. Not exposed as a setting until someone needs it.
     */
    private static final float JOINT_FRICTION = 3F;

    /** Spin bleeds off faster than travel — the standard ragdoll tuning against limbs that windmill. */
    private static final float ANGULAR_DAMPING = 0.3F;

    /**
     * Speeds no part of a character ever reaches honestly, in blocks and radians per second: a film
     * tick is fifty milliseconds, so this is five blocks or four turns in a single tick.
     *
     * <p>Diagnostics only — nothing is clamped or cancelled. A part that leaves the world is already
     * reported, but by then every number about it is infinity and says nothing about what happened;
     * these catch it on the way up, while the numbers still describe the push it was given.</p>
     */
    private static final float RUNAWAY_SPEED = 100F;
    private static final float RUNAWAY_SPIN = 25F;

    private final ModelForm form;
    private final String formPath;
    private final List<Part> parts = new ArrayList<>();
    private final RagdollState state = new RagdollState();

    /**
     * The joints, which Jolt holds by pointer. Kept in a field so the Java side cannot collect them
     * while the world still uses them; they die with the world.
     */
    private final List<TwoBodyConstraint> joints = new ArrayList<>();

    /** Whether the parts are currently kinematic — the cached side of the motion type switch. */
    private boolean kinematic = true;

    /** Whether the frame drawn last had a recorded pose, so a gap in the recording reads as a jump. */
    private boolean recorded;

    /** Whether a part has already been reported as having left the world — see {@link #record}. */
    private boolean lost;

    /** Whether a part has already been reported as running away — see {@link #reportRunaway}. */
    private boolean runaway;

    /** Whether a broken drive velocity has already been reported — see {@link #drive}. */
    private boolean misfed;

    /* The frame the simulation's answer is expressed against: the model's flipped group space,
     * captured per tick because the actor moves. See read(). */
    private final Matrix4f base = new Matrix4f();
    private final Matrix4f baseInverse = new Matrix4f();
    private boolean baseValid;

    /* Scratch, allocated once — all of this runs per part per tick. */
    private final Matrix4f worldMatrix = new Matrix4f();
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
    private final Matrix4f poseFrame = new Matrix4f();

    private ActorRagdoll(ModelForm form, String formPath)
    {
        this.form = form;
        this.formPath = formPath;
    }

    /**
     * Builds a ragdoll for one ragdoll-enabled model form, or returns null when there is nothing
     * to build — no marked bones, or a model type the pose cannot be handed back to.
     *
     * @param pieces   the marked-up bone slots belonging to this form, already collected
     * @param matrices the actor's pose at scene build — the placement the bodies start from
     * @param group    the actor's collision group, shared with its kinematic bones and with any
     *                 other ragdoll hanging off the same actor
     */
    public static ActorRagdoll build(PhysicsWorld physics, ModelForm form, String formPath, List<CollisionCollector.Piece> pieces, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group)
    {
        ModelInstance instance = ModelFormRenderer.getModel(form);

        if (instance == null)
        {
            /* Not "this model cannot have a ragdoll" — "this model is not here yet". BBS loads
             * models on a thread of its own and hands back null until one arrives, so a scene
             * assembled in the same moment the film is opened sees nothing at all. Said apart from
             * the case below because conflating the two cost a long hunt: the log claimed a
             * perfectly ordinary cubic model was an unsupported format, and the real fault — a
             * scene built too early and never rebuilt — went unnoticed behind it. The scene rebuilds
             * itself once the model lands (see {@code FilmScene.needsRebuild}). */
            BBSPhysics.LOGGER.info("A ragdoll is enabled on '{}', whose model has not finished loading; the scene will be built again when it has.", form.getDisplayName());

            return null;
        }

        Model model = instance.model instanceof Model cubic ? cubic : null;

        if (model == null)
        {
            BBSPhysics.LOGGER.warn("A ragdoll is enabled on '{}', but only cubic models can hand the simulated pose back to the renderer yet; its bones stay kinematic.", form.getDisplayName());

            return null;
        }

        if (pieces.isEmpty())
        {
            return null;
        }

        Map<String, ModelGroup> groups = new HashMap<>();

        for (ModelGroup modelGroup : model.getAllGroups())
        {
            groups.put(modelGroup.id, modelGroup);
        }

        FormRagdoll config = FormRagdolls.get(form);
        BodyInterface bodies = physics.getBodies();
        ActorRagdoll ragdoll = new ActorRagdoll(form, formPath);
        Map<String, Part> byBone = new HashMap<>();

        for (CollisionCollector.Piece piece : pieces)
        {
            MatrixCacheEntry entry = matrices.get(piece.path());

            if (entry == null || entry.matrix() == null || !groups.containsKey(piece.label()))
            {
                continue;
            }

            ConstShape shape = JoltShapes.build(piece.shapes());

            if (shape == null)
            {
                continue;
            }

            /* Where the animation has this bone right now — the parts must exist exactly where
             * their first drive would put them, or the first tick sweeps them across the set. */
            ragdoll.worldMatrix.set(actorWorld).mul(entry.matrix());
            ragdoll.worldMatrix.getTranslation(ragdoll.translation);
            ragdoll.worldMatrix.getUnnormalizedRotation(ragdoll.orientation);

            BodyCreationSettings settings = new BodyCreationSettings(
                shape,
                new RVec3(
                    ragdoll.translation.x - scene.getOriginX(),
                    ragdoll.translation.y - scene.getOriginY(),
                    ragdoll.translation.z - scene.getOriginZ()),
                new Quat(ragdoll.orientation.x, ragdoll.orientation.y, ragdoll.orientation.z, ragdoll.orientation.w),
                EMotionType.Kinematic,
                PhysicsLayers.BONE);

            settings.setFriction(0.6F);
            settings.setAngularDamping(ANGULAR_DAMPING);

            /* A film tick is fifty milliseconds; a flailing hand covers more than its own size in
             * one, and tested only at the ends it passes through the floor it slapped. */
            settings.setMotionQuality(EMotionQuality.LinearCast);

            /* The actor's own group, which the kinematic bones are in too — see
             * {@link ActorCollisionGroup} for which pairs it excuses and what each of them would
             * otherwise do. Another actor's bodies are in a different group and collide normally,
             * so two fallen characters land on each other rather than through. */
            int sub = group.claimPart();

            settings.setCollisionGroup(group.of(sub));

            /* Dynamic-capable from birth even though it starts kinematic: the body has to carry
             * proper mass and inertia for the moment the handle lets it go. Jolt weighs the shape
             * by volume at water's density, which for body parts is the right ballpark. */
            Body body = bodies.createBody(settings);

            bodies.addBody(body.getId(), EActivation.Activate);

            Part part = new Part(piece.label(), piece.path(), body.getId(), body, sub, scene.addChannel());

            ragdoll.parts.add(part);
            byBone.put(piece.label(), part);

            SceneBody debug = new SceneBody(body.getId(), 0.75F, 0.4F, 1F);

            debug.addShapes(piece.shapes());
            scene.addDebugBody(debug);
        }

        if (ragdoll.parts.isEmpty())
        {
            return null;
        }

        /* Who hangs off whom — the three-step answer, shared with the viewport preview so that the
         * lines an author sees are the joints that will actually be built (§7.6). */
        Map<String, String> attachment = RagdollAttachment.resolve(config, pieces, model, matrices, actorWorld);

        for (Part part : ragdoll.parts)
        {
            Part parent = byBone.get(attachment.get(part.bone));

            if (parent == null)
            {
                continue;
            }

            RagdollJoint joint = config.get(part.bone);
            TwoBodyConstraintSettings settings = ragdoll.jointSettings(joint, part, parent, groups, matrices, actorWorld, scene);

            if (settings == null)
            {
                continue;
            }

            TwoBodyConstraint constraint = settings.create(parent.body, part.body);

            physics.getSystem().addConstraint(constraint);
            ragdoll.joints.add(constraint);

            /* Neighbours share a joint; their shapes meet at it by design and always will. Letting
             * them also collide would have every joint permanently fighting its own limits. */
            group.excuse(part.sub, parent.sub);
        }

        FormRagdolls.setState(form, ragdoll.state);

        return ragdoll;
    }

    /**
     * One joint, set up in world space at the build pose — the bodies are already standing on it,
     * so Jolt converts to each body's local frame correctly on its own.
     */
    private TwoBodyConstraintSettings jointSettings(RagdollJoint joint, Part part, Part parent, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene)
    {
        MatrixCacheEntry entry = matrices.get(part.path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        this.worldMatrix.set(actorWorld).mul(entry.matrix());
        this.worldMatrix.getTranslation(this.translation);

        /* The joint sits at the child bone's pivot: the elbow is where the forearm turns. In the
         * scene's own coordinates, because the bodies live there — "world space" to Jolt is the
         * space the bodies are in, and a point left in raw world coordinates sits hundreds of
         * blocks from the bodies it is meant to join. The lever arm of that mistake is the whole
         * distance to the scene's origin, and the parts scatter as if they were never joined. */
        RVec3 point = new RVec3(
            this.translation.x - scene.getOriginX(),
            this.translation.y - scene.getOriginY(),
            this.translation.z - scene.getOriginZ());

        switch (joint.kind())
        {
            case FREE:
                return null;

            case FIXED:
            {
                FixedConstraintSettings fixed = new FixedConstraintSettings();

                fixed.setAutoDetectPoint(true);

                /* Both bones' own axes as they stand, which is what tells Jolt the pose the weld is
                 * meant to hold. Left at their defaults — the world's own X and Y for both sides —
                 * the pair says "these two bones face the same way as the world", and that is a
                 * pose no bone of a character is ever in: a cubic bone's frame is turned half a
                 * circle to begin with (§10.1), and the two ends of a weld rarely agree even
                 * before that. A constraint between two kinematic bodies does nothing, so the
                 * violation is invisible while the animation is in charge and the whole of it comes
                 * due on the one tick the parts are released — the weld hauling both bones round to
                 * the world's axes with however much force that takes. */
                MatrixCacheEntry parentEntry = matrices.get(parent.path);

                if (parentEntry == null || parentEntry.matrix() == null)
                {
                    return null;
                }

                Quaternionf child = new Quaternionf();
                Quaternionf above = new Quaternionf();

                this.worldMatrix.getUnnormalizedRotation(child);
                new Matrix4f(actorWorld).mul(parentEntry.matrix()).getUnnormalizedRotation(above);

                /* One is the parent: the joint is created as create(parent.body, part.body). */
                fixed.setAxisX1(axis(above, 1F, 0F, 0F));
                fixed.setAxisY1(axis(above, 0F, 1F, 0F));
                fixed.setAxisX2(axis(child, 1F, 0F, 0F));
                fixed.setAxisY2(axis(child, 0F, 1F, 0F));

                return fixed;
            }

            case HINGE:
            {
                HingeConstraintSettings hinge = new HingeConstraintSettings();

                /* The hinge axis is one of the bone's own axes, taken from its world frame as it
                 * stands — the frame of a cubic bone carries the Ry(π) flip (§10.1), consistently
                 * for every bone, so the author picks the axis that looks right in the preview and
                 * it stays right. */
                Vector3f axis = this.boneAxis(joint.hingeAxis());

                hinge.setPoint1(point);
                hinge.setPoint2(point);
                hinge.setHingeAxis1(new Vec3(axis.x, axis.y, axis.z));
                hinge.setHingeAxis2(new Vec3(axis.x, axis.y, axis.z));

                Vector3f normal = perpendicular(axis);

                hinge.setNormalAxis1(new Vec3(normal.x, normal.y, normal.z));
                hinge.setNormalAxis2(new Vec3(normal.x, normal.y, normal.z));
                hinge.setLimitsMin((float) Math.toRadians(joint.hingeMin()));
                hinge.setLimitsMax((float) Math.toRadians(joint.hingeMax()));
                hinge.setMaxFrictionTorque(JOINT_FRICTION);

                return hinge;
            }

            case CONE:
            default:
            {
                SwingTwistConstraintSettings cone = new SwingTwistConstraintSettings();

                /* The cone leans around the bone's rest direction: pivot towards the first child's
                 * pivot, which is the direction the limb visibly runs — positions only, so the
                 * frame flip cancels out of it. A leaf bone continues its parent's direction. */
                Vector3f axis = this.boneDirection(part, parent, groups, matrices, actorWorld);
                Vector3f plane = perpendicular(axis);

                cone.setPosition1(point);
                cone.setPosition2(point);
                cone.setTwistAxis1(new Vec3(axis.x, axis.y, axis.z));
                cone.setTwistAxis2(new Vec3(axis.x, axis.y, axis.z));
                cone.setPlaneAxis1(new Vec3(plane.x, plane.y, plane.z));
                cone.setPlaneAxis2(new Vec3(plane.x, plane.y, plane.z));
                cone.setNormalHalfConeAngle((float) Math.toRadians(joint.swing()));
                cone.setPlaneHalfConeAngle((float) Math.toRadians(joint.swing()));

                /* The twist range the author gives is min..max; Jolt wants it symmetric around the
                 * rest twist only in sign convention, so it is passed straight through. */
                cone.setTwistMinAngle((float) Math.toRadians(joint.twistMin()));
                cone.setTwistMaxAngle((float) Math.toRadians(joint.twistMax()));
                cone.setMaxFrictionTorque(JOINT_FRICTION);

                return cone;
            }
        }
    }

    /** One of the bone's local axes (0=X, 1=Y, 2=Z), in world space as the bone stands. */
    private Vector3f boneAxis(int axis)
    {
        this.worldMatrix.getUnnormalizedRotation(this.orientation);

        Vector3f result = new Vector3f(axis == 0 ? 1F : 0F, axis == 1 ? 1F : 0F, axis == 2 ? 1F : 0F);

        return this.orientation.transform(result).normalize();
    }

    /**
     * The direction the bone runs, in world space: its pivot towards its first child's pivot, or
     * onward from its parent when it has no children to point at. Degenerate cases — stacked
     * pivots — fall back to world up, which at least never crashes a build.
     */
    private Vector3f boneDirection(Part part, Part parent, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld)
    {
        Vector3f from = new Vector3f(this.translation);
        ModelGroup group = groups.get(part.bone);

        if (group != null)
        {
            for (ModelGroup child : group.children)
            {
                Vector3f to = this.pivotOf(child.id, matrices, actorWorld);

                if (to != null && to.distanceSquared(from) > 1.0e-6F)
                {
                    return to.sub(from).normalize();
                }
            }
        }

        Vector3f parentPivot = this.pivotOf(parent.bone, matrices, actorWorld);

        if (parentPivot != null && parentPivot.distanceSquared(from) > 1.0e-6F)
        {
            return from.sub(parentPivot).normalize();
        }

        return new Vector3f(0F, 1F, 0F);
    }

    private Vector3f pivotOf(String bone, MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry entry = matrices.get(StringUtils.combinePaths(this.formPath, bone));

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        return new Matrix4f(actorWorld).mul(entry.matrix()).getTranslation(new Vector3f());
    }

    /**
     * Says once, in numbers, when a part is being flung rather than falling.
     *
     * <p>Which of the two speeds is the absurd one is the whole of the diagnosis, and they mean
     * different things. A huge spin with an ordinary speed is a joint pumping the part round against
     * its own limits; a huge speed along one direction is a contact throwing it out of something it
     * was inside; both huge, with the part already far from where the animation has it, is the drive
     * chasing a pose it will never reach. There is no way to tell these apart from the viewport, and
     * once the part is gone every number about it is infinity.</p>
     */
    private void reportRunaway(BodyInterface bodies, Part part, int tick, float authority)
    {
        if (this.runaway)
        {
            return;
        }

        Vec3 velocity = bodies.getLinearVelocity(part.id);
        Vec3 spin = bodies.getAngularVelocity(part.id);

        float speed = length(velocity.getX(), velocity.getY(), velocity.getZ());
        float turn = length(spin.getX(), spin.getY(), spin.getZ());

        if (speed < RUNAWAY_SPEED && turn < RUNAWAY_SPIN)
        {
            return;
        }

        this.runaway = true;

        BBSPhysics.LOGGER.warn(
            "Ragdoll runaway on '{}', bone '{}' at tick {}: speed {} blocks/s, spin {} rad/s, handle {}, at ({}, {}, {}) in scene coordinates.",
            this.form.getDisplayName(), part.bone, tick,
            String.format("%.1f", speed), String.format("%.1f", turn), String.format("%.3f", authority),
            String.format("%.2f", this.scratchPosition.xx()),
            String.format("%.2f", this.scratchPosition.yy()),
            String.format("%.2f", this.scratchPosition.zz()));
    }

    private static float length(float x, float y, float z)
    {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** One of a frame's own axes, in world space — what Jolt is handed to read a rest pose from. */
    private static Vec3 axis(Quaternionf rotation, float x, float y, float z)
    {
        Vector3f result = rotation.transform(new Vector3f(x, y, z)).normalize();

        return new Vec3(result.x, result.y, result.z);
    }

    /** Any unit vector perpendicular to {@code axis} — crossed with whichever world axis it hugs least. */
    private static Vector3f perpendicular(Vector3f axis)
    {
        Vector3f helper = Math.abs(axis.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);

        return helper.cross(axis, new Vector3f()).normalize();
    }

    /**
     * Runs before the world steps: reads the handle at the tick being simulated, keeps the parts'
     * motion type in step with it, and drives them — kinematically at 1, by the velocity blend
     * below it.
     *
     * @param reset whether the scene is starting over at this tick, in which case every part is
     *              stood at its animated pose and stopped, whatever the handle says
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean reset)
    {
        this.captureBase(matrices, actorWorld);

        BodyInterface bodies = physics.getBodies();
        float authority = FormRagdolls.getAuthority(this.form);
        boolean wanted = authority >= 1F;
        boolean put = reset;

        if (wanted != this.kinematic)
        {
            for (Part part : this.parts)
            {
                /* Jolt keeps the velocity across the change, which is what lets a released body
                 * inherit the animation's momentum — the character crumples out of its run rather
                 * than out of thin air. */
                bodies.setMotionType(part.id, wanted ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);

                /* And the layer moves with it. Kinematic bones live in the bone layer, where
                 * bone-bone and bone-static pairs are switched off — they cannot push each other
                 * anyway, and a standing character intersects the floor all day. Dynamic parts
                 * need exactly those pairs. */
                bodies.setObjectLayer(part.id, wanted ? PhysicsLayers.BONE : PhysicsLayers.MOVING);
            }

            this.kinematic = wanted;

            /* Taken back by the animation after living its own life: nowhere near its keyframes.
             * Steered there over one tick it would rake the whole set on the way. */
            put |= wanted;
        }

        for (Part part : this.parts)
        {
            MatrixCacheEntry entry = matrices == null ? null : matrices.get(part.path);

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
                bodies.setPositionAndRotation(part.id, this.scratchPosition, this.scratchRotation, EActivation.Activate);
                bodies.setLinearAndAngularVelocity(part.id, ZERO, ZERO);
            }
            else if (this.kinematic)
            {
                bodies.moveKinematic(part.id, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
            }
            else if (authority > 0F)
            {
                this.drive(bodies, part, authority);
            }
        }
    }

    /**
     * The velocity blend, verbatim from {@link PhysicsBodyRig}: the part is offered the velocity
     * that would carry it to its keyframed place over one tick, mixed with what it already has in
     * the handle's proportion. Mixing velocities rather than writing them is what keeps gravity in
     * the picture — a weakly animated limb sags instead of hovering.
     */
    private void drive(BodyInterface bodies, Part part, float authority)
    {
        bodies.getPositionAndRotation(part.id, this.currentPosition, this.currentRotation);

        Vec3 velocity = bodies.getLinearVelocity(part.id);
        Vec3 spin = bodies.getAngularVelocity(part.id);

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

        /* The turn's axis and speed, by hand rather than through JOML's axis-angle conversion,
         * because of one property of the tick a ragdoll is released on: the parts stood glued to
         * the animation until this very moment, so the delta is the identity give or take float
         * dust — and rounding can land that dust a hair ABOVE w = 1. JOML's conversion takes a
         * square root of (1 - w²), negative there, behind a guard that catches infinity but walks
         * straight past NaN; the axis comes out NaN, NaN times a zero angle is still NaN, and one
         * poisoned velocity spreads through the joints to every part in a single solver pass. The
         * whole ragdoll vanishes on the spot, sane one tick and not-a-number the next, with no
         * runaway for the diagnostics to see. Clamped, the dust reads as what it is: no turn at
         * all. Whether a given release explodes depends on the last bits of the pose, which is why
         * it came and went with keyframe shuffling — and why a body released mid-swing survived
         * where one released standing still did not. */
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

        /* The last line of defence, because this is handed straight to the solver and one
         * non-finite component in it is the whole jointed ragdoll gone next step. Whatever
         * slipped through — a bone scaled to nothing turns the target rotation into NaN, a broken
         * pose does the same to the position — the part is better left falling free for a tick
         * than fed poison, and the log gets the numbers while they still mean something. */
        if (!finite(this.linear) || !finite(this.angular))
        {
            if (!this.misfed)
            {
                this.misfed = true;

                BBSPhysics.LOGGER.warn(
                    "The drive for bone '{}' of a ragdoll on '{}' came out unusable — linear ({}, {}, {}), angular ({}, {}, {}), handle {} — so the part falls free instead. The pose it is pulled towards is broken.",
                    part.bone, this.form.getDisplayName(),
                    this.linear.getX(), this.linear.getY(), this.linear.getZ(),
                    this.angular.getX(), this.angular.getY(), this.angular.getZ(), authority);
            }

            return;
        }

        bodies.setLinearAndAngularVelocity(part.id, this.linear, this.angular);

        /* A sleeping body ignores handed velocities, and a pulled limb that rested for a moment
         * would never pick its animation back up. */
        bodies.activateBody(part.id);
    }

    /** Whether a velocity may be handed to the solver at all — see the guard in {@link #drive}. */
    private static boolean finite(Vec3 velocity)
    {
        return Float.isFinite(velocity.getX()) && Float.isFinite(velocity.getY()) && Float.isFinite(velocity.getZ());
    }

    private static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }

    /** Whether a coordinate is a place at all — not infinite, not the result of dividing by zero. */
    private static boolean finite(double value)
    {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Runs right after the world stepped: reads where the simulation put every part, expresses it
     * in the model's own group space, and writes that into the recording under {@code tick}.
     *
     * <p>The conversion is done here rather than at draw time for the same reason
     * {@link PhysicsBodyRig#record} does it: the frame it is expressed against is a function of the
     * tick, which has just been posed, so a recorded film draws with no pose evaluation at all.</p>
     *
     * <p>Written every tick, the kinematic ones included — the tick the handle drops below 1 needs
     * the tick before it to interpolate from, or the release visibly jumps. The authority is
     * recorded alongside, because whether the simulation owns the pose is part of what was true on
     * that tick, not something to be re-read off a form at draw time.</p>
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        if (!this.baseValid)
        {
            /* No frame to express the answer against — a model that has not loaded, most likely.
             * The silence is written rather than skipped, because the slots in the recording are
             * reused across invalidations and a skipped one would read as an old answer. */
            this.translation.zero();
            this.orientation.identity();

            for (Part part : this.parts)
            {
                cache.write(tick, part.channel, this.translation, this.orientation, PhysicsCache.SILENT);
            }

            return;
        }

        BodyInterface bodies = physics.getBodies();
        float authority = FormRagdolls.getAuthority(this.form);

        for (Part part : this.parts)
        {
            bodies.getPositionAndRotation(part.id, this.scratchPosition, this.scratchRotation);

            if (!finite(this.scratchPosition.xx()) || !finite(this.scratchPosition.yy()) || !finite(this.scratchPosition.zz())
                || !finite(this.scratchRotation.getX()) || !finite(this.scratchRotation.getY()) || !finite(this.scratchRotation.getZ()) || !finite(this.scratchRotation.getW()))
            {
                /* The simulation lost this part — an impossible impulse or a poisoned velocity is
                 * the way that happens, sometimes over a few ticks of doubling and sometimes in a
                 * single step. The rotation is checked alongside the position because it is where
                 * a bad spin lands first: the part turns not-a-number a tick before it travels
                 * there. Drawn, either one is a character that has silently vanished: the pose
                 * applier would hand the renderer a bone at nowhere and nothing comes out of the
                 * far end.
                 *
                 * So it is recorded as silence instead, which the reader already knows means plain
                 * animation (Р8.1), and said out loud once — a bone that leaves the world is worth
                 * a line in the log, and until it is there the only symptom is an actor going
                 * missing with no explanation at all. */
                if (!this.lost)
                {
                    this.lost = true;

                    BBSPhysics.LOGGER.warn("The bone '{}' of a ragdoll on '{}' left the world at tick {}; it is drawn from its keyframes from here on. Something handed it an impossible push — the warnings above this line, if there are any, say who.", part.bone, this.form.getDisplayName(), tick);
                }

                this.translation.zero();
                this.orientation.identity();

                cache.write(tick, part.channel, this.translation, this.orientation, PhysicsCache.SILENT);

                continue;
            }

            this.reportRunaway(bodies, part, tick, authority);

            this.orientation.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW());
            this.poseFrame.translationRotate(
                (float) (this.scratchPosition.xx() + scene.getOriginX()),
                (float) (this.scratchPosition.yy() + scene.getOriginY()),
                (float) (this.scratchPosition.zz() + scene.getOriginZ()),
                this.orientation);

            /* The body follows the bone's cache frame, which carries the cubic flip on its right
             * (§10.1: frame = G × T(pivot/16) × Ry(π)) — multiplying the flip back on undoes it,
             * and the base inverse then peels the actor and the form off the left. What is left is
             * the bone's pivot frame in the model's flipped group space: exactly the thing the
             * render walk composes, so the renderer can solve for its local rotation and shift. */
            this.poseFrame.rotateY(MathUtils.PI);
            this.baseInverse.mul(this.poseFrame, this.poseFrame);

            this.poseFrame.getTranslation(this.translation);
            this.poseFrame.getUnnormalizedRotation(this.orientation);

            cache.write(tick, part.channel, this.translation, this.orientation, authority);
        }
    }

    /**
     * Hands the renderer the recorded pose for the frame being drawn, and the handle it was
     * simulated under — which decides how much of that pose is used.
     *
     * <p>Two ways this ends up drawing plain animation, and they are different things. The handle
     * standing at a full 1 leaves the substitution no weight, so the animation draws itself
     * untouched — smoother than substituting a pose that merely agrees with it, and now the end of
     * a continuous fade rather than a rule that fires on one tick. An <em>unrecorded</em> tick means
     * the recording has not reached this frame yet, and Р8.1 says the same thing happens: the
     * character stands on its keyframes until the catch-up gets here.</p>
     */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        /* Coming back from unrecorded frames counts as a jump: the pose the bones are drawn out of
         * is wherever the animation last left them, not a place they fell from. */
        boolean jumped = teleport || !this.recorded;
        boolean recorded = false;
        float authority = 1F;

        for (Part part : this.parts)
        {
            if (cache.read(tick, part.channel, this.translation, this.orientation))
            {
                this.state.set(part.bone, this.translation, this.orientation, jumped);

                if (!recorded)
                {
                    /* From a channel that actually answered, not from the first part outright. The
                     * handle belongs to the form, so every part of one ragdoll wrote the same
                     * number — but a part with nothing to say wrote the silence marker in its
                     * place, and reading that as a handle would release a ragdoll nobody released. */
                    authority = cache.readAuthority(tick, part.channel);
                }

                recorded = true;
            }
        }

        this.recorded = recorded;

        this.state.setRecorded(recorded);
        this.state.setAuthority(authority, jumped);
    }

    /**
     * The frame {@link #read} expresses its answer against: the actor's placement times the model
     * form's own frame times the flip — the left-hand side of every bone's cache entry.
     */
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

    /**
     * Lets go of the form: the model goes back to being drawn from its keyframes alone. Called
     * when the scene closes — the bodies behind this ragdoll are about to stop existing.
     */
    public void release()
    {
        FormRagdolls.setState(this.form, null);
    }

    /**
     * One marked bone as a ragdoll part: who it is, the body following it, its filter subgroup, and
     * its slot in the film's recording.
     */
    private record Part(String bone, String path, int id, Body body, int sub, int channel)
    {}
}
