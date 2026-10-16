package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.engine.RigMemory;
import org.joml.Matrix4f;
import wemppy.bbs_physics.forms.FormTreeWalk;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;

import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.forms.ModelForm;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsAnchor;
import wemppy.bbs_physics.ragdoll.FormRagdolls;
import java.util.Collections;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;

/**
 * Everything simulated for one actor, driven together because it all reads one pose.
 *
 * <p>Recording follows the form tree: a parent's physical frame must be available before a
 * child's world result can be converted to local space. Within a model, the body comes before
 * the ragdoll, and the ragdoll before its hair.</p>
 */
public final class SceneActor
{
    private static final Map<IEntity, SceneActor> LIVE = new IdentityHashMap<>();
    private final IEntity entity;

    /** What is simulated for this actor, in parent-first recording order. */
    private final List<SceneRig> rigs;

    /**
     * Who among this actor's bodies is excused from colliding with whom. Native and held by Jolt by
     * pointer, so it lives here for as long as the bodies do rather than being dropped once the
     * scene is assembled.
     */
    private final ActorCollisionGroup group;

    /** What the rigs are told each tick — one object, refilled rather than built per tick. */
    private final RigUpdate update;
    private final Map<SceneRig, PoseEvaluation> evaluations = new IdentityHashMap<>();

    /**
     * Each rig's walk from the pass before the step, kept for the rest of that step — see
     * {@link #plan}.
     */
    private final Map<SceneRig, Walk> walks = new IdentityHashMap<>();

    /** Whether a step may drive with the walks it took before the step — see {@link #plan}. */
    private boolean steady;

    /** The rigs whose walk after the step is the same as before it — see {@link #plan}. */
    private final Set<SceneRig> settled = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Whether this actor's last evaluation failed, so the failure is reported once instead of sixty
     * times a second. The usual cause is a model that has not loaded yet — BBS's matrix walk trips
     * over body parts when the animator is not there — and it clears itself once the model arrives.
     */
    private boolean broken;

    public SceneActor(IEntity entity, List<SceneRig> rigs, ActorCollisionGroup group, RigUpdate update)
    {
        this.entity = entity;
        this.rigs = new ArrayList<>(rigs);
        this.group = group;
        this.update = update;
        LIVE.put(entity, this);

        Map<Form, Integer> order = new IdentityHashMap<>();
        FormTreeWalk.walk(entity.getForm(), (form, path, anchor) ->
        {
            order.put(form, order.size());
            return true;
        });
        this.rigs.sort(Comparator.comparingInt((SceneRig rig) -> order.getOrDefault(rig.getForm(), -1))
            .thenComparingInt(rig -> rig.poseKind().ordinal()));

        for (SceneRig rig : this.rigs)
        {
            this.evaluations.put(rig, new PoseEvaluation(rig.getForm(), rig.poseKind()));
        }
    }

    public IEntity getEntity()
    {
        return this.entity;
    }

    public List<SceneRig> getRigs()
    {
        return this.rigs;
    }

    /**
     * Works out how much of a step's pose work can be done once.
     *
     * <p>A step walks every rig's pose three times: before the step, to stage the parents; to drive
     * the rig; after the step, to record it under its parents as they fell. A walk is the actor's
     * whole form tree through BBS's pose pipeline — the dearest thing in a step — and often the three
     * come out the same. Nothing a walk reads changes between the first two: the pass before the step
     * publishes the very slots the drive's walk would read, parents and anchors first, and the drives
     * only steer bodies. Between the drive and the recording the solver runs, so the third is the same
     * only for a rig that inherits nothing that falls: no simulated form above it, and nothing simulated
     * on its own form that its pose takes in (see {@link PoseEvaluation#allows}).</p>
     *
     * <p>An actor that hangs off another actor shares nothing: its placement goes through that
     * actor's pose, and through the release of its own anchor, which reads what its own bodies just
     * published. Its walks are taken three times, as they always were.</p>
     *
     * @param anchored whether this actor hangs off another at any point of the film
     */
    void plan(boolean anchored)
    {
        this.walks.clear();
        this.settled.clear();
        this.steady = !anchored;

        if (anchored)
        {
            return;
        }

        Map<Form, String> paths = new IdentityHashMap<>();

        FormTreeWalk.walk(this.entity.getForm(), (form, path, bone) ->
        {
            paths.put(form, path);
            return true;
        });

        for (SceneRig rig : this.rigs)
        {
            if (rig.getForm() != null && !this.inheritsPhysics(rig, paths))
            {
                this.settled.add(rig);
            }
        }
    }

    /** Whether anything simulated in this actor can move what {@code rig}'s walk reads. */
    private boolean inheritsPhysics(SceneRig rig, Map<Form, String> paths)
    {
        String path = paths.get(rig.getForm());

        if (path == null)
        {
            return true;
        }

        for (SceneRig other : this.rigs)
        {
            /* The remaining colliders publish nothing a walk could take in. */
            if (other == rig || other.getForm() == null)
            {
                continue;
            }

            if (other.getForm() == rig.getForm())
            {
                if (takesIn(rig.poseKind(), other.poseKind()))
                {
                    return true;
                }

                continue;
            }

            String above = paths.get(other.getForm());

            if (above == null || (above.isEmpty() ? !path.isEmpty() : path.startsWith(above + "/")))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Whether a walk for a rig of kind {@code kind} takes in the simulated pose of {@code component}
     * on its own form — the rule {@link PoseEvaluation#allows} applies to the target itself.
     */
    private static boolean takesIn(PoseEvaluation.Kind kind, PoseEvaluation.Kind component)
    {
        return kind != PoseEvaluation.Kind.BODY && (component == PoseEvaluation.Kind.BODY
            || kind == PoseEvaluation.Kind.CHAIN && component == PoseEvaluation.Kind.RAGDOLL);
    }

    /**
     * Evaluates this actor's pose at the tick being simulated and drives everything hanging off it.
     *
     * <p>Each rig samples its own animation under the physical poses of its parents. The same
     * walk captures the parent frame used to convert its result back to local space.</p>
     *
     * @param reset whether the scene is starting over, in which case every body is stood at its
     *              animated pose and stopped rather than steered towards it
     */
    public void drive(FilmScene scene, boolean reset)
    {
        Form root = this.entity.getForm();

        if (root == null)
        {
            return;
        }

        try
        {
            for (SceneRig rig : this.rigs)
            {
                if (reset || !this.reuse(scene, rig))
                {
                    this.sample(scene, rig, reset);
                }

                rig.update(this.update);
            }

            this.broken = false;
        }
        catch (Throwable e)
        {
            this.failed(e);
        }
    }

    /**
     * Writes every rig's answer for {@code tick}: before the step, the staging the drives stand on;
     * after it, the recording itself.
     *
     * @param staging whether this is the pass before the step, whose walks the step keeps
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, PhysicsCache pending, int tick, boolean staging)
    {
        for (SceneRig rig : this.rigs)
        {
            if (rig.getForm() == null)
            {
                continue;
            }

            /* Parents have already published this tick. Re-read their frame after the solver,
             * not the pre-step frame the drive used, then publish this child for its children —
             * unless nothing the walk reads can have fallen (see plan). */
            try
            {
                if (staging)
                {
                    this.sample(scene, rig, false);
                    this.keep(scene, rig);
                }
                else if (!this.settled.contains(rig) || !this.reuse(scene, rig))
                {
                    this.sample(scene, rig, false);
                }
            }
            catch (Throwable e)
            {
                this.failed(e);
                /* beginFrame cleared this rig's channels. Publish silence too, so a child
                 * cannot inherit stale output from a model that has not loaded yet. */
                rig.readCache(pending, tick, true);
                continue;
            }

            rig.captureFrame(this.update);
            rig.record(physics, scene, cache, tick);
            rig.readCache(pending, tick, true);
        }
    }

    private void failed(Throwable e)
    {
        if (!this.broken)
        {
            this.broken = true;
            BBSPhysics.LOGGER.warn("An actor's pose could not be evaluated for physics; its bodies hold still until it recovers.", e);
        }
    }

    private void sample(FilmScene scene, SceneRig rig, boolean reset)
    {
        this.sample(scene, rig, reset, 1F, true);
    }

    /** Keeps the walk just taken for {@code rig} for the rest of this step. */
    private void keep(FilmScene scene, SceneRig rig)
    {
        if (!this.steady)
        {
            return;
        }

        Walk walk = this.walks.computeIfAbsent(rig, (key) -> new Walk());

        walk.step = scene.stepIndex();
        walk.matrices = this.update.matrices;
        walk.actorWorld.set(this.update.actorWorld);
        walk.body = PhysicsForms.getState(rig.getForm());

        if (walk.body != null)
        {
            walk.parentFrame.set(walk.body.getWalkParentFrame());
        }
    }

    /**
     * Hands {@code rig} the walk kept for this step instead of taking it again — including the parent
     * frame the walk left on the form's body state, which the rig reads right after it.
     *
     * @return false when there is no walk of this step to hand over
     */
    private boolean reuse(FilmScene scene, SceneRig rig)
    {
        Walk walk = this.steady ? this.walks.get(rig) : null;

        if (walk == null || walk.step != scene.stepIndex())
        {
            return false;
        }

        this.update.on(walk.matrices, walk.actorWorld, false);

        if (walk.body != null)
        {
            walk.body.captureWalkParentFrame(walk.parentFrame);
        }

        return true;
    }

    /** One rig's walk, as {@link #keep} kept it. */
    private static final class Walk
    {
        private long step = Long.MIN_VALUE;
        private MatrixCache matrices;
        private final Matrix4f actorWorld = new Matrix4f();
        private final Matrix4f parentFrame = new Matrix4f();
        private PhysicsBodyState body;
    }

    private void sample(FilmScene scene, SceneRig rig, boolean reset, float transition, boolean anchored)
    {
        Form root = this.entity.getForm();
        FilmScene.ensureAnimators(root);
        boolean evaluating = RagdollPoseApplier.isEvaluating();

        try (PoseEvaluation ignored = this.evaluations.get(rig).enter())
        {
            RagdollPoseApplier.setEvaluating(reset);
            MatrixCache matrices = FormUtilsClient.getRenderer(root).collectMatrices(this.entity, transition);
            Matrix4f actorWorld = scene.actorWorld(this.entity, transition, anchored);
            this.update.on(matrices, actorWorld, reset);
        }
        finally
        {
            RagdollPoseApplier.setEvaluating(evaluating);
        }
    }

    /** Detaching must not pull a partially released body towards the actor's unrelated free pose. */
    public static Anchor releaseAnchor(Anchor anchor)
    {
        if (anchor == null || !anchor.isFadeOut()) return anchor;
        for (SceneActor actor : LIVE.values())
        {
            Form root = actor.entity.getForm();
            if (root == null || FilmMatrices.getAnchor(actor.entity) != anchor) continue;
            var body = PhysicsForms.getState(root);
            var ragdoll = root instanceof ModelForm model ? FormRagdolls.getState(model) : null;
            boolean physical = body != null && body.isSimulated() || ragdoll != null && ragdoll.isActive();
            float authority = PhysicsForms.getAuthority(root);
            if (physical && authority > 0F && authority < 1F)
            {
                return PhysicsAnchor.release(anchor, authority);
            }
        }
        return anchor;
    }

    /** Prepare anchor dependencies before the actor, including actors without a physics modifier. */
    public static void prepareRender(IEntity entity, Map<String, IEntity> entities, float transition)
    {
        prepareRender(entity, entities, transition, true);
    }

    public static void prepareRender(IEntity entity, Map<String, IEntity> entities, float transition, boolean anchored)
    {
        prepareRender(entity, entities, transition, anchored, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void prepareRender(IEntity entity, Map<String, IEntity> entities, float transition,
        boolean anchored, Set<IEntity> visited)
    {
        if (entity == null || entity.getForm() == null || !visited.add(entity)) return;
        var anchor = anchored ? FilmMatrices.getAnchor(entity) : null;
        if (anchor != null)
        {
            prepareRender(entities.get(anchor.replay), entities, transition, true, visited);
            if (anchor.previous != null) prepareRender(entities.get(anchor.previous.replay), entities, transition, true, visited);
        }
        SceneActor actor = LIVE.get(entity);
        if (actor == null) return;
        try
        {
            for (SceneRig rig : actor.rigs)
            {
                if (!rig.needsRenderFrame()) continue;
                actor.sample(actor.update.scene, rig, false, transition, anchored);
                rig.renderFrame(actor.update);
            }
        }
        catch (Throwable e)
        {
            actor.failed(e);
        }
    }

    /** Writes every rig's memory for a checkpoint, in the order {@link #recall} reads it back. */
    public void remember(RigMemory memory)
    {
        for (SceneRig rig : this.rigs)
        {
            rig.remember(memory);
        }
    }

    /** Puts every rig back to what {@link #remember} wrote, before the world itself is restored. */
    public void recall(RigMemory memory, PhysicsWorld physics)
    {
        for (SceneRig rig : this.rigs)
        {
            rig.recall(memory, physics);
        }
    }

    /** Hands every rig the frame recorded for {@code tick}, or the news that there is not one. */
    public void readCache(PhysicsCache cache, int tick, boolean jumped)
    {
        for (SceneRig rig : this.rigs)
        {
            rig.readCache(cache, tick, jumped);
        }
    }

    /** Lets go of every form this actor's rigs claimed — the scene is closing. */
    public void release()
    {
        LIVE.remove(this.entity, this);
        for (SceneRig rig : this.rigs)
        {
            rig.release();
        }
    }

}
