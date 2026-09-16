package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;
import wemppy.bbs_physics.forms.FormTreeWalk;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;

import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.forms.ModelForm;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsAnchor;
import wemppy.bbs_physics.ragdoll.FormRagdolls;
import java.util.Collections;
import java.util.Set;
import io.netty.util.collection.IntObjectMap;
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
                this.sample(scene, rig, reset);
                rig.update(this.update);
            }

            this.broken = false;
        }
        catch (Throwable e)
        {
            this.failed(e);
        }
    }

    /** Writes every rig's answer for the tick that has just been simulated. */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, PhysicsCache pending, int tick)
    {
        for (SceneRig rig : this.rigs)
        {
            if (rig.getForm() == null)
            {
                continue;
            }

            /* Parents have already published this tick. Re-read their frame after the solver,
             * not the pre-step frame the drive used, then publish this child for its children. */
            try
            {
                this.sample(scene, rig, false);
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
        if (!anchor.isFadeOut()) return anchor;
        for (SceneActor actor : LIVE.values())
        {
            Form root = actor.entity.getForm();
            if (root == null || root.anchor.get() != anchor) continue;
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
    public static void prepareRender(IEntity entity, IntObjectMap<IEntity> entities, float transition)
    {
        prepareRender(entity, entities, transition, true);
    }

    public static void prepareRender(IEntity entity, IntObjectMap<IEntity> entities, float transition, boolean anchored)
    {
        prepareRender(entity, entities, transition, anchored, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void prepareRender(IEntity entity, IntObjectMap<IEntity> entities, float transition,
        boolean anchored, Set<IEntity> visited)
    {
        if (entity == null || entity.getForm() == null || !visited.add(entity)) return;
        var anchor = entity.getForm().anchor.get();
        prepareRender(entities.get(anchor.replay), entities, transition, true, visited);
        if (anchor.previous != null) prepareRender(entities.get(anchor.previous.replay), entities, transition, true, visited);
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
