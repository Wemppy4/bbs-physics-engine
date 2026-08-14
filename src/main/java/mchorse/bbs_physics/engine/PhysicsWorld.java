package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.StateRecorderImpl;
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
     * How much of the step Jolt is allowed to re-solve. A film tick is 50 ms, which is long for a
     * solver aimed at 60 Hz frames, so it is cut in two — the cheapest way to keep stacked bodies
     * from sinking into each other. Fixed rather than adaptive on purpose: the number of collision
     * steps is part of the simulation's arithmetic, and a value that drifted with the frame rate
     * would make a film stop being reproducible.
     */
    public static final int COLLISION_STEPS = 2;

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

    public PhysicsWorld()
    {
        this.pairFilter = PhysicsLayers.newPairFilter();
        this.broadPhaseLayers = PhysicsLayers.newBroadPhaseLayers();
        this.broadPhaseFilter = PhysicsLayers.newBroadPhaseFilter(this.broadPhaseLayers, this.pairFilter);

        this.system = new PhysicsSystem();
        this.system.init(MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, this.broadPhaseLayers, this.broadPhaseFilter, this.pairFilter);
        this.system.setGravity(0F, -9.81F, 0F);
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

    public void setGravity(float x, float y, float z)
    {
        this.system.setGravity(x, y, z);
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
        this.system.update(TICK, COLLISION_STEPS, this.temp, this.jobs);
    }

    /**
     * The whole world's state as bytes: positions, velocities, sleeping flags, contacts, the lot.
     * Proven to round-trip exactly through a byte array, which is what lets a checkpoint be kept
     * for later instead of being restored immediately.
     */
    public byte[] saveState()
    {
        StateRecorderImpl recorder = new StateRecorderImpl();

        this.system.saveState(recorder);

        return recorder.getData();
    }

    /**
     * Puts the world back exactly as {@link #saveState()} found it. The same array can be restored
     * from more than once — a scrubbed timeline restores the same checkpoint over and over.
     *
     * @return whether Jolt accepted the state; false means the world no longer matches the
     *         snapshot's shape (bodies added or removed since) and the caller has to rebuild
     */
    public boolean restoreState(byte[] state)
    {
        StateRecorderImpl recorder = new StateRecorderImpl();

        recorder.writeBytes(state);

        return this.system.restoreState(recorder);
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

    public boolean isClosed()
    {
        return this.closed;
    }
}
