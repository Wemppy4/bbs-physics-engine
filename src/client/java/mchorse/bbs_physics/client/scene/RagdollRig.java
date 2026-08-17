package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
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
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.CollisionShapes;
import mchorse.bbs_physics.client.collision.JoltShapes;
import mchorse.bbs_physics.client.ragdoll.RagdollAttachment;
import mchorse.bbs_physics.client.ragdoll.RagdollJoints;
import mchorse.bbs_physics.client.ragdoll.RagdollWelds;
import mchorse.bbs_physics.engine.BodyDrive;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsMath;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollJoint;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>A bone that does not fall is not always a bone that stands still.</b> One left out of the
 * ragdoll underneath a falling bone — headwear on a head, a badge on a chest — is welded into that
 * bone's body rather than given one of its own: its shapes join the owner's compound and travel with
 * it, and the renderer carries the bone itself along for free, since a weld is only ever resolved
 * along the bone tree. See {@link RagdollWelds} for why the tree and nothing else decides that, and
 * why this is not a fixed joint. A bone with no falling bone above it is unaffected and stays the
 * kinematic body it always was — that is the torso walking on while the head comes off.</p>
 *
 * <p><b>The skeleton is held by Jolt's own joints</b> — {@code SwingTwistConstraint} for the cone,
 * {@code HingeConstraint} for knees and elbows, {@code FixedConstraint} for welds — created once at
 * scene build and living as long as the world, so the recording's shape never changes. Adjacent
 * parts are excused from colliding with each other through a group filter; everything else
 * collides normally, which is what keeps an arm from folding through the chest.</p>
 *
 * <p><b>The handle drives exactly like a physics body's.</b> At 1 the parts are kinematic bones —
 * the same thing {@link BoneRig} builds, shoving props and ignoring gravity. Below 1 they turn
 * dynamic and are pulled towards the animated pose by the same velocity blend
 * {@link BodyRig} was proven with: each tick every part is offered the velocity that would
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
public class RagdollRig implements SceneRig
{
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
     * The joints with both their ends, which Jolt holds by pointer. Kept in a field so the Java
     * side cannot collect them while the world still uses them; they die with the world. The ends
     * are kept because a tear has to reason about the whole neighbourhood of a bone — see
     * {@link #tear} for why "the bone's own joint" was not enough.
     */
    private final List<Joint> joints = new ArrayList<>();

    /**
     * Bones a tear clip has ripped off (Э5): the joints between each and the rest of the skeleton
     * are switched off and the part is dynamic whatever the handle says — a torn head cannot be
     * walked by keyframes it no longer follows. Grows during recording, emptied when the scene
     * starts over, which is the only way back on: the recording is linear, so "before the tear"
     * only ever exists as a re-recording.
     */
    private final Set<String> torn = new HashSet<>();

    /** The joints the tears switched off, so a restart can switch exactly those back on. */
    private final List<TwoBodyConstraint> severed = new ArrayList<>();

    /**
     * The model's bone tree, kept for one question a tear asks: is this neighbour a descendant of
     * the torn bone — hair on a head, riding along — or the rest of the character, being left.
     */
    private Map<String, ModelGroup> groups = Map.of();

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
    private final Vec3 linear = new Vec3();

    /** The velocity blend that pulls a part towards its pose — held, because it carries scratch. */
    private final BodyDrive drive = new BodyDrive();
    private final Matrix4f poseFrame = new Matrix4f();

    /* The two frames a bone's fall is measured between, and the weighted blend of them. Fields
     * because this runs per part per tick — see {@link #publish}. */
    private final Vector3f animatedPosition = new Vector3f();
    private final Vector3f simulatedPosition = new Vector3f();
    private final Vector3f blendedPosition = new Vector3f();
    private final Quaternionf animatedRotation = new Quaternionf();
    private final Quaternionf simulatedRotation = new Quaternionf();
    private final Quaternionf blendedRotation = new Quaternionf();

    private RagdollRig(ModelForm form, String formPath)
    {
        this.form = form;
        this.formPath = formPath;
    }

    /**
     * Whether a ragdoll can be built for this form at all — asked <em>before</em> its bone pieces
     * are claimed away from the kinematic rig, because a claim can no longer be undone once that
     * rig is built.
     */
    public static boolean supports(ModelForm form)
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

            return false;
        }

        if (!(instance.model instanceof Model))
        {
            BBSPhysics.LOGGER.warn("A ragdoll is enabled on '{}', but only cubic models can hand the simulated pose back to the renderer yet; its bones stay kinematic.", form.getDisplayName());

            return false;
        }

        return true;
    }

    /**
     * Builds a ragdoll for one ragdoll-enabled model form ({@link #supports} already said yes), or
     * returns null when none of its claimed pieces could become a body.
     *
     * @param pieces    the marked-up bone slots belonging to this form, already claimed — the
     *                  falling parts and the bones welded into them, together
     * @param welds     welded bone → the part it belongs to, as {@link RagdollWelds} resolved it. A
     *                  welded bone gets no body: its shapes join its owner's compound
     * @param kinematic the bone slots of this form the animation kept — already built into
     *                  {@code rig} — which a part with no falling parent can be jointed to
     * @param rig       the actor's kinematic bones, for looking those bodies up; may be null when
     *                  the actor has none
     * @param matrices  the actor's pose at scene build — the placement the bodies start from
     * @param group     the actor's collision group, shared with its kinematic bones and with any
     *                  other ragdoll hanging off the same actor
     */
    public static RagdollRig build(PhysicsWorld physics, ModelForm form, String formPath, List<CollisionCollector.Piece> pieces, Map<String, String> welds, List<CollisionCollector.Piece> kinematic, BoneRig rig, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, ActorCollisionGroup group)
    {
        ModelInstance instance = ModelFormRenderer.getModel(form);
        Model model = instance != null && instance.model instanceof Model cubic ? cubic : null;

        if (model == null || pieces.isEmpty())
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
        RagdollRig ragdoll = new RagdollRig(form, formPath);
        Map<String, Part> byBone = new HashMap<>();

        ragdoll.groups = groups;

        /* The welded bones, gathered under the part each of them belongs to, and the parts on their
         * own — the joint search must not see a welded bone, which has no body to be jointed to. */
        Map<String, List<CollisionCollector.Piece>> welded = new HashMap<>();
        List<CollisionCollector.Piece> partPieces = new ArrayList<>(pieces.size());

        for (CollisionCollector.Piece piece : pieces)
        {
            String owner = welds.get(piece.label());

            if (owner == null)
            {
                partPieces.add(piece);
            }
            else
            {
                welded.computeIfAbsent(owner, key -> new ArrayList<>(1)).add(piece);
            }
        }

        for (CollisionCollector.Piece piece : partPieces)
        {
            MatrixCacheEntry entry = matrices.get(piece.path());

            if (entry == null || entry.matrix() == null || !groups.containsKey(piece.label()))
            {
                continue;
            }

            /* Everything this part collides as: its own markup, plus the markup of every bone nailed
             * to it, carried into its frame. */
            List<CollisionShapes.SubShape> shapes = weld(piece, welded.get(piece.label()), entry, matrices);
            ConstShape shape = JoltShapes.build(shapes);

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

            debug.addShapes(shapes);
            scene.addDebugBody(debug);
        }

        if (ragdoll.parts.isEmpty())
        {
            return null;
        }

        /* Who hangs off whom — the three-step answer, shared with the viewport preview so that the
         * lines an author sees are the joints that will actually be built (§7.6). The bones the
         * animation kept are in the candidate list too: a part whose tree parent is not falling —
         * "the ragdoll is only on the head" — attaches to that kinematic bone and dangles from the
         * walking body instead of dropping free. */
        List<CollisionCollector.Piece> candidates = new ArrayList<>(partPieces);

        candidates.addAll(kinematic);

        Map<String, String> attachment = RagdollAttachment.resolve(config, candidates, model, matrices, actorWorld);
        Map<String, CollisionCollector.Piece> kinematicByBone = new HashMap<>();

        for (CollisionCollector.Piece piece : kinematic)
        {
            kinematicByBone.put(piece.label(), piece);
        }

        for (Part part : ragdoll.parts)
        {
            String parentBone = attachment.get(part.bone);
            Part parent = byBone.get(parentBone);
            CollisionCollector.Piece kinematicParent = parent == null ? kinematicByBone.get(parentBone) : null;
            BoneRig.Part rigParent = kinematicParent == null || rig == null ? null : rig.find(kinematicParent.path());

            if (parent == null && rigParent == null)
            {
                continue;
            }

            RagdollJoint joint = config.get(part.bone);
            String parentPath = parent != null ? parent.path : kinematicParent.path();
            TwoBodyConstraintSettings settings = RagdollJoints.build(joint, part.bone, part.path, parentBone, parentPath,
                formPath, groups, matrices, actorWorld, scene);

            if (settings == null)
            {
                continue;
            }

            TwoBodyConstraint constraint = settings.create(parent != null ? parent.body : rigParent.body(), part.body);

            physics.getSystem().addConstraint(constraint);
            ragdoll.joints.add(new Joint(part.bone, parentBone, constraint));

            /* Neighbours share a joint; their shapes meet at it by design and always will. Letting
             * them also collide would have every joint permanently fighting its own limits. A
             * kinematic parent needs the excuse more than anyone: it cannot give way, so the
             * overlap at the joint would otherwise shove the hanging part out every step. */
            group.excuse(part.sub, parent != null ? parent.sub : rigParent.sub());
        }

        FormRagdolls.setState(form, ragdoll.state);

        return ragdoll;
    }

    /**
     * One part's collision: its own shapes, plus the shapes of every bone welded into it, carried
     * from each of their frames into this part's.
     *
     * <p>Their placement is read once, at the pose the scene is built on, and that is the whole of
     * what a weld means: the bone keeps the position relative to its owner that it has here,
     * whatever the animation does with it afterwards. An author who animates a welded bone against
     * its parent will see the mesh move and the shape stay — but a bone animated against its parent
     * is not nailed to it, and the fix for that is to let it fall on its own joint.</p>
     */
    private static List<CollisionShapes.SubShape> weld(CollisionCollector.Piece piece, List<CollisionCollector.Piece> welded, MatrixCacheEntry own, MatrixCache matrices)
    {
        if (welded == null || welded.isEmpty())
        {
            return piece.shapes();
        }

        List<CollisionShapes.SubShape> shapes = new ArrayList<>(piece.shapes());
        Matrix4f inverse = new Matrix4f(own.matrix()).invert();

        for (CollisionCollector.Piece part : welded)
        {
            MatrixCacheEntry entry = matrices.get(part.path());

            if (entry == null || entry.matrix() == null)
            {
                continue;
            }

            Matrix4f relative = new Matrix4f(inverse).mul(entry.matrix());

            for (CollisionShapes.SubShape sub : part.shapes())
            {
                shapes.add(CollisionShapes.carry(sub, relative));
            }
        }

        return shapes;
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

    /**
     * Runs before the world steps: drives every part towards its animated pose by the handle, and
     * publishes how far each bone has actually been carried from it — see {@link #publish}, and
     * {@link SceneRig#readsBoneDeltas()} for who reads that.
     */
    @Override
    public void update(RigUpdate update)
    {
        PhysicsWorld physics = update.physics;
        FilmScene scene = update.scene;
        MatrixCache matrices = update.matrices;
        Matrix4f actorWorld = update.actorWorld;
        boolean reset = update.reset;
        Map<String, Matrix4f> deltas = update.pinned ? update.deltas : null;

        this.captureBase(matrices, actorWorld);

        BodyInterface bodies = physics.getBodies();

        /* Before anything else on a restart: the torn bones go back on, so the placement below
         * stands a whole character on its keyframes rather than one missing its head. */
        if (reset)
        {
            this.untear(bodies);
        }

        float authority = PhysicsForms.getAuthority(this.form);
        boolean wanted = authority >= 1F;
        boolean put = reset;

        if (wanted != this.kinematic)
        {
            for (Part part : this.parts)
            {
                /* A torn part answers to nobody: the animation taking the rest of the body back
                 * must not weld the head back onto the neck. */
                if (this.torn.contains(part.bone))
                {
                    continue;
                }

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

            /* A torn bone's authority is 0 whatever the form's handle says — the fall owns it from
             * the tear to the end of the recording. */
            boolean torn = this.torn.contains(part.bone);
            float effective = torn ? 0F : authority;

            this.worldMatrix.set(actorWorld).mul(entry.matrix());
            this.worldMatrix.getTranslation(this.translation);
            this.worldMatrix.getUnnormalizedRotation(this.orientation);

            this.scratchPosition.set(
                this.translation.x - scene.getOriginX(),
                this.translation.y - scene.getOriginY(),
                this.translation.z - scene.getOriginZ());
            this.scratchRotation.set(this.orientation.x, this.orientation.y, this.orientation.z, this.orientation.w);

            if (put && !torn)
            {
                bodies.setPositionAndRotation(part.id, this.scratchPosition, this.scratchRotation, EActivation.Activate);
                bodies.setLinearAndAngularVelocity(part.id, PhysicsMath.ZERO, PhysicsMath.ZERO);
            }
            else if (this.kinematic && !torn)
            {
                bodies.moveKinematic(part.id, this.scratchPosition, this.scratchRotation, PhysicsWorld.TICK);
            }
            else if (effective > 0F)
            {
                this.drive(bodies, part, effective);
            }

            this.publish(bodies, scene, part, deltas, effective, torn);
        }
    }

    /**
     * Rips {@code bone} off this ragdoll (Э5): every joint tying it to the rest of the skeleton is
     * switched off, the part goes dynamic whatever the handle says, and the given send-off is added
     * to whatever velocity it carried — the head of a running character flies out of the run, plus
     * the kick.
     *
     * <p><b>Every joint between what comes off and what stays — not "the bone's own joint".</b> Who
     * attaches to whom is the auto-attachment's decision, and its trunk is the bulkiest part —
     * which on Minecraft proportions is the <em>head</em> (8×8×8 beats the 8×12×4 torso). Tear
     * only its own upward link and a trunk bone has none: the kick landed, nothing broke, and the
     * whole character flew off after its own head — the first live run's exact report. So what
     * comes off is defined first — the bone and everything under it in the <em>bone tree</em>,
     * because "the hair is part of the head" is a fact of the model while the attachment graph
     * flips with cube volumes — and then every joint with one end inside that set and one end
     * outside is cut, whichever end happened to be called the parent. Hair on a torn head keeps
     * its joint to the head and flies with it; a strand the geometry pass jointed to the torso
     * instead loses that joint, rather than being left as a leash the head drags behind it.</p>
     *
     * <p><b>And everything in that set is released, not only the bone named.</b> The joint holding
     * the hair to the head is kept deliberately, and a kept joint with a kinematic body on one end
     * is a joint that wins: the hair, still glued to its keyframes, would haul the head back and
     * swing it around its own pivot instead of letting it fly. The kick stays on the bone the
     * author aimed at — the rest comes along the way anything attached does.</p>
     *
     * <p>Runs inside the recording, on the tick the tear clip fires, which is what makes it
     * deterministic: a re-recording replays the same clip on the same tick. The joint objects stay
     * owned and in the world — {@code setEnabled(false)} is the whole of the break — so the scene's
     * set of bodies and constraints never changes shape and nothing about the recording moves.</p>
     *
     * <p>The part stays excused from colliding with its former neighbours. Their shapes overlap at
     * the joint on the tick of the tear, and letting them collide right then would fire the head
     * off the depenetration instead of the author's kick.</p>
     *
     * @return whether this ragdoll has that bone as a part at all
     */
    public boolean tear(PhysicsWorld physics, String bone, float kickX, float kickY, float kickZ)
    {
        Part found = null;

        for (Part part : this.parts)
        {
            if (part.bone.equals(bone))
            {
                found = part;

                break;
            }
        }

        if (found == null)
        {
            return false;
        }

        BodyInterface bodies = physics.getBodies();

        for (Joint joint : this.joints)
        {
            /* What comes off is the torn bone and everything under it in the bone tree — the head
             * and its hair — so a joint breaks exactly when it crosses that line: one end inside,
             * one end out. Which end the attachment solver happened to call the parent says nothing
             * (its trunk is the bulkiest bone, and on Minecraft proportions that is the head), and
             * neither does whether the torn bone is one of the two ends: a strand of hair jointed
             * to the torso by geometry rather than to the head it grows from is a leash the head
             * would drag behind it. */
            boolean severs = this.comesOff(joint.child, bone) != this.comesOff(joint.parent, bone);

            if (severs && joint.constraint.getEnabled())
            {
                joint.constraint.setEnabled(false);
                this.severed.add(joint.constraint);
            }
        }

        /* And every one of them is let go, not only the bone named. A part below the torn one keeps
         * its joint on purpose so that it travels along — but a part still riding the animation at
         * the other end of that joint is immovable, so it would haul the torn bone straight back to
         * the keyframes and swing it about its own pivot instead of letting it fly. Same mistake
         * one level up as the joints had: the tear is about a piece of the character, never about a
         * single bone. */
        for (Part part : this.parts)
        {
            if (!this.comesOff(part.bone, bone))
            {
                continue;
            }

            this.torn.add(part.bone);

            bodies.setMotionType(part.id, EMotionType.Dynamic, EActivation.Activate);
            bodies.setObjectLayer(part.id, PhysicsLayers.MOVING);
        }

        if ((kickX != 0F || kickY != 0F || kickZ != 0F) && Float.isFinite(kickX) && Float.isFinite(kickY) && Float.isFinite(kickZ))
        {
            Vec3 velocity = bodies.getLinearVelocity(found.id);

            this.linear.set(velocity.getX() + kickX, velocity.getY() + kickY, velocity.getZ() + kickZ);
            bodies.setLinearVelocity(found.id, this.linear);
        }

        bodies.activateBody(found.id);

        return true;
    }

    /** Puts every torn bone back on — the scene is starting over, so the tears have not happened yet. */
    private void untear(BodyInterface bodies)
    {
        if (this.torn.isEmpty())
        {
            return;
        }

        /* Exactly the joints the tears switched off — not "the torn bones' joints", because a tear
         * severs by neighbourhood and the bookkeeping must match it break for break. */
        for (TwoBodyConstraint constraint : this.severed)
        {
            constraint.setEnabled(true);
        }

        this.severed.clear();

        for (Part part : this.parts)
        {
            if (!this.torn.contains(part.bone))
            {
                continue;
            }

            bodies.setMotionType(part.id, this.kinematic ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.Activate);
            bodies.setObjectLayer(part.id, this.kinematic ? PhysicsLayers.BONE : PhysicsLayers.MOVING);
        }

        this.torn.clear();
    }

    /**
     * Whether {@code bone} travels with {@code torn} when it comes off: the torn bone itself, and
     * everything below it in the model's bone tree.
     */
    private boolean comesOff(String bone, String torn)
    {
        return bone.equals(torn) || this.isTreeDescendant(bone, torn);
    }

    /**
     * Whether {@code bone} sits under {@code ancestor} in the model's own bone tree. The tree, not
     * the attachment graph, deliberately: attachment is a solver detail that flips with cube
     * volumes, while "the hair is part of the head" is a fact of the model.
     */
    private boolean isTreeDescendant(String bone, String ancestor)
    {
        ModelGroup group = this.groups.get(bone);
        ModelGroup parent = group == null ? null : group.parent;

        while (parent != null)
        {
            if (parent.id.equals(ancestor))
            {
                return true;
            }

            parent = parent.parent;
        }

        return false;
    }

    /**
     * An impulse clip's push (Э5), offered to every part: each dynamic one inside the radius takes
     * the velocity change {@code push} computes for its position. Kinematic parts ignore it — a
     * character at a full handle is the animation's, and the clip's radius is how an author aims
     * around one.
     */
    @Override
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        BodyInterface bodies = physics.getBodies();

        for (Part part : this.parts)
        {
            if (this.kinematic && !this.torn.contains(part.bone))
            {
                continue;
            }

            push.apply(bodies, part.id);
        }
    }

    /**
     * Says how far this bone has been carried from the pose the animation drew it in, as one
     * matrix: <em>where the body is</em> times the inverse of <em>where the keyframes put it</em>.
     *
     * <p>This exists because everything hanging off a fallen bone would otherwise stay behind.
     * A form is placed from the actor's pose walk, and that walk is deliberately run with the
     * ragdoll's substitution switched off — the simulation has to see plain animation, or the
     * parts chase their own output. Correct for the ragdoll, wrong for a cape pinned to its
     * shoulder: the sheet was simulated where the shoulder <em>would have been</em>, while the
     * renderer drew it where the shoulder actually is. Multiplying the form's animated frame by
     * this delta on the left swaps the animated bone out for the simulated one and leaves
     * everything below it — the body part's own transform, nested forms — untouched.</p>
     *
     * <p>Published only while the ragdoll is actually falling: at a handle of 1 the bodies are
     * kinematically glued to the animation, so the delta is the identity and saying so would be
     * one matrix multiply per hanger per tick to change nothing.</p>
     *
     * <p>The delta describes the <em>previous</em> step, which is the only pose that exists before
     * this one is solved. A tick of lag at 20 Hz, and the same lag the cloth proxies carry.</p>
     */
    private void publish(BodyInterface bodies, FilmScene scene, Part part, Map<String, Matrix4f> deltas, float authority, boolean torn)
    {
        /* A torn bone publishes even while the rest of the body is kinematic: hair pinned to a
         * head that has left the neck follows the head, not the animation of a body it is no
         * longer on. */
        if (deltas == null || (this.kinematic && !torn))
        {
            return;
        }

        bodies.getPositionAndRotation(part.id, this.currentPosition, this.currentRotation);

        double x = this.currentPosition.xx() + scene.getOriginX();
        double y = this.currentPosition.yy() + scene.getOriginY();
        double z = this.currentPosition.zz() + scene.getOriginZ();

        if (!PhysicsMath.finite(x) || !PhysicsMath.finite(y) || !PhysicsMath.finite(z))
        {
            /* A part the solver has lost says nothing rather than handing everything pinned to it
             * a frame made of infinities. */
            return;
        }

        /* this.worldMatrix still holds the animated frame this part was just driven towards. */
        this.worldMatrix.getTranslation(this.animatedPosition);
        this.worldMatrix.getUnnormalizedRotation(this.animatedRotation);

        this.simulatedPosition.set((float) x, (float) y, (float) z);
        this.simulatedRotation.set(
            this.currentRotation.getX(), this.currentRotation.getY(),
            this.currentRotation.getZ(), this.currentRotation.getW());

        /* Weighted exactly the way the renderer weighs the pose it substitutes: the handle is a
         * crossfade, not a switch (the Р9 feedback), so at 0.5 what is drawn is halfway between
         * animation and simulation. A delta taken from the simulation alone would place the sheet
         * somewhere the shoulder is not being drawn, and the cape would float away from a
         * character that is only half limp. At 0 and 1 this is the plain answer either way. */
        float weight = 1F - authority;

        this.animatedPosition.lerp(this.simulatedPosition, weight, this.blendedPosition);
        this.animatedRotation.slerp(this.simulatedRotation, weight, this.blendedRotation);

        /* Both frames taken as rigid — position and rotation, no scale. A model scaled in the film
         * carries that scale in its matrices, and dividing one scaled frame by another would
         * cancel it out of everything hanging below: the delta must move the sheet, not resize it.
         * Rigid on both sides leaves the scale where it was, in the form's own frame. */
        this.poseFrame.translationRotate(
            this.animatedPosition.x, this.animatedPosition.y, this.animatedPosition.z, this.animatedRotation).invert();

        deltas.computeIfAbsent(part.path, (key) -> new Matrix4f())
            .translationRotate(this.blendedPosition.x, this.blendedPosition.y, this.blendedPosition.z, this.blendedRotation)
            .mul(this.poseFrame);
    }

    /**
     * The velocity blend every driven body here uses — the part is offered the velocity that would
     * carry it to its keyframed place over one tick, mixed with what it already has in the handle's
     * proportion. The reasoning, and the NaN it is armoured against, live in {@link BodyDrive}.
     */
    private void drive(BodyInterface bodies, Part part, float authority)
    {
        if (this.drive.apply(bodies, part.id, this.scratchPosition, this.scratchRotation, authority))
        {
            return;
        }

        /* Whatever slipped through — a bone scaled to nothing turns the target rotation into NaN, a
         * broken pose does the same to the position — the part is better left falling free for a
         * tick than fed poison, and the log gets the numbers while they still mean something. */
        if (!this.misfed)
        {
            this.misfed = true;

            BBSPhysics.LOGGER.warn(
                "The drive for bone '{}' of a ragdoll on '{}' came out unusable — {}, handle {} — so the part falls free instead. The pose it is pulled towards is broken.",
                part.bone, this.form.getDisplayName(), this.drive.describe(), authority);
        }
    }


    /**
     * Runs right after the world stepped: reads where the simulation put every part, expresses it
     * in the model's own group space, and writes that into the recording under {@code tick}.
     *
     * <p>The conversion is done here rather than at draw time for the same reason
     * {@link BodyRig#record} does it: the frame it is expressed against is a function of the
     * tick, which has just been posed, so a recorded film draws with no pose evaluation at all.</p>
     *
     * <p>Written every tick, the kinematic ones included — the tick the handle drops below 1 needs
     * the tick before it to interpolate from, or the release visibly jumps. The authority is
     * recorded alongside, because whether the simulation owns the pose is part of what was true on
     * that tick, not something to be re-read off a form at draw time.</p>
     */
    @Override
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
        float authority = PhysicsForms.getAuthority(this.form);

        for (Part part : this.parts)
        {
            bodies.getPositionAndRotation(part.id, this.scratchPosition, this.scratchRotation);

            if (!PhysicsMath.finite(this.scratchPosition.xx()) || !PhysicsMath.finite(this.scratchPosition.yy()) || !PhysicsMath.finite(this.scratchPosition.zz())
                || !PhysicsMath.finite(this.scratchRotation.getX()) || !PhysicsMath.finite(this.scratchRotation.getY()) || !PhysicsMath.finite(this.scratchRotation.getZ()) || !PhysicsMath.finite(this.scratchRotation.getW()))
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

            /* The recording remembers a tear as the bone's own authority: 0 from the tick it came
             * off, whatever the form's handle was doing — which is also how the drawn frame knows
             * to draw the torn head from the simulation while the body walks its keyframes. */
            float effective = this.torn.contains(part.bone) ? 0F : authority;

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

            cache.write(tick, part.channel, this.translation, this.orientation, effective);
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
    @Override
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
                /* The authority is the bone's own now, not the form's: a torn head recorded 0 on
                 * every tick after the tear while the walking body recorded 1, and the renderer
                 * weighs each bone's substitution by its own number. Read only from a channel that
                 * actually answered — a silent one holds the marker, and reading that as a handle
                 * would release a ragdoll nobody released. */
                float partAuthority = cache.readAuthority(tick, part.channel);

                this.state.set(part.bone, this.translation, this.orientation, partAuthority, jumped);

                /* The form-wide answer is the loosest bone's, which is what gates whether the
                 * substitution walk runs at all: one torn head is reason enough. */
                authority = recorded ? Math.min(authority, partAuthority) : partAuthority;
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
    @Override
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

    /**
     * One joint with its two ends: the bone hanging ({@code child}) and what it hangs on
     * ({@code parent} — a fellow part or a kinematic bone). Which end is which is the attachment
     * solver's choice, which is exactly why a tear reads both.
     */
    private record Joint(String child, String parent, TwoBodyConstraint constraint)
    {}
}
