package wemppy.bbs_physics.chain;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the simulation has a strand's segments, in the two ticks the drawn frame sits between —
 * the chain's equivalent of {@code ClothState}, with one difference: a rigid segment has an
 * orientation as well as a place, so each one is a position and a quaternion rather than a point.
 *
 * <p>Filled by the scene from the recording as each frame is distributed and read by the renderer;
 * runtime only, never saved. The segments are in the <em>form's own frame</em>, converted at
 * record time (the recording's whole bargain: playback evaluates no poses), so the renderer draws
 * them straight into the matrix stack it was already handed.</p>
 *
 * <p>Null on a form no scene has claimed — the form editor's preview, a film without physics —
 * which is also what tells the renderer to draw the straight, unsimulated strand.</p>
 */
public class ChainState
{
    /** Floats per segment: position, then the quaternion. */
    private static final int STRIDE = 7;

    private final int segments;

    private final float[] previous;
    private final float[] current;

    /** Whether the frame being drawn has a recorded answer at all (Р8.1: none means straight strand). */
    private boolean known;

    public ChainState(int segments)
    {
        this.segments = segments;
        this.previous = new float[segments * STRIDE];
        this.current = new float[segments * STRIDE];
    }

    public int getSegments()
    {
        return this.segments;
    }

    public boolean isKnown()
    {
        return this.known;
    }

    /**
     * Takes one segment of this frame's answer. Called for every segment of a tick, then sealed
     * with {@link #push(boolean)} — the two-step shape because the recording hands segments out
     * one channel at a time.
     */
    public void stage(int segment, Vector3f position, Quaternionf rotation)
    {
        int at = segment * STRIDE;

        this.current[at] = position.x;
        this.current[at + 1] = position.y;
        this.current[at + 2] = position.z;
        this.current[at + 3] = rotation.x;
        this.current[at + 4] = rotation.y;
        this.current[at + 5] = rotation.z;
        this.current[at + 6] = rotation.w;
    }

    /**
     * Declares the staged frame current, rolling what was current into the previous slot.
     *
     * @param teleport whether the film jumped rather than advanced one tick, in which case there
     *                 is no meaningful previous strand to be drawn sliding out of
     */
    public void push(boolean teleport)
    {
        if (teleport || !this.known)
        {
            System.arraycopy(this.current, 0, this.previous, 0, this.current.length);
        }

        this.known = true;
    }

    /** Rolls current into previous before a new frame is staged — the pair that {@link #push} completes. */
    public void roll()
    {
        System.arraycopy(this.current, 0, this.previous, 0, this.current.length);
    }

    /** This frame has no recorded answer: the renderer draws the straight strand instead. */
    public void setUnsimulated()
    {
        this.known = false;
    }

    /** One segment's centre, interpolated between the frame's two ticks. */
    public void getPosition(int segment, float transition, Vector3f out)
    {
        int at = segment * STRIDE;

        out.set(
            lerp(this.previous[at], this.current[at], transition),
            lerp(this.previous[at + 1], this.current[at + 1], transition),
            lerp(this.previous[at + 2], this.current[at + 2], transition));
    }

    /** One segment's orientation, interpolated between the frame's two ticks. */
    public void getRotation(int segment, float transition, Quaternionf out)
    {
        int at = segment * STRIDE + 3;

        out.set(this.previous[at], this.previous[at + 1], this.previous[at + 2], this.previous[at + 3]);

        if (out.lengthSquared() < 1e-6F)
        {
            out.identity();
        }

        Quaternionf target = new Quaternionf(this.current[at], this.current[at + 1], this.current[at + 2], this.current[at + 3]);

        if (target.lengthSquared() < 1e-6F)
        {
            target.identity();
        }

        out.slerp(target, transition);
    }

    private static float lerp(float a, float b, float x)
    {
        return a + (b - a) * x;
    }
}
