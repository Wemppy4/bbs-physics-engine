package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;

/**
 * The worker threads a heavy scene's steps are shared out over: one pool for the whole game,
 * started the first time a scene is heavy enough to want it.
 *
 * <p>What a film comes out as does not depend on it. Jolt steps a world on any number of threads to
 * the very bit it steps it on one — its own tests hold it to that, a run on fifteen workers against
 * a run on none after every step, and so does {@code ThreadsCheck} here, on boxes, joints and cloth,
 * switched over halfway through. That is what lets a world change its mind in the middle of a film:
 * checkpoints, a recording handed over and a recording kept on disk all stay the film's to the bit.
 * Nothing of the addon's own runs during a step — no listeners, no filters written in Java — so
 * the workers never touch the game.</p>
 *
 * <p>Not worth it for a light scene: handing a step out costs a few hundredths of a millisecond,
 * more than a step of a few bodies takes. Measured on an eight-core Ryzen: below about 0.4 ms a step
 * the pool loses slightly, from 0.9 ms it always wins, and heavy scenes — hundreds of boxes, a dozen
 * ragdolls' worth of joints, several sheets of cloth — step two and a half to three times faster on
 * four workers. Which is how {@link Choice} picks.</p>
 *
 * <p>Never closed. It lives as long as the game, like the game's own worker pools, and its threads
 * sleep while nothing is stepped; closing it on the way out would race a step still in flight.</p>
 */
public final class PhysicsThreads
{
    /** Jolt's own figure for a physics system's barriers. */
    private static final int MAX_BARRIERS = 8;

    private static JobSystemThreadPool pool;
    private static boolean unavailable;

    private PhysicsThreads()
    {}

    /**
     * Workers beside the thread that steps: a quarter of the logical processors, at most four —
     * past four the measured gain is a few percent while the game's own threads have less room —
     * and none at all on a machine of two.
     */
    public static int workers()
    {
        int processors = Runtime.getRuntime().availableProcessors();

        return processors <= 2 ? 0 : Math.min(4, Math.max(1, processors / 4));
    }

    /** Whether a step may go to the pool: the player has not switched it off, and there are threads to have. */
    public static boolean allowed()
    {
        return !unavailable && (BBSPhysicsSettings.threads == null || BBSPhysicsSettings.threads.get()) && workers() > 0;
    }

    /** The pool, started on first use; null when it cannot be had, and then never asked for again. */
    static synchronized JobSystem pool()
    {
        if (pool == null && !unavailable)
        {
            try
            {
                pool = new JobSystemThreadPool(PhysicsWorld.JOB_QUEUE, MAX_BARRIERS, workers());

                BBSPhysics.LOGGER.info("Heavy physics scenes step on {} worker threads.", workers());
            }
            catch (Throwable e)
            {
                unavailable = true;

                BBSPhysics.LOGGER.warn("Physics worker threads could not be started; every scene steps on one thread.", e);
            }
        }

        return pool;
    }

    /**
     * Which one world steps on, learned from what its own steps cost.
     *
     * <p>One thread until a step costs more than {@link #TRY_ABOVE}. Then the pool, on trial: kept
     * if it steps at least a tenth faster, given back otherwise — and not tried again until steps
     * on one thread grow half as dear again. Back to one thread for good once the scene has gone
     * quiet, a step on the pool under {@link #BACK_BELOW}. The wide gap between the two is what keeps
     * a scene from flapping.</p>
     *
     * <p>A cost is a running average over the last few steps, with a lone spike cut down to three
     * times the average first — a collection, the system scheduling something else — so that one
     * hitch moves nothing.</p>
     */
    public static final class Choice
    {
        /** Milliseconds a step costs on one thread before the pool is tried. */
        public static final double TRY_ABOVE = 0.6D;

        /** Milliseconds a step costs on the pool below which one thread is cheaper again. */
        public static final double BACK_BELOW = 0.25D;

        /** Steps on a choice before its cost is trusted. */
        public static final int SETTLE = 8;

        private boolean pooled;
        private double cost;
        private int steps;

        /** What a step cost on one thread when the pool went on trial; NaN when nothing is on trial. */
        private double trial = Double.NaN;

        private double tryAbove = TRY_ABOVE;

        public boolean pooled()
        {
            return this.pooled;
        }

        /** Takes what a step on the current choice cost, in milliseconds. */
        public void learn(double milliseconds)
        {
            double spike = Math.max(this.cost * 3D, 0.05D);

            this.cost = this.steps == 0 ? milliseconds : this.cost + (Math.min(milliseconds, spike) - this.cost) / 4D;
            this.steps += 1;

            if (this.steps < SETTLE)
            {
                return;
            }

            if (!this.pooled)
            {
                if (this.cost > this.tryAbove)
                {
                    this.trial = this.cost;
                    this.change(true);
                }
            }
            else if (!Double.isNaN(this.trial))
            {
                if (this.cost > this.trial * 0.9D)
                {
                    this.tryAbove = this.trial * 1.5D;
                    this.change(false);
                }

                this.trial = Double.NaN;
            }
            else if (this.cost < BACK_BELOW)
            {
                this.tryAbove = TRY_ABOVE;
                this.change(false);
            }
        }

        /** One thread, and everything learned forgotten — the pool was switched off or could not start. */
        public void reset()
        {
            this.change(false);
            this.trial = Double.NaN;
            this.tryAbove = TRY_ABOVE;
        }

        private void change(boolean pooled)
        {
            this.pooled = pooled;
            this.steps = 0;
        }
    }
}
