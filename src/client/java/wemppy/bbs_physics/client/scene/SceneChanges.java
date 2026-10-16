package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import wemppy.bbs_physics.engine.FilmPhysics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out the first tick an edit can change, so that everything recorded before it can stand.
 *
 * <p>It keeps a copy of what the recording was made from — every key and loop of every channel of the
 * replays a step reads, and every action clip of every replay — and compares the channel or the clips
 * an edit touched against that copy, bringing the copy up to date as it goes. A comparison rather
 * than a reading of the edit itself, because the edit says what changed and not what it was before:
 * a key dragged from tick 400 to tick 100 changes the film from 100, and so does one dragged the
 * other way, and only the copy knows which. Undo and redo come out right the same way.</p>
 *
 * <p>Anything else an edit can touch — a form, its physics, the film's own physics knobs, the list of
 * replays — changes the film from its first tick, and says so.</p>
 */
final class SceneChanges
{
    /** What {@link #affected} answers for an edit that changes nothing the simulation reads. */
    static final int NONE = Integer.MAX_VALUE;

    /** One key as it was: where it sits, whether it plays at all, and everything it says. */
    record Key(float tick, boolean enabled, BaseType data)
    {}

    /** A channel as it was: its keys in order, the key objects they were read from, and its loops. */
    private record Copy(List<Keyframe<?>> keyframes, List<Key> keys, List<KeyframeLoop> loops)
    {}

    /** One clip as it was: where it starts, and everything it says. */
    private record Shot(int tick, BaseType data)
    {}

    private Map<KeyframeChannel<?>, Copy> channels = new IdentityHashMap<>();
    private Map<Clips, Map<Clip, Shot>> clips = new IdentityHashMap<>();

    /**
     * Whether an edit since the last {@link #follow} may have rewritten keys or clips without the copy
     * following it — the film as a whole, the list of replays, a replay as a whole — so that the next
     * one reads all of it again.
     */
    private boolean resync;

    /**
     * Brings the copy in line with what the recording is made from: the channels of the replays a
     * step reads now, and every replay's clips.
     *
     * <p>What the copy already holds has followed every edit made since, so it is kept, and only what
     * it lacks is read — the channels of a replay just come into reach, the clips of a replay just
     * added. Reading every key of a long film again on every edit would be felt on every frame of a
     * drag. After an edit the copy could not follow, all of it is read again.</p>
     */
    void follow(Film film, Set<String> reach)
    {
        Map<KeyframeChannel<?>, Copy> knownChannels = this.channels;
        Map<Clips, Map<Clip, Shot>> knownClips = this.clips;

        this.channels = new IdentityHashMap<>();
        this.clips = new IdentityHashMap<>();

        if (this.resync)
        {
            knownChannels.clear();
            knownClips.clear();

            this.resync = false;
        }

        if (film == null)
        {
            return;
        }

        for (Replay replay : film.replays.getList())
        {
            Map<Clip, Shot> shots = knownClips.get(replay.actions);

            this.clips.put(replay.actions, shots != null ? shots : shots(replay.actions));

            if (reach.contains(replay.getId()))
            {
                for (KeyframeChannel<?> channel : channelsOf(replay))
                {
                    Copy copy = knownChannels.get(channel);

                    this.channels.put(channel, copy != null ? copy : copy(channel, null, null));
                }
            }
        }
    }

    /**
     * The first tick an edit to {@code value} can change, or {@link #NONE} when it changes nothing the
     * simulation reads. The copy of the edited channel or clips is brought up to date either way.
     *
     * @param reach the replays a step reads — the animation of any other replay changes nothing here
     */
    int affected(Film film, BaseValue value, Set<String> reach)
    {
        if (value == film)
        {
            this.resync = true;

            return 0;
        }

        List<String> path = value.getPath().strings;

        if (!SceneEdits.matters(path))
        {
            return NONE;
        }

        BaseValue part = null;
        Replay replay = null;

        for (BaseValue current = value; current != null; part = current, current = current.getParent())
        {
            if (current instanceof Replay found)
            {
                replay = found;
                break;
            }
        }

        if (replay == null)
        {
            /* The film's physics knobs, the list of replays: all of it from the start. Anything above
             * the replays but the knobs may have brought keys the copy never saw. */
            if (!path.contains(FilmPhysics.KEY))
            {
                this.resync = true;
            }

            return 0;
        }

        if (part == null)
        {
            /* The replay as a whole — which is how a recording overwrites it: its keys may all be
             * new. */
            this.resync = true;

            return 0;
        }

        if (part == replay.actions)
        {
            return this.clipsChanged(replay.actions);
        }

        if (part != replay.keyframes && part != replay.properties)
        {
            /* The form, the anchor as authored, the replay's own settings. */
            return 0;
        }

        if (!reach.contains(replay.getId()))
        {
            return NONE;
        }

        for (BaseValue current = value; current != null && current != replay; current = current.getParent())
        {
            if (current instanceof KeyframeChannel<?> channel)
            {
                return this.channelChanged(channel, value instanceof Keyframe<?> keyframe ? keyframe : null);
            }
        }

        /* The channels as a whole — a track added or taken away: the copy starts again from here. */
        for (KeyframeChannel<?> channel : channelsOf(replay))
        {
            this.channels.put(channel, copy(channel, null, null));
        }

        return 0;
    }

    /**
     * @param edited the one key this change was made to, or null when it was made to the channel —
     *               then every key is read again; otherwise only that one, the rest being as they were
     */
    private int channelChanged(KeyframeChannel<?> channel, Keyframe<?> edited)
    {
        Copy before = this.channels.get(channel);
        Copy after = copy(channel, edited == null ? null : before, edited);

        this.channels.put(channel, after);

        /* A channel the copy never saw — a new track — counts from the start. */
        if (before == null)
        {
            return 0;
        }

        return Math.min(firstChange(before.keys(), after.keys()), firstLoopChange(before.loops(), after.loops()));
    }

    /**
     * The first tick two versions of one channel's keys can disagree on, or {@link #NONE} when they
     * are the same.
     *
     * <p>A key's change reaches back two keys: the piece of curve between two keys is drawn through
     * the key before them and the key after them too, so the piece that ends one key before the
     * changed one already bends with it. Keys switched off are passed over in that count, as they
     * are in playback. Any change in the first two keys that play reaches the start of the film,
     * since everything before the first key holds its value.</p>
     */
    static int firstChange(List<Key> before, List<Key> after)
    {
        int common = Math.min(before.size(), after.size());
        int first = 0;

        while (first < common && before.get(first).equals(after.get(first)))
        {
            first += 1;
        }

        if (first == before.size() && first == after.size())
        {
            return NONE;
        }

        /* The two keys before it that play — the same in both versions, which agree up to it. */
        int from = first;

        for (int counted = 0; counted < 2; )
        {
            from -= 1;

            if (from < 0)
            {
                return 0;
            }

            if (before.get(from).enabled())
            {
                counted += 1;
            }
        }

        /* From there; and from wherever a changed key now sits or used to, should one have moved past
         * the keys before it. */
        float earliest = Float.POSITIVE_INFINITY;

        for (int i = from; i < before.size(); i++)
        {
            earliest = Math.min(earliest, before.get(i).tick());
        }

        for (int i = from; i < after.size(); i++)
        {
            earliest = Math.min(earliest, after.get(i).tick());
        }

        return Math.max(0, (int) Math.floor(earliest));
    }

    /**
     * The earliest start among the loops added, removed or changed, or {@link #NONE}. A loop repeats
     * keys after its own start and changes nothing before it.
     */
    static int firstLoopChange(List<KeyframeLoop> before, List<KeyframeLoop> after)
    {
        if (before.equals(after))
        {
            return NONE;
        }

        float earliest = Float.POSITIVE_INFINITY;

        for (KeyframeLoop loop : before)
        {
            if (!after.contains(loop))
            {
                earliest = Math.min(earliest, loop.start());
            }
        }

        for (KeyframeLoop loop : after)
        {
            if (!before.contains(loop))
            {
                earliest = Math.min(earliest, loop.start());
            }
        }

        /* The same loops kept in another order: from the first of them, which assumes nothing. */
        if (earliest == Float.POSITIVE_INFINITY)
        {
            for (KeyframeLoop loop : after)
            {
                earliest = Math.min(earliest, loop.start());
            }
        }

        return Float.isFinite(earliest) ? Math.max(0, (int) Math.floor(earliest)) : 0;
    }

    /** The earliest start among the clips added, removed or changed, or {@link #NONE}. */
    private int clipsChanged(Clips list)
    {
        Map<Clip, Shot> before = this.clips.get(list);
        Map<Clip, Shot> after = shots(list);

        this.clips.put(list, after);

        if (before == null)
        {
            return 0;
        }

        int first = NONE;

        for (Map.Entry<Clip, Shot> entry : after.entrySet())
        {
            Shot now = entry.getValue();
            Shot then = before.get(entry.getKey());

            if (then == null)
            {
                first = Math.min(first, now.tick());
            }
            else if (!then.equals(now))
            {
                first = Math.min(first, Math.min(then.tick(), now.tick()));
            }
        }

        for (Map.Entry<Clip, Shot> entry : before.entrySet())
        {
            if (!after.containsKey(entry.getKey()))
            {
                first = Math.min(first, entry.getValue().tick());
            }
        }

        return first == NONE ? NONE : Math.max(0, first);
    }

    private static List<KeyframeChannel<?>> channelsOf(Replay replay)
    {
        List<KeyframeChannel<?>> channels = new ArrayList<>(replay.keyframes.getChannels());

        for (KeyframeChannel<?> channel : replay.properties.tracks.values())
        {
            channels.add(channel);
        }

        return channels;
    }

    /**
     * The channel as it is now. With {@code known} and {@code edited}, a change to one key reads only
     * that key again and takes the rest from {@code known}: a recorded take has a key on every tick,
     * and reading thousands of them for every step of a drag would be felt under the hand.
     */
    private static Copy copy(KeyframeChannel<?> channel, Copy known, Keyframe<?> edited)
    {
        Map<Keyframe<?>, Key> previous = null;

        if (known != null)
        {
            previous = new IdentityHashMap<>(known.keyframes().size());

            for (int i = 0; i < known.keyframes().size(); i++)
            {
                previous.put(known.keyframes().get(i), known.keys().get(i));
            }
        }

        List<Keyframe<?>> keyframes = new ArrayList<>(channel.getKeyframes());
        List<Key> keys = new ArrayList<>(keyframes.size());

        for (Keyframe<?> keyframe : keyframes)
        {
            Key key = previous == null || keyframe == edited ? null : previous.get(keyframe);

            keys.add(key != null ? key : new Key(keyframe.getTick(), keyframe.isEnabled(), keyframe.toData()));
        }

        /* Loops are few and read whole every time: a loop is changed through its channel, never
         * through one of its keys, but reading them is cheaper than relying on that. */
        return new Copy(keyframes, keys, List.copyOf(channel.getLoops()));
    }

    private static Map<Clip, Shot> shots(Clips list)
    {
        Map<Clip, Shot> shots = new IdentityHashMap<>();

        for (Clip clip : list.get())
        {
            shots.put(clip, new Shot(clip.tick.get(), clip.toData()));
        }

        return shots;
    }
}
