package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import wemppy.bbs_physics.engine.FilmPhysics;

import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p>Each answer also says what the edit does to the poses kept between runs ({@link ScenePoses}):
 * a physics edit leaves them all standing, a key takes back its replay's from the same tick as the
 * recording, a form handed over whole is compared, and anything else forgets its replay's.</p>
 */
final class SceneChanges
{
    /** What {@link #affected} answers for an edit that changes nothing the simulation reads. */
    static final int NONE = Integer.MAX_VALUE;

    /** What an edit does to the poses walked so far — see {@link ScenePoses}. */
    enum Poses
    {
        /** Nothing: the animation is as it was. */
        STAND,
        /** The replay's animation is different from the change's tick on. */
        FROM,
        /** The replay's form was handed over whole: its poses stand if its looks did not change. */
        FORM,
        /** None of the replay's poses can be trusted. */
        REPLAY,
        /** None of anyone's. */
        ALL
    }

    /**
     * What one edit changes: the recording from {@code tick} on, and the poses walked so far as
     * {@code poses} says — of {@code replay}'s actor, or of everyone when there is no replay.
     */
    record Change(int tick, Replay replay, Poses poses)
    {
        static final Change NOTHING = new Change(NONE, null, Poses.STAND);
    }

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

    /** Every replay's form as it was, whole — see {@link #affected}. */
    private Map<Replay, MapType> forms = new IdentityHashMap<>();

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
        Map<Replay, MapType> knownForms = this.forms;

        this.channels = new IdentityHashMap<>();
        this.clips = new IdentityHashMap<>();
        this.forms = new IdentityHashMap<>();

        if (this.resync)
        {
            knownChannels.clear();
            knownClips.clear();
            knownForms.clear();

            this.resync = false;
        }

        if (film == null)
        {
            return;
        }

        for (Replay replay : film.replays.getList())
        {
            Map<Clip, Shot> shots = knownClips.get(replay.actions);
            MapType form = knownForms.get(replay);

            this.clips.put(replay.actions, shots != null ? shots : shots(replay.actions));
            this.forms.put(replay, form != null ? form : formData(replay));

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
     * What an edit to {@code value} changes: the first tick of the recording ({@link #NONE} when it
     * changes nothing the simulation reads), and what becomes of the poses walked so far (see
     * {@link ScenePoses}). The copy of the edited channel or clips is brought up to date either way.
     *
     * @param reach the replays a step reads — the animation of any other replay changes nothing here
     */
    Change affected(Film film, BaseValue value, Set<String> reach)
    {
        Replay owner = replayOf(value);

        if (owner != null && partOf(value, owner) == owner.form)
        {
            /* The copy of a form follows every edit to it, and a form handed back whole saying exactly
             * what it said changes nothing: the form editor hands the form back as it opens, and
             * again as it closes, edited or not — and BBS rebuilds the cast both times. */
            MapType now = formData(owner);
            MapType before = this.forms.put(owner, now);

            if (value == owner.form && now.equals(before))
            {
                return Change.NOTHING;
            }
        }

        Change change = classify(film, value);

        if (change.replay() == null || change.tick() == NONE)
        {
            /* Nothing the simulation reads, the film's physics knobs — or the film as a whole, or the
             * list of its replays, after which the keys may all be new. */
            if (change.poses() == Poses.ALL)
            {
                this.resync = true;
            }

            return change;
        }

        Replay replay = change.replay();
        BaseValue part = partOf(value, replay);

        if (part == null)
        {
            /* The replay as a whole — which is how a recording overwrites it: its keys may all be
             * new. */
            this.resync = true;

            return change;
        }

        if (part == replay.actions)
        {
            return new Change(this.clipsChanged(replay.actions), replay, Poses.STAND);
        }

        if (part != replay.keyframes && part != replay.properties)
        {
            /* The form, the anchor as authored, the replay's own settings. */
            return change;
        }

        if (!reach.contains(replay.getId()))
        {
            return Change.NOTHING;
        }

        for (BaseValue current = value; current != null && current != replay; current = current.getParent())
        {
            if (current instanceof KeyframeChannel<?> channel)
            {
                int tick = this.channelChanged(channel, value instanceof Keyframe<?> keyframe ? keyframe : null);

                return new Change(tick, replay, change.poses() == Poses.STAND ? Poses.STAND : Poses.FROM);
            }
        }

        /* The channels as a whole — a track added or taken away: the copy starts again from here. */
        for (KeyframeChannel<?> channel : channelsOf(replay))
        {
            this.channels.put(channel, copy(channel, null, null));
        }

        return new Change(0, replay, Poses.REPLAY);
    }

    /**
     * What an edit to {@code value} changes, told from where the value sits alone — no copy to compare
     * against, so a key changes its replay from the start and its poses all. What {@link #affected}
     * refines; and what an edit comes to when no scene is there to compare it.
     */
    static Change classify(Film film, BaseValue value)
    {
        if (value == film)
        {
            return new Change(0, null, Poses.ALL);
        }

        List<String> path = value.getPath().strings;

        if (!SceneEdits.matters(path))
        {
            return Change.NOTHING;
        }

        Replay replay = replayOf(value);

        if (replay == null)
        {
            /* The film's physics knobs move no one's animation; the list of replays may move anyone's. */
            return new Change(0, null, path.contains(FilmPhysics.KEY) ? Poses.STAND : Poses.ALL);
        }

        BaseValue part = partOf(value, replay);

        if (part == null)
        {
            return new Change(0, replay, Poses.REPLAY);
        }

        if (part == replay.actions)
        {
            /* A push changes where things fly, never how anyone is animated. */
            return new Change(0, replay, Poses.STAND);
        }

        if (part == replay.keyframes || part == replay.properties)
        {
            /* A track of a physics knob moves no bone; any other key moves the replay's actor. */
            String property = part == replay.properties ? SceneEdits.trackProperty(path) : null;

            return new Change(0, replay, property != null && ScenePoses.isPhysics(property) ? Poses.STAND : Poses.REPLAY);
        }

        if (part == replay.form)
        {
            if (value == replay.form)
            {
                /* The form put back whole, which is how the form editor hands it over: its looks
                 * may or may not have changed, and only a comparison can say. */
                return new Change(0, replay, Poses.FORM);
            }

            for (BaseValue current = value; current != null && current != replay.form; current = current.getParent())
            {
                if (ScenePoses.isPhysics(current.getId()))
                {
                    return new Change(0, replay, Poses.STAND);
                }
            }

            return new Change(0, replay, Poses.REPLAY);
        }

        /* The replay's own settings: its death switches are physics, the rest places the actor. */
        return new Change(0, replay, ScenePoses.isPhysics(part.getId()) ? Poses.STAND : Poses.REPLAY);
    }

    /**
     * Feeds {@code into} everything of the film a recording is made from, as this copy has it — what
     * a recording kept on disk is checked against (see {@link RecordingStore}). The film's physics
     * knobs, the replays a step reads, and every replay in order: what places it, its form whole, its
     * clips, and — for a replay a step reads — every key and loop of every channel.
     *
     * <p>From the copy rather than from the film, because the copy has every key read already and
     * keeps up with every edit as it happens: nothing here is read twice.</p>
     */
    void fingerprint(Film film, Set<String> reach, Fingerprint into)
    {
        into.put(FilmPhysics.of(film).knobs());

        List<String> reached = new ArrayList<>(reach);

        reached.sort(null);
        into.put(reached.size());

        for (String id : reached)
        {
            into.put(id);
        }

        List<Replay> replays = film.replays.getList();

        into.put(replays.size());

        for (Replay replay : replays)
        {
            into.put(replay.getId());

            /* What places it — everything of the replay but its keys, tracks, clips and form, which
             * follow. */
            for (BaseValue value : replay.getAll())
            {
                if (value != replay.keyframes && value != replay.properties && value != replay.actions && value != replay.form)
                {
                    into.put(value.getId()).put(value.toData());
                }
            }

            MapType form = this.forms.get(replay);

            into.put(form != null ? form : formData(replay));

            Map<Clip, Shot> shots = this.clips.get(replay.actions);
            List<Clip> clips = replay.actions.get();

            into.put(clips.size());

            for (Clip clip : clips)
            {
                Shot shot = shots == null ? null : shots.get(clip);

                into.put(shot != null ? shot.tick() : clip.tick.get()).put(shot != null ? shot.data() : clip.toData());
            }

            if (!reach.contains(replay.getId()))
            {
                continue;
            }

            for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
            {
                this.fingerprint(channel, into);
            }

            /* By the track's name: the tracks are kept in a map, in no order of their own. */
            List<Object> tracks = new ArrayList<>(replay.properties.tracks.keySet());

            tracks.sort(Comparator.comparing(String::valueOf));

            for (Object track : tracks)
            {
                into.put(String.valueOf(track));
                this.fingerprint((KeyframeChannel<?>) replay.properties.tracks.get(track), into);
            }
        }
    }

    private void fingerprint(KeyframeChannel<?> channel, Fingerprint into)
    {
        Copy copy = this.channels.get(channel);

        if (copy == null)
        {
            copy = copy(channel, null, null);
        }

        into.put(channel.getId()).put(copy.keys().size());

        for (Key key : copy.keys())
        {
            into.put(key.tick()).put(key.enabled()).put(key.data());
        }

        into.put(copy.loops().size());

        for (KeyframeLoop loop : copy.loops())
        {
            into.put(loop.id()).put(loop.start()).put(loop.sourceEnd()).put(loop.end());
        }
    }

    /** The replay {@code value} sits in, or null when it sits above the replays. */
    private static Replay replayOf(BaseValue value)
    {
        for (BaseValue current = value; current != null; current = current.getParent())
        {
            if (current instanceof Replay replay)
            {
                return replay;
            }
        }

        return null;
    }

    /** The replay's form as data, whole — what a form handed back is compared against. */
    private static MapType formData(Replay replay)
    {
        MapType data = FormUtils.toData(replay.form.get());

        return data == null ? new MapType() : data;
    }

    /** The child of {@code replay} that {@code value} sits under, or null for the replay itself. */
    private static BaseValue partOf(BaseValue value, Replay replay)
    {
        BaseValue part = null;

        for (BaseValue current = value; current != null && current != replay; current = current.getParent())
        {
            part = current;
        }

        return part;
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
