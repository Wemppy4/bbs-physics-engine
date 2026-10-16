package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.forms.FormTreeWalk;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Which of a film's replays the simulation reads: every actor it built a body, a ragdoll or a
 * collider for, and every actor those hang off — through an actor's anchor, a rope tied to a bone,
 * or an anchor in any track — followed as far as the chain goes.
 *
 * <p>Two things lean on the answer. A step stands only these actors on its tick ({@link
 * SceneCast#reach}), and an edit to the animation of any other actor leaves the recording standing
 * ({@link FilmScene#reads}). Both are only right if nothing is missed, so the anchors are read from
 * everywhere they can be — the actor's own value, every key of its anchor channel, every key of every
 * track that holds an anchor, and the ropes' own values — across the whole film, not just the tick
 * the scene happens to stand on. Generous is cheap: an actor read for nothing costs one placement per
 * step, as every actor used to.</p>
 *
 * <p>Not here: the solver target tracks, which aim a model's limbs at another actor. They read the
 * film's live entities, so the scene's own placement never applies them — they cannot see an actor
 * the step did or did not stand.</p>
 */
final class SceneReach
{
    private SceneReach()
    {}

    /** The ids of every replay the simulation reads on a step. */
    static Set<String> of(SceneCast cast, List<SceneActor> actors)
    {
        Map<String, SceneCast.Member> byId = new HashMap<>();

        for (SceneCast.Member member : cast)
        {
            if (member.replay != null)
            {
                byId.put(member.replay.getId(), member);
            }
        }

        Set<String> reached = new HashSet<>();
        ArrayDeque<SceneCast.Member> queue = new ArrayDeque<>();

        for (SceneActor actor : actors)
        {
            SceneCast.Member member = cast.find(actor.getEntity());

            if (member != null && member.replay != null && reached.add(member.replay.getId()))
            {
                queue.add(member);
            }
        }

        while (!queue.isEmpty())
        {
            targets(queue.poll(), (id) ->
            {
                SceneCast.Member target = byId.get(id);

                if (target != null && reached.add(id))
                {
                    queue.add(target);
                }
            });
        }

        return reached;
    }

    /**
     * Whether this actor hangs off another at any point of the film. Its pose then leans on another
     * actor's — which may be falling — so its walks are not shared across a step (see
     * {@link SceneActor#plan}).
     */
    static boolean anchored(SceneCast.Member member)
    {
        if (member.replay == null)
        {
            return false;
        }

        String self = member.replay.getId();
        boolean[] anchored = {false};

        actorAnchors(member.replay, (id) -> anchored[0] |= !id.equals(self));

        return anchored[0];
    }

    /** Every replay {@code member} can be tied to by any anchor it carries. */
    private static void targets(SceneCast.Member member, Consumer<String> out)
    {
        Replay replay = member.replay;

        actorAnchors(replay, out);

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            for (Keyframe<?> keyframe : channel.getKeyframes())
            {
                if (keyframe.getValue() instanceof Anchor anchor)
                {
                    anchor(anchor, out);
                }
            }
        }

        FormTreeWalk.walk(member.entity.getForm(), (form, path, bone) ->
        {
            if (form instanceof ChainForm chain)
            {
                anchor(chain.attach.get(), out);
                anchor(chain.attach.getOriginalValue(), out);
            }

            return true;
        });
    }

    /** The actor's own anchor: its value as it stands, as authored, and every key of its channel. */
    private static void actorAnchors(Replay replay, Consumer<String> out)
    {
        anchor(replay.anchor.get(), out);
        anchor(replay.anchor.getOriginalValue(), out);

        for (Keyframe<Anchor> keyframe : replay.keyframes.anchor.getKeyframes())
        {
            anchor(keyframe.getValue(), out);
        }
    }

    /** An anchor's target, and the one it is fading from. */
    private static void anchor(Anchor anchor, Consumer<String> out)
    {
        for (Anchor current = anchor; current != null; current = current.previous)
        {
            if (current.hasTarget())
            {
                out.accept(current.replay);
            }
        }
    }
}
