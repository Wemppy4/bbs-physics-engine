package mchorse.bbs_physics.engine;

import java.util.function.IntConsumer;

/**
 * The clock of one physics world: which film tick it stands on, and the one operation that moves it
 * — a single step forward.
 *
 * <p>It used to be much more than this. Physics was treated as an instant function of the cursor,
 * so the world had to be able to <em>arrive</em> at any tick from any other: snapshots every ten
 * ticks, a rewind that restored the nearest one, a re-simulation of the remainder under a step
 * budget, and a flag for when that budget ran out mid-jump. All of it existed to make a sequence
 * randomly addressable, and all of it is gone (§2.5, §6): the simulation is recorded into
 * {@link PhysicsCache} as it goes and the film is drawn from the recording, so nothing ever needs
 * to arrive anywhere. The world only ever moves forwards, one tick at a time, exactly like the film
 * it belongs to.</p>
 *
 * <p>{@link PhysicsWorld#saveState()} still exists and still round-trips exactly — it is cheap and
 * it will be wanted the day a scene needs to be resumed rather than replayed. It is simply no
 * longer on the path a drawn frame takes.</p>
 */
public class PhysicsTimeline
{
    private final PhysicsWorld world;

    private int tick;

    public PhysicsTimeline(PhysicsWorld world)
    {
        this.world = world;
    }

    /** The tick the world stands on: the last one that was simulated. */
    public int getTick()
    {
        return this.tick;
    }

    /**
     * Declares the world as it stands to be tick 0 — the film's opening frame, which is what a
     * scene is always assembled as, whatever the cursor happened to be on.
     */
    public void start()
    {
        this.tick = 0;
    }

    /**
     * Simulates the next tick and returns it.
     *
     * <p>{@code pose} stands the animated part of the film on the tick that is <em>about to be</em>
     * simulated, never the one being left. Simulating twenty ticks against the pose of the
     * twentieth means the hand that was pushing a crate through those ticks is already at its
     * destination and pushes nothing; that lesson cost a whole stage (Э1.6) and it survives the
     * change of contract intact — the recording has to be the film, tick for tick.</p>
     */
    public int step(IntConsumer pose)
    {
        pose.accept(this.tick + 1);

        this.world.step();

        this.tick += 1;

        return this.tick;
    }
}
