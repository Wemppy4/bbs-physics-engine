package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.actions.ImpulseActionClip;
import mchorse.bbs_physics.actions.TearActionClip;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * The physics action clips of a film — the Э5 pushes and tears — fired into the simulation.
 *
 * <p>They go off <em>inside the recording</em>, once per simulated tick, and that is what keeps them
 * deterministic: a re-recording replays the same clip on the same tick and the film comes out the
 * same. They are also applied <em>after</em> the tick's drives, deliberately — a drive writes a
 * body's velocity outright, so a push applied before it would be erased in the same tick it was
 * given. Landing on top, the push survives, and the next tick's drive mixes it away by the handle's
 * proportion, which is the muscles resisting the blast and exactly what partial authority means.</p>
 */
public final class SceneClips
{
    private final FilmScene scene;

    /**
     * Bone names a tear clip asked for that no ragdoll of the actor has, so the fact is reported
     * once per name rather than on every tick of every re-recording.
     */
    private final Set<String> warnedTears = new HashSet<>(0);

    public SceneClips(FilmScene scene)
    {
        this.scene = scene;
    }

    /** Fires whatever sits on {@code tick}. The cast is already standing on it. */
    public void apply(SceneCast cast, int tick)
    {
        for (SceneCast.Member member : cast)
        {
            if (member.replay == null)
            {
                continue;
            }

            int local = member.replay.getTick(tick);

            for (Clip clip : member.replay.actions.getClips(local))
            {
                if (!fires(clip, local))
                {
                    continue;
                }

                if (clip instanceof ImpulseActionClip impulse)
                {
                    this.impulse(impulse);
                }
                else if (clip instanceof TearActionClip tear)
                {
                    this.tear(member, tear);
                }
            }
        }
    }

    /**
     * Whether an action clip goes off on this tick — the same rule {@code ActionClip} applies for
     * the server and client passes, repeated here because physics reads the clip directly: at its
     * first tick once, or every {@code frequency} ticks of its length when one is set.
     */
    private static boolean fires(Clip clip, int tick)
    {
        if (!(clip instanceof ActionClip action) || !clip.enabled.get())
        {
            return false;
        }

        int relative = tick - clip.tick.get();

        if (relative < 0)
        {
            /* The clip has not started. BBS's own passes cannot reach this — they only ever ask a
             * clip that covers the tick — but the list this reads also hands back "global" clips
             * whatever the tick, and a repeating clip's modulo says yes to a negative multiple just
             * as readily as to a positive one. A push before its own frame is not a thing. */
            return false;
        }

        int frequency = action.frequency.get();

        return frequency == 0 ? relative == 0 : relative % frequency == 0;
    }

    /**
     * One firing of an impulse clip: the push is worked out once and offered to everything simulated
     * in the scene — every actor's bodies, not only the clip's own. An explosion has no respect for
     * whose timeline it was authored on.
     */
    private void impulse(ImpulseActionClip clip)
    {
        Point point = clip.point.get();
        Point direction = clip.direction.get();

        SceneImpulse push = SceneImpulse.of(
            (float) (point.x - this.scene.getOriginX()),
            (float) (point.y - this.scene.getOriginY()),
            (float) (point.z - this.scene.getOriginZ()),
            clip.radius.get(),
            clip.strength.get(),
            clip.radial.get() ? null : new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));

        if (push == null)
        {
            return;
        }

        for (SceneActor actor : this.scene.getActors())
        {
            for (SceneRig rig : actor.getRigs())
            {
                rig.impulse(this.scene.getWorld(), push);
            }
        }
    }

    /**
     * One firing of a tear clip: the named bone of this clip's own actor comes off, with the kick
     * the author gave it. The actor's ragdolls are asked in order; the first that owns the bone
     * answers.
     */
    private void tear(SceneCast.Member member, TearActionClip clip)
    {
        String bone = clip.bone.get().trim();

        if (bone.isEmpty())
        {
            return;
        }

        Point direction = clip.direction.get();
        Vector3f kick = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);

        if (kick.lengthSquared() > 1.0e-12F && kick.isFinite())
        {
            kick.normalize().mul(clip.strength.get());
        }
        else
        {
            kick.zero();
        }

        for (SceneActor actor : this.scene.getActors())
        {
            if (actor.getEntity() != member.entity)
            {
                continue;
            }

            for (SceneRig rig : actor.getRigs())
            {
                if (rig instanceof RagdollRig ragdoll && ragdoll.tear(this.scene.getWorld(), bone, kick.x, kick.y, kick.z))
                {
                    return;
                }
            }
        }

        /* Nobody owns that bone. Said out loud once per name, because the alternative is the clip
         * doing nothing at all with no explanation — and the name is typed by hand, so the usual
         * cause is a bone that is spelled differently, unmarked in the collision tab, or ticked out
         * of the ragdoll. Exactly the kind of silence the impulse clip's point already cost a live
         * run over. */
        if (this.warnedTears.add(bone))
        {
            BBSPhysics.LOGGER.warn("A tear clip names the bone '{}', which is not a ragdoll part of that actor; nothing comes off. Check the spelling, that the bone is marked up in the Collision tab, and that it is ticked on in the ragdoll modifier.", bone);
        }
    }
}
