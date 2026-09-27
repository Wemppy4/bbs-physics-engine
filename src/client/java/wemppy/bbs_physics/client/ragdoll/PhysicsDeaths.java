package wemppy.bbs_physics.client.ragdoll;

import wemppy.bbs_physics.ragdoll.DeathCapture;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import wemppy.bbs_physics.actions.DeathActionClip;
import wemppy.bbs_physics.ragdoll.DeathReplay;

import java.util.*;

/** One take can kill several other actors. Their events belong to the film, not the shooter's clips. */
public final class PhysicsDeaths
{
    private record Hit(String victim, UUID entity, int tick, Point point, Point direction) {}
    private record Captured(String victim, DeathActionClip clip) {}
    private static Film film;
    private static Film preparedEditor;
    private static String source;
    private static int first;
    private static int last;
    private static boolean taking;
    private static final List<Captured> hits = new ArrayList<>();
    private static final List<Captured> retained = new ArrayList<>();
    private static final Map<Replay, List<DeathActionClip>> previous = new IdentityHashMap<>();
    private static final Set<UUID> seen = new HashSet<>();

    public static void init()
    {
        ClientPlayNetworking.registerGlobalReceiver(DeathCapture.ID, (client, handler, buf, sender) ->
        {
            String filmId = buf.readString();
            String victim = buf.readString();
            UUID entity = buf.readUuid();
            String owner = buf.readString();
            int tick = buf.readInt();
            Point point = new Point(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Point direction = new Point(buf.readDouble(), buf.readDouble(), buf.readDouble());
            client.execute(() -> receive(filmId, owner, new Hit(victim, entity, tick, point, direction)));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { reset(); preparedEditor = null; });
    }

    public static boolean isTaking() { return taking; }
    public static void prepareEditor(Film target) { preparedEditor = target; }

    /** Called only for a real gameplay take. Editing pose channels alone must not replace deaths. */
    public static void actionRecording(String id, int replay, int start, boolean recording)
    {
        if (recording && preparedEditor != null && preparedEditor.getId().equals(id))
            tick(preparedEditor, replay, start, start - 1);
        else if (!recording) end();
    }

    public static void tick(Film target, int replayIndex, int start, int tick)
    {
        if (target == null || replayIndex < 0 || replayIndex >= target.replays.getList().size()) return;
        String owner = target.replays.getList().get(replayIndex).getId();
        if (!taking || film != target || !Objects.equals(source, owner))
        {
            reset();
            film = target;
            source = owner;
            first = start;
            taking = true;
            last = first - 1;
            // The old take must not kill the actor before this take's new bullet arrives.
            for (Replay replay : film.replays.getList())
            {
                var old = replay.actions.getClips(DeathActionClip.class).stream()
                    .filter(c -> source.equals(c.sourceReplay.get()) && (replay.looping.get() > 0 || c.tick.get() >= replay.getTick(first)))
                    .toList();
                if (!old.isEmpty())
                {
                    previous.put(replay, old);
                    BaseValue.edit(replay.actions, actions -> old.forEach(actions::remove));
                }
            }
        }
        last = Math.max(last, tick);
    }

    private static void receive(String filmId, String owner, Hit hit)
    {
        // Packets sent before stop arrive before the action-recording reply on the same connection.
        if (film == null || !film.getId().equals(filmId) || !Objects.equals(source, owner) || hit.tick < 0
            || !finite(hit.point) || !finite(hit.direction) || !seen.add(hit.entity)) return;
        Replay victim = find(film, hit.victim);
        if (!(victim instanceof DeathReplay settings) || !settings.bbs_physics$deathEnabled().get()) return;
        DeathActionClip clip = new DeathActionClip();
        clip.tick.set(victim.getTick(first + hit.tick));
        clip.duration.set(1);
        clip.point.set(hit.point);
        clip.direction.set(hit.direction);
        clip.strength.set(3F);
        clip.sourceReplay.set(source);
        hits.add(new Captured(hit.victim, clip));
        last = Math.max(last, first + hit.tick);
        BaseValue.edit(victim.actions, actions -> actions.addClip(clip));
    }

    public static void end()
    {
        taking = false;
        previous.forEach((replay, clips) ->
        {
            var untouched = clips.stream().filter(c -> !inRange(replay, c.tick.get())).toList();
            for (DeathActionClip clip : untouched) retained.add(new Captured(replay.getId(), clip));
            if (!untouched.isEmpty()) BaseValue.edit(replay.actions, actions -> untouched.forEach(actions::addClip));
        });
        previous.clear();
    }

    public static boolean finish(Film target, int replayIndex, int start)
    {
        if (taking || target == null || start != first || replayIndex < 0 || replayIndex >= target.replays.getList().size()
            || !Objects.equals(source, target.replays.getList().get(replayIndex).getId())) return false;
        boolean changed = merge(target);
        if (film != null && target != null && film.getId().equals(target.getId())) reset();
        return changed;
    }

    public static boolean merge(Film target)
    {
        if (film == null || target == null || !target.getId().equals(film.getId()) || source == null) return false;
        // Reconcile even the live film: BBS replaces the recording actor's action track.
        boolean[] changed = {false};
        BaseValue.edit(target, value ->
        {
            for (Replay replay : target.replays.getList())
            {
                var old = new ArrayList<>(replay.actions.getClips(DeathActionClip.class));
                for (DeathActionClip clip : old)
                {
                    if (source.equals(clip.sourceReplay.get()) && inRange(replay, clip.tick.get()))
                    {
                        replay.actions.remove(clip);
                        changed[0] = true;
                    }
                }
                for (Captured hit : hits)
                {
                    if (!replay.getId().equals(hit.victim)) continue;
                    DeathActionClip clip = new DeathActionClip();
                    clip.fromData(hit.clip.toData());
                    replay.actions.addClip(clip);
                    changed[0] = true;
                }
                // BBS's action reply replaces the recording actor's entire tail, including
                // deaths beyond this take. Put those back without duplicating live events.
                for (Captured saved : retained)
                {
                    if (!replay.getId().equals(saved.victim) || inRange(replay, saved.clip.tick.get())) continue;
                    var data = saved.clip.toData();
                    if (replay.actions.getClips(DeathActionClip.class).stream().anyMatch(c -> c.toData().equals(data))) continue;
                    DeathActionClip clip = new DeathActionClip();
                    clip.fromData(data);
                    replay.actions.addClip(clip);
                    changed[0] = true;
                }
            }
        });
        return changed[0];
    }

    private static Replay find(Film film, String id)
    {
        for (Replay replay : film.replays.getList()) if (replay.getId().equals(id)) return replay;
        return null;
    }

    private static boolean finite(Point p) { return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    private static boolean inRange(Replay replay, int tick)
    {
        if (last < first) return false;
        int loop = replay.looping.get();
        if (loop <= 0) return tick >= first && tick <= last;
        if (last - first + 1 >= loop) return true;
        int from = replay.getTick(first), to = replay.getTick(last);
        return from <= to ? tick >= from && tick <= to : tick >= from || tick <= to;
    }
    private static void reset() { film = null; source = null; taking = false; hits.clear(); retained.clear(); seen.clear(); previous.clear(); }
    private PhysicsDeaths() {}
}
