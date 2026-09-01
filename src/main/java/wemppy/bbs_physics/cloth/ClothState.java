package wemppy.bbs_physics.cloth;

/**
 * Where the simulation has a sheet's vertices, in the two ticks the drawn frame sits between —
 * the cloth's equivalent of {@code PhysicsBodyState}.
 *
 * <p>Filled by the scene from the recording as each frame is distributed and read by the renderer;
 * runtime only, never saved — it is the result of the simulation, not a description of it. The
 * vertices are in the <em>form's own frame</em>, converted at record time (the recording's whole
 * bargain: playback evaluates no poses), so the renderer draws them straight into the matrix stack
 * it was already handed.</p>
 *
 * <p>Null on a form no scene has claimed — the form editor's preview, a film without physics —
 * which is also what tells the renderer to draw the flat, unsimulated sheet.</p>
 */
public class ClothState
{
    /** Vertices across and down — the grid the positions below are laid out in, row by row. */
    private final int columns;
    private final int rows;

    private final float[] previous;
    private final float[] current;

    /** Whether the frame being drawn has a recorded answer at all (Р8.1: none means flat sheet). */
    private boolean known;

    public ClothState(int columns, int rows)
    {
        this.columns = columns;
        this.rows = rows;
        this.previous = new float[columns * rows * 3];
        this.current = new float[columns * rows * 3];
    }

    public int getColumns()
    {
        return this.columns;
    }

    public int getRows()
    {
        return this.rows;
    }

    public boolean isKnown()
    {
        return this.known;
    }

    /**
     * Takes this frame's vertices, laid out {@code x y z} per vertex, row by row. {@code values}
     * may be longer than the grid (the recording keeps its marker in the last float); only the
     * grid's worth is read.
     *
     * @param teleport whether the film jumped rather than advanced one tick, in which case there
     *                 is no meaningful previous sheet to be drawn sliding out of
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

    /** This frame has no recorded answer: the renderer draws the flat sheet instead. */
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
