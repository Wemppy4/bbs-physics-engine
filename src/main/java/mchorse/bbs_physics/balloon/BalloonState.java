package mchorse.bbs_physics.balloon;

/**
 * Where the simulation has a ball's vertices, in the two ticks the drawn frame sits between — the
 * balloon's {@code ClothState}, differing only in that a closed mesh is one flat run of vertices
 * rather than a grid.
 *
 * <p>Filled by the scene from the recording as each frame is distributed and read by the renderer;
 * runtime only, never saved. The vertices are in the <em>form's own frame</em>, converted at
 * record time (the recording's whole bargain: playback evaluates no poses). Null on a form no
 * scene has claimed — the form editor's preview — which is also what tells the renderer to draw
 * the perfect sphere.</p>
 */
public class BalloonState
{
    private final int count;

    private final float[] previous;
    private final float[] current;

    /** Whether the frame being drawn has a recorded answer at all (Р8.1: none means the sphere). */
    private boolean known;

    public BalloonState(int count)
    {
        this.count = count;
        this.previous = new float[count * 3];
        this.current = new float[count * 3];
    }

    public int getCount()
    {
        return this.count;
    }

    public boolean isKnown()
    {
        return this.known;
    }

    /**
     * Takes this frame's vertices, laid out {@code x y z} per vertex. {@code values} may be longer
     * than the mesh (the recording keeps its marker in the last float); only the mesh's worth is
     * read.
     *
     * @param teleport whether the film jumped rather than advanced one tick, in which case there
     *                 is no meaningful previous ball to be drawn sliding out of
     */
    public void set(float[] values, boolean teleport)
    {
        boolean had = this.known;

        System.arraycopy(this.current, 0, this.previous, 0, this.current.length);
        System.arraycopy(values, 0, this.current, 0, this.current.length);

        if (teleport || !had)
        {
            System.arraycopy(this.current, 0, this.previous, 0, this.current.length);
        }

        this.known = true;
    }

    /** This frame has no recorded answer: the renderer draws the perfect sphere instead. */
    public void setUnsimulated()
    {
        this.known = false;
    }

    /** One coordinate of one vertex, interpolated between the frame's two ticks. */
    public float get(int vertex, int axis, float transition)
    {
        int at = vertex * 3 + axis;

        return this.previous[at] + (this.current[at] - this.previous[at]) * transition;
    }
}
