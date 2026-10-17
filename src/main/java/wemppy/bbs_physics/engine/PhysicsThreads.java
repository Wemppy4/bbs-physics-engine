package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Beside the pool, one more thread: the one a heavy scene's step runs on while the frame is drawn
 * — see {@link PhysicsWorld#beginStep}. The same switch lets both be used.</p>
 *
 * <p>Never closed, either of them. They live as long as the game, like the game's own worker pools,
 * and sleep while nothing is stepped; closing them on the way out would race a step still in
 * flight.</p>
 */
public final class PhysicsThreads
{
    /** Jolt's own figure for a physics system's barriers. */
    private static final int MAX_BARRIERS = 8;

    private static JobSystemThreadPool pool;
    private static boolean unavailable;

    private static ExecutorService background;
    private static boolean backgroundUnavailable;

    /**
     * How many steps on the pool are running in the background right now. A step taken on the spot
     * meanwhile — another scene's — keeps off the pool: two updates sharing one pool at once is not
     * something to lean on, and the other scene is the light one more often than not.
     */
    private static final AtomicInteger POOL_IN_BACKGROUND = new AtomicInteger();

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
        return !unavailable && switchedOn() && workers() > 0;
    }

    /**
     * Whether a step may run in the background while the frame is drawn — see
     * {@link PhysicsWorld#beginStep}: the same switch, and a machine with a core to spare for it.
     */
    public static boolean backgroundAllowed()
    {
        return !backgroundUnavailable && switchedOn() && Runtime.getRuntime().availableProcessors() > 2;
    }

    private static boolean switchedOn()
    {
        return BBSPhysicsSettings.threads == null || BBSPhysicsSettings.threads.get();
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
     * The thread a step runs on while the frame is drawn, started on first use; null when it cannot
     * be had, and then never asked for again.
     */
    static synchronized ExecutorService background()
    {
        if (background == null && !backgroundUnavailable)
        {
            try
            {
                background = Executors.newSingleThreadExecutor((task) ->
                {
                    Thread thread = new Thread(task, "BBS Physics step");

                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY);

                    return thread;
                });
            }
            catch (Throwable e)
            {
                backgroundUnavailable = true;

                BBSPhysics.LOGGER.warn("The physics background thread could not be started; every step is taken on the thread that asks for it.", e);
            }
        }

        return background;
    }

    /** Notes a step on the pool going to the background; the step itself notes when it is done. */
    static void poolToBackground()
    {
        POOL_IN_BACKGROUND.incrementAndGet();
    }

    static void poolBack()
    {
        POOL_IN_BACKGROUND.decrementAndGet();
    }

    /** Whether a step on the pool is running in the background, so a step taken on the spot keeps off it. */
    static boolean poolInBackground()
    {
        return POOL_IN_BACKGROUND.get() > 0;
    }

    /**
     * Waits for a step started in the background and answers how long its physics took, in
     * nanoseconds. Waits it out whatever interrupts the wait — the world cannot be touched before
     * the step is done — and passes on whatever the step threw.
     */
    static long await(Future<Long> step)
    {
        boolean interrupted = false;

        try
        {
            while (true)
            {
                try
                {
                    return step.get();
                }
                catch (InterruptedException e)
                {
                    interrupted = true;
                }
                catch (ExecutionException e)
                {
                    if (e.getCause() instanceof RuntimeException cause)
                    {
                        throw cause;
                    }

                    if (e.getCause() instanceof Error cause)
                    {
                        throw cause;
                    }

                    throw new IllegalStateException("A physics step failed in the background", e.getCause());
                }
            }
        }
        finally
        {
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A running average of what steps cost, {@code sample} taken in: a quarter of the way towards it,
     * a lone spike cut down to three times the average first. Starts at the first sample.
     */
    public static double average(double average, double sample)
    {
        return average <= 0D ? sample : average + (Math.min(sample, Math.max(average * 3D, 0.05D)) - average) / 4D;
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
            this.cost = this.steps == 0 ? milliseconds : average(this.cost, milliseconds);
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
