package wemppy.bbs_physics.client.scene;

import wemppy.bbs_physics.engine.RigMemory;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording's checkpoints: every second of the film, where the world and every rig stood, so an
 * edit picks the simulation up at the last one before the first tick it changes instead of replaying
 * the film from its opening frame.
 *
 * <p>Not for scrubbing. Scrubbing reads the recording and nothing else (Р7); a checkpoint is only ever
 * restored to record onwards from it, and only when an edit made the rest of the recording wrong.</p>
 *
 * <p>Held in memory beside the recording. A scene of many bodies makes them heavy, so past a budget
 * they thin out — every other one goes, and from then on only every other one is taken — rather than
 * stop being taken: a coarser checkpoint still spares most of a film.</p>
 */
final class SceneCheckpoints
{
    /** One second of film between checkpoints, to begin with. */
    static final int INTERVAL = 20;

    /** What all the checkpoints of a scene may take together, beside the recording's own ceiling. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    /** Never thinner than this, however heavy a checkpoint is. */
    private static final int MAX_INTERVAL = INTERVAL << 6;

    /**
     * One checkpoint: the tick it was taken after, Jolt's snapshot of the world, what every rig
     * remembered, and the first tick something had left the world by then (-1 for none).
     */
    record Checkpoint(int tick, byte[] world, RigMemory rigs, int lostAt)
    {
        long bytes()
        {
            return this.world.length + this.rigs.bytes();
        }
    }

    private final TreeMap<Integer, Checkpoint> byTick = new TreeMap<>();
    private int interval = INTERVAL;
    private long bytes;

    /** Whether a checkpoint is to be taken after recording {@code tick}. */
    boolean due(int tick)
    {
        return tick > 0 && tick % this.interval == 0;
    }

    void add(Checkpoint checkpoint)
    {
        Checkpoint replaced = this.byTick.put(checkpoint.tick(), checkpoint);

        if (replaced != null)
        {
            this.bytes -= replaced.bytes();
        }

        this.bytes += checkpoint.bytes();

        while (this.bytes > MAX_BYTES && this.thin())
        {}
    }

    /** The last checkpoint taken strictly before {@code tick}, or null when there is none. */
    Checkpoint before(int tick)
    {
        Map.Entry<Integer, Checkpoint> entry = this.byTick.lowerEntry(tick);

        return entry == null ? null : entry.getValue();
    }

    /** The checkpoint taken after {@code tick}, or null. */
    Checkpoint at(int tick)
    {
        return this.byTick.get(tick);
    }

    /** Forgets every checkpoint after {@code tick}: the recording they were taken from is gone. */
    void dropAfter(int tick)
    {
        Iterator<Checkpoint> later = this.byTick.tailMap(tick, false).values().iterator();

        while (later.hasNext())
        {
            this.bytes -= later.next().bytes();
            later.remove();
        }
    }

    /** Every checkpoint, earliest first — for keeping them on disk. */
    List<Checkpoint> all()
    {
        return new ArrayList<>(this.byTick.values());
    }

    void clear()
    {
        this.byTick.clear();
        this.bytes = 0L;
        this.interval = INTERVAL;
    }

    /** Doubles the interval and drops the checkpoints that are no longer on it; false when it cannot. */
    private boolean thin()
    {
        if (this.interval >= MAX_INTERVAL)
        {
            return false;
        }

        this.interval *= 2;

        Iterator<Checkpoint> all = this.byTick.values().iterator();

        while (all.hasNext())
        {
            Checkpoint checkpoint = all.next();

            if (checkpoint.tick() % this.interval != 0)
            {
                this.bytes -= checkpoint.bytes();
                all.remove();
            }
        }

        return true;
    }
}
