package mchorse.bbs_physics.engine;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * The film's physics as a <em>recording</em>: what every simulated thing was doing on every tick,
 * worked out once going forwards and read back thereafter.
 *
 * <p>This is the contract the addon took from Blender (§2.5, §6 of the concept), and it is the
 * reason half of this project's difficulty went away. Physics is a sequence — tick 300 is a
 * consequence of the 299 ticks before it — and the previous design tried to make a sequence
 * randomly addressable: snapshots every ten ticks, a rewind that restored the nearest one, a
 * re-simulation of the remainder inside a step budget, and an entire class of bugs about the world
 * standing on a different tick than the film. A recording has none of that. Scrubbing reads an
 * array; there is no "arrived by a different route", because there is only one route.</p>
 *
 * <p><b>What a channel is.</b> Everything that has an answer per tick registers one: a physics body
 * form, one bone of a ragdoll, one debug body. A channel stores a position and a rotation — and one
 * extra number, which is the only thing that is not geometry. That is the authority handle as it
 * stood on the simulated tick, and it is cached rather than read from the form because the form's
 * keyframed values are written at draw time on a fractional tick; the recording has to say what was
 * true when the tick was <em>simulated</em>.</p>
 *
 * <p><b>What is not cached is the reason this is cheap.</b> The frames a body's answer is expressed
 * in — actor-local for a body, the model's group space for a ragdoll bone — depend only on the tick,
 * so the conversion is done once during recording and the numbers stored are the ones the renderer
 * substitutes directly. Playing back a recorded film therefore evaluates no poses at all.</p>
 *
 * <p>A tick costs {@value #FLOATS} floats per channel: thirty-two bytes. A thousand-tick film with
 * thirty channels is under a megabyte, which is why the whole thing can simply be kept.</p>
 */
public class PhysicsCache
{
    /** Position, rotation, and the authority the tick was simulated under. */
    public static final int FLOATS = 8;

    /**
     * The authority written for a channel that has nothing to say on a tick — a ragdoll whose model
     * had not loaded when that tick was simulated, say.
     *
     * <p>Silence has to be written rather than skipped. The array outlives an invalidation (only
     * the counter is reset, so that re-recording does not reallocate), which means an untouched
     * slot still holds whatever the previous run put there — and a reader cannot tell the
     * difference between "nobody wrote this" and "this is the answer". A negative authority is
     * impossible as a real value, so it makes an unambiguous marker.</p>
     */
    public static final float SILENT = -1F;

    /**
     * The ceiling on a recording, in bytes. A film long enough to reach it is far longer than
     * anything BBS is used for; the cap exists so that a runaway lookahead cannot eat the heap.
     * Past it the recording simply stops growing and the frames beyond show plain animation.
     */
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private int channels;
    private boolean sealed;

    private float[] data = new float[0];

    /** How many ticks the array has room for, and how many are actually recorded. */
    private int capacity;
    private int computed;

    /**
     * Claims a channel. Called while the scene is being built, once per thing that will have an
     * answer per tick; the index returned is how that thing addresses itself for the rest of the
     * scene's life.
     */
    public int addChannel()
    {
        if (this.sealed)
        {
            throw new IllegalStateException("Physics cache channels are fixed once the scene is built.");
        }

        return this.channels++;
    }

    /** No more channels: the scene is assembled and the recording can start. */
    public void seal()
    {
        this.sealed = true;
    }

    public int getChannels()
    {
        return this.channels;
    }

    /** How many ticks are recorded — ticks {@code 0} to {@code getComputed() - 1}. */
    public int getComputed()
    {
        return this.computed;
    }

    public boolean has(int tick)
    {
        return tick >= 0 && tick < this.computed;
    }

    /** The last tick this recording can ever hold, given the memory ceiling. */
    public int getLimit()
    {
        if (this.channels <= 0)
        {
            /* Nothing is stored per tick, so nothing limits how many ticks there can be.
             *
             * Zero was the arithmetic's honest answer to "how many ticks fit in the ceiling" and a
             * disaster as an answer to "may this tick be recorded": a scene with nothing simulated
             * in it — a film whose models had not finished loading when it was assembled — refused
             * to record its very first tick, reported itself as a recording that had run out of
             * room, and left the readout saying "this frame is not computed yet" for the rest of
             * the film. Which is the single most misleading thing this addon can say, because it is
             * word for word what a scene that is merely behind says. */
            return Integer.MAX_VALUE;
        }

        return MAX_BYTES / (this.channels * FLOATS * 4);
    }

    /**
     * Throws the recording away. Every edit to the film does this — physics is a consequence of
     * numbers that have just changed, so nothing worked out from the old ones survives. It is a
     * counter, which is the point: invalidation used to mean re-simulating up to the cursor on the
     * spot, and now it means the bar under the timeline turns grey while the background catches up.
     */
    public void clear()
    {
        this.computed = 0;
    }

    /**
     * Whether {@code tick} can be recorded at all — within the ceiling, and the next tick in line.
     * Recording is strictly sequential: a gap would be a frame nobody could ever fill, since tick
     * {@code n} can only be produced by having simulated {@code n - 1}.
     */
    public boolean canWrite(int tick)
    {
        return tick == this.computed && tick < this.getLimit();
    }

    /**
     * Writes one channel's answer for a tick that is being recorded. Call {@link #commit(int)} once
     * every channel of that tick has been written — a tick is only readable when it is whole.
     */
    public void write(int tick, int channel, Vector3f position, Quaternionf rotation, float authority)
    {
        if (channel < 0 || channel >= this.channels || !this.canWrite(tick))
        {
            return;
        }

        this.ensureCapacity(tick + 1);

        int at = (tick * this.channels + channel) * FLOATS;

        this.data[at] = position.x;
        this.data[at + 1] = position.y;
        this.data[at + 2] = position.z;
        this.data[at + 3] = rotation.x;
        this.data[at + 4] = rotation.y;
        this.data[at + 5] = rotation.z;
        this.data[at + 6] = rotation.w;
        this.data[at + 7] = authority;
    }

    /** The tick is complete: everything after this point may read it. */
    public void commit(int tick)
    {
        if (tick == this.computed)
        {
            this.computed = tick + 1;
        }
    }

    /**
     * Reads a channel's answer into the outputs.
     *
     * @return false when the tick is not recorded, or when this channel had nothing to say on it,
     *         in which case the outputs are untouched — the caller shows plain animation for that
     *         frame (Р8.1)
     */
    public boolean read(int tick, int channel, Vector3f position, Quaternionf rotation)
    {
        int at = this.at(tick, channel);

        if (at < 0 || this.data[at + 7] == SILENT)
        {
            return false;
        }

        position.set(this.data[at], this.data[at + 1], this.data[at + 2]);
        rotation.set(this.data[at + 3], this.data[at + 4], this.data[at + 5], this.data[at + 6]);

        return true;
    }

    /** The authority this channel was simulated under on {@code tick}, or 1 when unrecorded. */
    public float readAuthority(int tick, int channel)
    {
        int at = this.at(tick, channel);

        return at < 0 ? 1F : this.data[at + 7];
    }

    /**
     * Where a channel's floats sit for a tick, or -1 when there are none. Guards the array length
     * as well as the tick: a tick can be committed before every channel has written into it — a
     * ragdoll that produced nothing does not grow the array — so the bound is not implied by
     * {@link #has(int)}.
     */
    private int at(int tick, int channel)
    {
        if (!this.has(tick) || channel < 0 || channel >= this.channels)
        {
            return -1;
        }

        int at = (tick * this.channels + channel) * FLOATS;

        return at + FLOATS > this.data.length ? -1 : at;
    }

    /**
     * Grows the array to hold {@code ticks} ticks. Doubling rather than exact growth: a recording
     * is filled a tick at a time, and copying the whole thing on every one of them would make the
     * background catch-up quadratic in the length of the film.
     */
    private void ensureCapacity(int ticks)
    {
        if (ticks <= this.capacity)
        {
            return;
        }

        int wanted = Math.max(this.capacity * 2, Math.max(ticks, 256));
        int limit = this.getLimit();

        if (limit > 0)
        {
            wanted = Math.min(wanted, limit);
        }

        this.data = Arrays.copyOf(this.data, wanted * this.channels * FLOATS);
        this.capacity = wanted;
    }
}
