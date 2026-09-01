package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Everything simulated for one actor, driven together because it all reads one pose.
 *
 * <p>The rigs are in the order they were built in, and that order is load bearing: an actor's
 * kinematic bones go first, then its ragdolls, then everything that might be pinned to a ragdolled
 * bone — a crate in a fallen hand, a cape on a fallen shoulder, hair on a fallen head. Driven the
 * other way round, each of those would follow the previous tick's fall.</p>
 */
public final class SceneActor
{
    private final IEntity entity;

    /** What is simulated for this actor, in build order — see the class note on why that matters. */
    private final List<SceneRig> rigs;

    /**
     * Who among this actor's bodies is excused from colliding with whom. Native and held by Jolt by
     * pointer, so it lives here for as long as the bodies do rather than being dropped once the
     * scene is assembled.
     */
    private final ActorCollisionGroup group;

    /** What the rigs are told each tick — one object, refilled rather than built per tick. */
    private final RigUpdate update;

    /**
     * Whether this actor's last evaluation failed, so the failure is reported once instead of sixty
     * times a second. The usual cause is a model that has not loaded yet — BBS's matrix walk trips
     * over body parts when the animator is not there — and it clears itself once the model arrives.
     */
    private boolean broken;

    public SceneActor(IEntity entity, List<SceneRig> rigs, ActorCollisionGroup group, RigUpdate update)
    {
        this.entity = entity;
        this.rigs = rigs;
        this.group = group;
        this.update = update;

        update.pinned = pinned(rigs);
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
     * <p>One walk per actor per tick: every rig reads the same {@code MatrixCache}, and the walk
     * fills each physics body's parent frame through its renderer on the way.</p>
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
            MatrixCache matrices = FilmScene.evaluatePose(this.entity, root);
            Matrix4f actorWorld = scene.actorWorld(this.entity);

            this.update.on(matrices, actorWorld, reset);

            for (SceneRig rig : this.rigs)
            {
                rig.update(this.update);
            }

            this.broken = false;
        }
        catch (Throwable e)
        {
            if (!this.broken)
            {
                this.broken = true;

                BBSPhysics.LOGGER.warn("An actor's pose could not be evaluated for physics; its bodies hold still until it recovers.", e);
            }
        }
    }

    /** Writes every rig's answer for the tick that has just been simulated. */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        for (SceneRig rig : this.rigs)
        {
            rig.record(physics, scene, cache, tick);
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
        for (SceneRig rig : this.rigs)
        {
            rig.release();
        }
    }

    /** Whether anything here hangs off a ragdolled bone — see {@link SceneRig#readsBoneDeltas()}. */
    private static boolean pinned(List<SceneRig> rigs)
    {
        for (SceneRig rig : rigs)
        {
            if (rig.readsBoneDeltas())
            {
                return true;
            }
        }

        return false;
    }
}
