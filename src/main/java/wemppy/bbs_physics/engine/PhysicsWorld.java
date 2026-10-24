package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.StateRecorderImpl;
import com.github.stephengold.joltjni.TempAllocator;
import com.github.stephengold.joltjni.TempAllocatorImplWithMallocFallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One Jolt world. Everything physical in a scene lives in a single one of these and is stepped
 * together — that is the whole point of bringing an engine in: a body can only push another body
 * if they share a world.
 *
 * <p><b>Units are blocks.</b> Jolt is tuned for metres and stops behaving well below roughly ten
 * centimetres, so a block is fed to it as a metre. Model pixels (a sixteenth of a block) would put
 * small bodies right at the engine's resolution limit, where they jitter and sink through floors.
 * BBS's own bone physics already works this way — it divides its lengths by 16 everywhere.</p>
 *
 * <p>The world owns native memory, so it must be {@link #close() closed}. It also keeps its layer
 * tables in fields for the same reason: Jolt holds them by raw pointer, and letting the Java
 * objects be collected while the system is still running would pull the ground out from under it.</p>
 *
 * <p>A step may run in the background while the caller gets on with something else — see
 * {@link #beginStep}. Nothing may reach into Jolt meanwhile, so everything here that does waits for
 * that step first: a caller that forgets to wait costs a pause, never a crash.</p>
 */
public class PhysicsWorld implements AutoCloseable
{
    /**
     * How many times a film tick is split before Jolt solves it. A tick is 50 ms, which is long for
     * a solver aimed at 60 Hz frames, so it is cut up — the cheapest way to keep stacked bodies from
     * sinking into each other, and the difference between a contact that resolves smoothly and one
     * that arrives as a bang.
     *
     * <p>Three, to land on 60 Hz. BBS's own chain solver has always run three sub-steps per tick
     * and says why in its own comment: the in-between shapes are then actually simulated rather
     * than guessed from coarse 20 Hz snapshots. Simulating at 40 Hz where the thing an author is
     * comparing against runs at 60 is a step backwards they can see.</p>
     *
     * <p>Fixed rather than adaptive on purpose: the number of steps is part of the simulation's
     * arithmetic, and a value that drifted with the frame rate would make a film stop being
     * reproducible.</p>
     */
    public static final int COLLISION_STEPS = 3;

    /** Earth, in blocks per second squared — a block is a metre (§8). */
    public static final float EARTH_GRAVITY = 9.81F;

    /** A film tick in seconds, before the physics speed multiplier. */
    public static final float TICK = 1F / 20F;

    /** What an ordinary scene is sized for: actors, their ragdolls, ropes, sheets and props. */
    private static final int MAX_BODIES = 4096;
    private static final int MAX_BODY_PAIRS = 4096;
    private static final int MAX_CONTACTS = 2048;

    /**
     * Pairs and contacts on top of those for each piece of debris, which can all come loose at once
     * and lie in one heap. A block in a solid lattice touches 26 others, 13 pairs of its own; the
     * rest is the ground and whatever else it lands on. Measured in {@code stands/BigStructureSmoke}:
     * a solid 21×21×10 cube released whole needs 12, a wall three blocks thick 8. Short of them,
     * Jolt drops contacts and blocks fall through each other and through the floor.
     */
    private static final int CONTACTS_PER_DEBRIS = 16;

    /**
     * Jolt takes its whole contact buffer out of this on every step, so a large heap of debris needs
     * more than this — it then comes from malloc rather than from here, which measured no slower. A
     * plain allocator that runs out aborts the game outright.
     */
    private static final int TEMP_ALLOCATOR_BYTES = 8 * 1024 * 1024;
    static final int JOB_QUEUE = 2048;

    private final ObjectLayerPairFilterTable pairFilter;
    private final BroadPhaseLayerInterfaceTable broadPhaseLayers;
    private final ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter;

    private final PhysicsSystem system;
    private final BodyInterface bodies;
    private final TempAllocator temp;
    private final JobSystem jobs;

    /** Whether this world's steps go to the shared worker threads — see {@link PhysicsThreads}. */
    private final PhysicsThreads.Choice threads = new PhysicsThreads.Choice();

    /** What a step has been costing lately, in milliseconds, whatever it ran on — see {@link #getStepCost}. */
    private double cost;

    /** The step running in the background, or null — see {@link #beginStep}. */
    private Future<Long> running;

    /** How many bodies there were when that step started; none come or go during one. */
    private int runningBodies;

    /** Whether the step being taken runs on what {@link #threads} picked, so its time teaches the choice. */
    private boolean teaches;
    private boolean runningTeaches;

    private boolean closed;

    /** Scene-wide knobs, which are Blender's scene properties rather than per-body settings (§7.4). */
    private int collisionSteps = COLLISION_STEPS;
    private float speed = 1F;

    public PhysicsWorld()
    {
        this(0);
    }

    /**
     * @param debris bodies on top of an ordinary scene that may all come loose and pile up — a
     *               destructible structure's blocks. Jolt fixes how many bodies and contacts a world
     *               holds when it is made, so they are counted before it is.
     */
    public PhysicsWorld(int debris)
    {
        this.pairFilter = PhysicsLayers.newPairFilter();
        this.broadPhaseLayers = PhysicsLayers.newBroadPhaseLayers();
        this.broadPhaseFilter = PhysicsLayers.newBroadPhaseFilter(this.broadPhaseLayers, this.pairFilter);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES + debris, 0, MAX_BODY_PAIRS + debris * CONTACTS_PER_DEBRIS,
            MAX_CONTACTS + debris * CONTACTS_PER_DEBRIS, this.broadPhaseLayers, this.broadPhaseFilter, this.pairFilter);
        this.system.setGravity(0F, -EARTH_GRAVITY, 0F);
        this.bodies = this.system.getBodyInterface();

        this.temp = new TempAllocatorImplWithMallocFallback(TEMP_ALLOCATOR_BYTES);

        /* A light step is cheapest on the thread that asks for it; a heavy one goes to the shared
         * pool instead — see PhysicsThreads. Either way to the same bit. */
        this.jobs = new JobSystemSingleThreaded(JOB_QUEUE);
    }

    public PhysicsSystem getSystem()
    {
        this.finishStep();

        return this.system;
    }

    public BodyInterface getBodies()
    {
        this.finishStep();

        return this.bodies;
    }

    /** Answered without waiting for a step in the background: none adds or takes away a body. */
    public int getBodyCount()
    {
        return this.running != null ? this.runningBodies : this.system.getNumBodies();
    }

    /** Downwards, which is the only direction an author has ever asked for. */
    public void setGravity(float strength)
    {
        this.finishStep();
        this.system.setGravity(0F, -strength, 0F);
    }

    public void setCollisionSteps(int steps)
    {
        this.collisionSteps = Math.max(1, steps);
    }

    /**
     * How many pieces a tick is solved in. Asked for by the damping conversion, which has to know:
     * Jolt sheds a rigid body's speed once per sub-step, so the same rate bites harder the fewer
     * there are — see {@link PhysicsMath#bodyDamping}.
     */
    public int getCollisionSteps()
    {
        return this.collisionSteps;
    }

    /**
     * Tells Jolt to rebuild its broad phase tree from scratch. Worth calling once after a batch of
     * bodies has been added and not on every addition — it is an optimisation pass, not a
     * requirement.
     */
    public void optimize()
    {
        this.finishStep();
        this.system.optimizeBroadPhase();
    }

    public void setSpeed(float speed)
    {
        this.speed = Float.isFinite(speed) ? Math.max(0.1F, Math.min(4F, speed)) : 1F;
    }

    /** Physical seconds elapsed during one unchanged film tick. */
    public float getDeltaTime()
    {
        return TICK * this.speed;
    }

    public int getIntegrationSteps()
    {
        return Math.max(this.collisionSteps, (int) Math.ceil(this.collisionSteps * this.speed));
    }

    /**
     * Advances physics for one film tick at the selected speed, on one thread or on the shared
     * workers, whichever this world's steps have shown to be cheaper.
     */
    public void step()
    {
        this.finishStep();

        JobSystem jobs = this.jobs();
        long start = System.nanoTime();

        this.step(jobs);
        this.learn(System.nanoTime() - start, this.teaches);
    }

    /**
     * Starts the step {@link #step} would take and returns at once, the step running on the
     * background thread meanwhile — the caller draws a frame. {@link #finishStep} brings it in. The
     * same step to the bit: it is the same call, made from another thread, on a world nobody touches
     * until it is done. Taken on the spot when the background thread cannot be had.
     */
    public void beginStep()
    {
        this.finishStep();

        ExecutorService background = PhysicsThreads.background();

        if (background == null)
        {
            this.step();

            return;
        }

        JobSystem jobs = this.jobs();
        float delta = this.getDeltaTime();
        int steps = this.getIntegrationSteps();
        boolean pooled = jobs != this.jobs;

        this.runningBodies = this.system.getNumBodies();
        this.runningTeaches = this.teaches;

        if (pooled)
        {
            PhysicsThreads.poolToBackground();
        }

        try
        {
            this.running = background.submit(() ->
            {
                try
                {
                    long start = System.nanoTime();

                    this.system.update(delta, steps, this.temp, jobs);

                    return System.nanoTime() - start;
                }
                finally
                {
                    if (pooled)
                    {
                        PhysicsThreads.poolBack();
                    }
                }
            });
        }
        catch (RuntimeException e)
        {
            if (pooled)
            {
                PhysicsThreads.poolBack();
            }

            throw e;
        }
    }

    /** Waits for the step {@link #beginStep} started, when there is one. */
    public void finishStep()
    {
        Future<Long> running = this.running;

        if (running == null)
        {
            return;
        }

        try
        {
            this.learn(PhysicsThreads.await(running), this.runningTeaches);
        }
        finally
        {
            this.running = null;
        }
    }

    /** Whether a step is running in the background — see {@link #beginStep}. */
    public boolean isStepping()
    {
        return this.running != null;
    }

    /**
     * What the next step runs on: the shared workers when this world's steps have shown them cheaper
     * — unless another world's step has them in the background, in which case this one keeps to its
     * own thread and says nothing about the workers.
     */
    private JobSystem jobs()
    {
        this.teaches = true;

        if (!PhysicsThreads.allowed())
        {
            this.threads.reset();

            return this.jobs;
        }

        if (this.threads.pooled())
        {
            if (PhysicsThreads.poolInBackground())
            {
                this.teaches = false;

                return this.jobs;
            }

            JobSystem pool = PhysicsThreads.pool();

            if (pool != null)
            {
                return pool;
            }

            this.threads.reset();
        }

        return this.jobs;
    }

    private void learn(long nanoseconds, boolean teaches)
    {
        double milliseconds = nanoseconds / 1_000_000D;

        this.cost = PhysicsThreads.average(this.cost, milliseconds);

        if (teaches && PhysicsThreads.allowed())
        {
            this.threads.learn(milliseconds);
        }
    }

    /** Whether this world's steps go to the shared worker threads now. */
    public boolean isThreaded()
    {
        return this.threads.pooled();
    }

    /** What Jolt's part of a step has been costing lately, in milliseconds; 0 before the first. */
    public double getStepCost()
    {
        return this.cost;
    }

    /**
     * Advances physics for one film tick at the selected speed, its work done by {@code jobs}
     * whatever this world would have picked — for checks that pin it.
     */
    public void step(JobSystem jobs)
    {
        this.finishStep();
        this.system.update(this.getDeltaTime(), this.getIntegrationSteps(), this.temp, jobs);
    }

    /**
     * Everything Jolt itself keeps between steps — where every body is and how it moves, which
     * bodies sleep, the contacts it warm-starts from, the constraints' running state — as bytes.
     * Restored bit for bit by {@link #restoreState}; measured in {@code stands/CheckpointSmoke}.
     *
     * <p>Not in it: what a body <em>is</em> rather than what it is doing — its motion type, object
     * layer, shape and settings. Those belong to whoever set them, and are put back by them.</p>
     */
    public byte[] saveState()
    {
        this.finishStep();

        try (StateRecorderImpl recorder = new StateRecorderImpl())
        {
            this.system.saveState(recorder);

            return recorder.getData();
        }
    }

    /** Puts the world back to what {@link #saveState} wrote; false when Jolt refuses it. */
    public boolean restoreState(byte[] state)
    {
        this.finishStep();

        try (StateRecorderImpl recorder = new StateRecorderImpl())
        {
            recorder.writeBytes(state);

            return this.system.restoreState(recorder);
        }
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;

        /* Never out from under a step still running. What it threw matters no more; the wait does. */
        try
        {
            this.finishStep();
        }
        catch (RuntimeException e)
        {
            /* Closing regardless. */
        }

        /* Reverse order of construction: the system points at the filters, so it goes first. */
        this.system.close();
        this.jobs.close();
        this.temp.close();
        this.broadPhaseFilter.close();
        this.broadPhaseLayers.close();
        this.pairFilter.close();
    }
}
