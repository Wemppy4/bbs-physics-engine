package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.FixedConstraintSettings;
import com.github.stephengold.joltjni.GroupFilterTable;
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
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollJoint;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.AxisAngle4f;
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
 * scene build and living as long as the world, which keeps every checkpoint restorable. Adjacent
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
 * world's problem. Muting IK on ragdolled bones costs nothing here: the pose the parts are pulled
 * towards already includes IK, and the drawn pose below 1 is these bodies, not the solvers.</p>
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

    private final ModelForm form;
    private final String formPath;
    private final List<Part> parts = new ArrayList<>();
    private final RagdollState state = new RagdollState();

    /**
     * Native objects Jolt holds by pointer: the group filter the parts' collision groups point at,
     * and the joints themselves. Kept in fields so the Java side cannot collect them while the
     * world still uses them; they die with the world.
     */
    private final GroupFilterTable filter;
    private final List<TwoBodyConstraint> joints = new ArrayList<>();

    /** Whether the parts are currently kinematic — the cached side of the motion type switch. */
    private boolean kinematic = true;

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
    private final AxisAngle4f axisAngle = new AxisAngle4f();
    private final Vec3 linear = new Vec3();
    private final Vec3 angular = new Vec3();
    private final Matrix4f poseFrame = new Matrix4f();

    private ActorRagdoll(ModelForm form, String formPath, GroupFilterTable filter)
    {
        this.form = form;
        this.formPath = formPath;
        this.filter = filter;
    }

    /**
     * Builds a ragdoll for one ragdoll-enabled model form, or returns null when there is nothing
     * to build — no marked bones, or a model type the pose cannot be handed back to.
     *
     * @param pieces   the marked-up bone slots belonging to this form, already collected
     * @param matrices the actor's pose at scene build — the placement the bodies start from
     * @param group    a collision group id no other ragdoll in this scene uses
     */
    public static ActorRagdoll build(PhysicsWorld physics, ModelForm form, String formPath, List<CollisionCollector.Piece> pieces, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, int group)
    {
        ModelInstance instance = ModelFormRenderer.getModel(form);
        Model model = instance != null && instance.model instanceof Model cubic ? cubic : null;

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
        ActorRagdoll ragdoll = new ActorRagdoll(form, formPath, new GroupFilterTable(pieces.size()));
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

            /* Same group: parts of one ragdoll ask the filter, which excuses only joined
             * neighbours. Parts of different ragdolls have different group ids and collide
             * normally — two fallen characters land on each other, not through. */
            int sub = ragdoll.parts.size();

            settings.setCollisionGroup(new CollisionGroup(ragdoll.filter, group, sub));

            /* Dynamic-capable from birth even though it starts kinematic: the body has to carry
             * proper mass and inertia for the moment the handle lets it go. Jolt weighs the shape
             * by volume at water's density, which for body parts is the right ballpark. */
            Body body = bodies.createBody(settings);

            bodies.addBody(body.getId(), EActivation.Activate);

            Part part = new Part(piece.label(), piece.path(), body.getId(), body, sub);

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

        /* The joints. Each part hangs off its nearest marked ancestor — bones between them that
         * nobody marked are skipped over, the same way the eye skips them: LeftFoot joins LeftLeg
         * whether or not the shin was worth a collider. */
        for (Part part : ragdoll.parts)
        {
            Part parent = nearestMarkedAncestor(groups.get(part.bone), byBone);

            if (parent == null)
            {
                continue;
            }

            RagdollJoint joint = config.get(part.bone);
            TwoBodyConstraintSettings settings = ragdoll.jointSettings(joint, part, parent, groups, matrices, actorWorld);

            if (settings == null)
            {
                continue;
            }

            TwoBodyConstraint constraint = settings.create(parent.body, part.body);

            physics.getSystem().addConstraint(constraint);
            ragdoll.joints.add(constraint);

            /* Neighbours share a joint; their shapes meet at it by design and always will. Letting
             * them also collide would have every joint permanently fighting its own limits. */
            ragdoll.filter.disableCollision(part.sub, parent.sub);
        }

        FormRagdolls.setState(form, ragdoll.state);

        return ragdoll;
    }

    /** Walks up the model's bone tree to the first ancestor that is itself a ragdoll part. */
    private static Part nearestMarkedAncestor(ModelGroup group, Map<String, Part> byBone)
    {
        ModelGroup parent = group == null ? null : group.parent;

        while (parent != null)
        {
            Part part = byBone.get(parent.id);

            if (part != null)
            {
                return part;
            }

            parent = parent.parent;
        }

        return null;
    }

    /**
     * One joint, set up in world space at the build pose — the bodies are already standing on it,
     * so Jolt converts to each body's local frame correctly on its own.
     */
    private TwoBodyConstraintSettings jointSettings(RagdollJoint joint, Part part, Part parent, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry entry = matrices.get(part.path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        this.worldMatrix.set(actorWorld).mul(entry.matrix());
        this.worldMatrix.getTranslation(this.translation);

        /* The joint sits at the child bone's pivot: the elbow is where the forearm turns. Scene
         * coordinates, like everything in the world. */
        RVec3 point = new RVec3(this.translation.x, this.translation.y, this.translation.z);

        switch (joint.kind())
        {
            case FREE:
                return null;

            case FIXED:
            {
                FixedConstraintSettings fixed = new FixedConstraintSettings();

                fixed.setAutoDetectPoint(true);

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
                this.drive(bodies, part.id, authority);
            }
        }

        /* Below a full 1 the drawn pose is the simulation's; at 1 the animation draws itself and
         * the bodies merely stand on it, which is the smoother path while it is in charge. */
        this.state.setActive(authority < 1F);
    }

    /**
     * The velocity blend, verbatim from {@link PhysicsBodyRig}: the part is offered the velocity
     * that would carry it to its keyframed place over one tick, mixed with what it already has in
     * the handle's proportion. Mixing velocities rather than writing them is what keeps gravity in
     * the picture — a weakly animated limb sags instead of hovering.
     */
    private void drive(BodyInterface bodies, int id, float authority)
    {
        bodies.getPositionAndRotation(id, this.currentPosition, this.currentRotation);

        Vec3 velocity = bodies.getLinearVelocity(id);
        Vec3 spin = bodies.getAngularVelocity(id);

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

        this.axisAngle.set(this.delta);

        float speed = this.axisAngle.angle / PhysicsWorld.TICK;

        this.angular.set(
            mix(spin.getX(), this.axisAngle.x * speed, authority),
            mix(spin.getY(), this.axisAngle.y * speed, authority),
            mix(spin.getZ(), this.axisAngle.z * speed, authority));

        bodies.setLinearAndAngularVelocity(id, this.linear, this.angular);

        /* A sleeping body ignores handed velocities, and a pulled limb that rested for a moment
         * would never pick its animation back up. */
        bodies.activateBody(id);
    }

    private static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }

    /**
     * Runs after the world stepped: reads where the simulation put every part and hands it to the
     * renderer, expressed in the model's own group space so the renderer can substitute it without
     * knowing where the actor stands.
     *
     * <p>Written every tick, the kinematic ones included — the tick the handle drops below 1 needs
     * the tick before it to interpolate from, or the release visibly jumps (the same lesson
     * {@link PhysicsBodyRig#read} carries).</p>
     */
    public void read(PhysicsWorld physics, FilmScene scene, boolean teleport)
    {
        if (!this.baseValid)
        {
            return;
        }

        BodyInterface bodies = physics.getBodies();

        for (Part part : this.parts)
        {
            bodies.getPositionAndRotation(part.id, this.scratchPosition, this.scratchRotation);

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

            this.state.set(part.bone, this.translation, this.orientation, teleport);
        }
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
     * Re-captures the answer frame without touching the bodies — for the one seek that simulates
     * nothing (a rewind landing exactly on a checkpoint), where no step ran to refresh it and the
     * bones would otherwise be drawn against where the actor stood ticks ago.
     */
    public void refresh(MatrixCache matrices, Matrix4f actorWorld)
    {
        this.captureBase(matrices, actorWorld);
    }

    /** Pins the drawn pose where it is, for a film that is not advancing. */
    public void freeze()
    {
        this.state.freeze();
    }

    /**
     * Lets go of the form: the model goes back to being drawn from its keyframes alone. Called
     * when the scene closes — the bodies behind this ragdoll are about to stop existing.
     */
    public void release()
    {
        FormRagdolls.setState(this.form, null);
    }

    /** One marked bone as a ragdoll part: who it is, the body following it, its filter subgroup. */
    private record Part(String bone, String path, int id, Body body, int sub)
    {}
}
