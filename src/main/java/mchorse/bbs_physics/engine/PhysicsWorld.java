package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.TempAllocator;
import com.github.stephengold.joltjni.TempAllocatorImpl;

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

    /** A film tick, in seconds — the step this world always advances by. */
    public static final float TICK = 1F / 20F;

    private static final int MAX_BODIES = 4096;
    private static final int MAX_BODY_PAIRS = 4096;
    private static final int MAX_CONTACTS = 2048;
    private static final int TEMP_ALLOCATOR_BYTES = 8 * 1024 * 1024;
    private static final int JOB_QUEUE = 2048;

    private final ObjectLayerPairFilterTable pairFilter;
    private final BroadPhaseLayerInterfaceTable broadPhaseLayers;
    private final ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter;

    private final PhysicsSystem system;
    private final BodyInterface bodies;
    private final TempAllocator temp;
    private final JobSystem jobs;

    private boolean closed;

    /** Scene-wide knobs, which are Blender's scene properties rather than per-body settings (§7.4). */
    private int collisionSteps = COLLISION_STEPS;

    public PhysicsWorld()
    {
        this.pairFilter = PhysicsLayers.newPairFilter();
        this.broadPhaseLayers = PhysicsLayers.newBroadPhaseLayers();
        this.broadPhaseFilter = PhysicsLayers.newBroadPhaseFilter(this.broadPhaseLayers, this.pairFilter);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, this.broadPhaseLayers, this.broadPhaseFilter, this.pairFilter);
        this.system.setGravity(0F, -EARTH_GRAVITY, 0F);
        this.bodies = this.system.getBodyInterface();

        this.temp = new TempAllocatorImpl(TEMP_ALLOCATOR_BYTES);

        /* Single-threaded deliberately, for now. Jolt claims the same result whatever the thread
         * count, but a film has to look identical on every machine that opens it, and that claim
         * is not something to build on before it has been measured. A scene of a few hundred
         * bodies costs nothing on one thread anyway. */
        this.jobs = new JobSystemSingleThreaded(JOB_QUEUE);
    }

    public PhysicsSystem getSystem()
    {
        return this.system;
    }

    public BodyInterface getBodies()
    {
        return this.bodies;
    }

    public int getBodyCount()
    {
        return this.system.getNumBodies();
    }

    /** Downwards, which is the only direction an author has ever asked for. */
    public void setGravity(float strength)
    {
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
        this.system.optimizeBroadPhase();
    }

    /** Advances the world by exactly one film tick. */
    public void step()
    {
        this.system.update(TICK, this.collisionSteps, this.temp, this.jobs);
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;

        /* Reverse order of construction: the system points at the filters, so it goes first. */
        this.system.close();
        this.jobs.close();
        this.temp.close();
        this.broadPhaseFilter.close();
        this.broadPhaseLayers.close();
        this.pairFilter.close();
    }
}
