package mchorse.bbs_physics.engine;

import mchorse.bbs_physics.BBSPhysics;

import java.util.Map;
import java.util.TreeMap;

/**
 * Makes a physics world a function of the film's tick rather than of how the film was played.
 *
 * <p>This is the difference between physics in a game and physics in an editor. A game only ever
 * moves forward, so "step once per frame" is the whole story. An editor scrubs: the cursor jumps
 * back to 200, forward to 900, back to 0, and tick 300 has to look the same every single time it
 * is visited — otherwise the viewport disagrees with the exported video, and an animator cannot
 * trust what they see.</p>
 *
 * <p>So the world is never simply stepped. It is <em>seeked</em>: snapshots are kept as the
 * simulation goes, and a jump backwards restores the nearest one at or before the target and
 * re-simulates the remainder. Jolt's state round-trips exactly, so a re-simulated tick is
 * bit-for-bit the tick that was there before.</p>
 */
public class PhysicsTimeline
{
    /**
     * Ticks between snapshots. A backward jump therefore costs at most this many steps, which at
     * one second of film is well under a frame's worth of work. Smaller would spend memory to save
     * time nobody notices.
     */
    public static final int CHECKPOINT_INTERVAL = 20;

    /**
     * How many ticks a single seek may simulate before it gives up. A drag of the cursor across a
     * long film would otherwise freeze the game for as long as the film lasts. When the budget is
     * exhausted the world is left where it got to and the timeline says so — the alternative is a
     * frozen editor, which is worse than physics that is visibly behind.
     */
    private static final int MAX_SEEK_STEPS = 1200;

    /** Snapshots are cheap but not free, so old ones are dropped once there are too many. */
    private static final int MAX_CHECKPOINTS = 512;

    private final PhysicsWorld world;
    private final TreeMap<Integer, byte[]> checkpoints = new TreeMap<>();

    private int tick;
    private int lastSeekSteps;
    private boolean behind;

    public PhysicsTimeline(PhysicsWorld world)
    {
        this.world = world;
    }

    public int getTick()
    {
        return this.tick;
    }

    public int getCheckpointCount()
    {
        return this.checkpoints.size();
    }

    /** How many steps the last {@link #seek(int)} had to run — the number the debug overlay shows. */
    public int getLastSeekSteps()
    {
        return this.lastSeekSteps;
    }

    /** Whether the last seek ran out of budget and left the world short of the tick asked for. */
    public boolean isBehind()
    {
        return this.behind;
    }

    /**
     * Declares the world as it stands to be tick 0. Called once the scene's bodies are in place:
     * the snapshot taken here is what every rewind past the first checkpoint falls back to, so
     * without it a scrub to the start would have nothing to restore.
     */
    public void start()
    {
        this.checkpoints.clear();
        this.tick = 0;
        this.lastSeekSteps = 0;
        this.behind = false;
        this.checkpoints.put(0, this.world.saveState());
    }

    /**
     * Brings the world to {@code target}, however far away and in whichever direction that is.
     * Repeating the current tick does nothing, which is what a paused editor does every frame.
     */
    public void seek(int target)
    {
        if (target < 0)
        {
            target = 0;
        }

        this.lastSeekSteps = 0;
        this.behind = false;

        if (target == this.tick)
        {
            return;
        }

        if (target < this.tick)
        {
            this.rewind(target);
        }

        this.advance(target);
    }

    /**
     * Restores the newest snapshot at or before {@code target}. There is always one, because
     * {@link #start()} put a snapshot at tick 0 and a target is never negative — unless the world
     * has changed shape underneath us, in which case Jolt refuses the state and the caller has to
     * rebuild the scene.
     */
    private void rewind(int target)
    {
        Map.Entry<Integer, byte[]> entry = this.checkpoints.floorEntry(target);

        if (entry == null)
        {
            /* Only reachable if start() was never called. Nothing to restore, so the best that can
             * be done is to keep simulating forward from wherever the world is. */
            BBSPhysics.LOGGER.warn("Physics scene has no checkpoint to rewind to, tick {} will be wrong.", target);

            this.tick = target;

            return;
        }

        if (!this.world.restoreState(entry.getValue()))
        {
            BBSPhysics.LOGGER.warn("Jolt refused a checkpoint at tick {}; the scene changed under it.", entry.getKey());

            this.checkpoints.clear();
            this.tick = target;

            return;
        }

        this.tick = entry.getKey();

        /* Snapshots after the point we jumped back to describe a future that is about to be
         * re-simulated. Keeping them would be harmless while the scene is untouched and wrong the
         * moment anything about it changes, so they go. */
        this.checkpoints.tailMap(target, false).clear();
    }

    private void advance(int target)
    {
        while (this.tick < target)
        {
            if (this.lastSeekSteps >= MAX_SEEK_STEPS)
            {
                this.behind = true;

                BBSPhysics.LOGGER.warn("Physics seek to tick {} stopped at {} — {} steps is the limit for one jump.", target, this.tick, MAX_SEEK_STEPS);

                /* Claim the target anyway. Insisting on catching up would spend the same budget
                 * again on every following frame and never arrive. */
                this.tick = target;

                return;
            }

            this.world.step();

            this.tick += 1;
            this.lastSeekSteps += 1;

            if (this.tick % CHECKPOINT_INTERVAL == 0)
            {
                this.checkpoint();
            }
        }
    }

    private void checkpoint()
    {
        this.checkpoints.put(this.tick, this.world.saveState());

        while (this.checkpoints.size() > MAX_CHECKPOINTS)
        {
            /* The oldest goes, except for tick 0 — that one is the fallback every rewind past the
             * kept range relies on, and losing it would turn a jump to the beginning into a
             * simulation of the entire film. */
            Integer oldest = this.checkpoints.higherKey(0);

            if (oldest == null || oldest == this.tick)
            {
                break;
            }

            this.checkpoints.remove(oldest);
        }
    }
}
