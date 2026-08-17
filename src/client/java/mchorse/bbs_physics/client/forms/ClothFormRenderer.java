package mchorse.bbs_physics.client.forms;

import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.cloth.ClothState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;

/**
 * Draws a sheet of cloth: the simulated grid when a scene has one for this form, the flat rectangle
 * the author placed otherwise — the form editor's preview, and every frame the recording has not
 * reached (Р8.1).
 *
 * <p>The texture, the blending and the translucent-queue deferral are {@link
 * TexturedMeshFormRenderer}'s. What is here is the sheet: a grid of quads, and normals worked out
 * per vertex from the drape, because a bent sheet lit as if it were flat reads as flat.</p>
 */
public class ClothFormRenderer extends TexturedMeshFormRenderer<ClothForm>
{
    /**
     * Whether this is the palette's little preview rather than the film.
     *
     * <p>An unsimulated sheet is drawn flat, which is honest in the world — that is what the frame
     * really shows until the recording reaches it. In the palette it is useless: a flat rectangle
     * with a texture on it is a picture form, and the whole point of the entry is to say "this one
     * is cloth". So the preview is given a canned drape, folds and all, in place of the simulation
     * it cannot have.</p>
     */
    private boolean preview;

    private float[] normals = new float[0];

    public ClothFormRenderer(ClothForm form)
    {
        super(form);
    }

    @Override
    protected void applyPreviewTransform(MatrixStack stack)
    {
        stack.scale(1.5F, 1.5F, 1.5F);

        /* The sheet is drawn hanging from its top edge, so the pivot is at the top of it — shift it
         * up by half its height to sit in the middle of the slot it is previewed in. */
        stack.translate(0F, this.form.height.get() / 2F, 0F);
    }

    @Override
    protected void beforePreview()
    {
        this.preview = true;
    }

    @Override
    protected void afterPreview()
    {
        this.preview = false;
    }

    @Override
    protected int getVertexCount()
    {
        return this.form.getColumns() * this.form.getRows();
    }

    /**
     * Where every vertex is for this frame, in the form's frame: the simulation's drape when the
     * scene has one, the author's flat rectangle when it does not.
     */
    @Override
    protected void fillPositions(float transition)
    {
        int columns = this.form.getColumns();
        int rows = this.form.getRows();

        if (this.normals.length != this.positions.length)
        {
            this.normals = new float[this.positions.length];
        }

        ClothState state = this.form.state;
        boolean simulated = state != null && state.isKnown()
            && state.getColumns() == columns && state.getRows() == rows;

        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < columns; c++)
            {
                int i = r * columns + c;

                if (simulated)
                {
                    this.positions[i * 3] = state.get(i, 0, transition);
                    this.positions[i * 3 + 1] = state.get(i, 1, transition);
                    this.positions[i * 3 + 2] = state.get(i, 2, transition);
                }
                else if (this.preview)
                {
                    this.drape(c, r, columns, rows, i);
                }
                else
                {
                    this.positions[i * 3] = this.form.flatX(c);
                    this.positions[i * 3 + 1] = this.form.flatY(r);
                    this.positions[i * 3 + 2] = 0F;
                }
            }
        }

        this.fillNormals(columns, rows);
    }

    /**
     * The canned drape the palette entry is drawn in: a curtain hanging from its top edge in three
     * soft folds, gathered a little at the top and swinging free at the bottom.
     *
     * <p>Written out rather than simulated because the palette has no world, no tick and no
     * recording — and because an entry has to look the same every time it is drawn. The shape is a
     * cosine across the width for the folds, deepening towards the hem where a real sheet is least
     * constrained, with the width pinched in slightly as the fabric gathers.</p>
     */
    private void drape(int c, int r, int columns, int rows, int i)
    {
        float u = columns == 1 ? 0F : c / (float) (columns - 1);
        float v = rows == 1 ? 0F : r / (float) (rows - 1);

        /* Folds run down the sheet and open up towards the hem. */
        float depth = this.form.width.get() * 0.14F * v;
        float fold = (float) Math.cos(u * Math.PI * 3F);

        /* The fabric gathers, so the hanging sheet is a touch narrower than it is laid flat. */
        float pinch = 1F - 0.12F * v;

        this.positions[i * 3] = this.form.flatX(c) * pinch;
        this.positions[i * 3 + 1] = this.form.flatY(r);
        this.positions[i * 3 + 2] = fold * depth;
    }

    /**
     * A normal per vertex, from the grid's own neighbours: the cross of the run across and the run
     * down, which handles the edges by using whichever neighbour exists.
     */
    private void fillNormals(int columns, int rows)
    {
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < columns; c++)
            {
                int i = r * columns + c;

                int left = (c > 0 ? i - 1 : i) * 3;
                int right = (c < columns - 1 ? i + 1 : i) * 3;
                int up = (r > 0 ? i - columns : i) * 3;
                int down = (r < rows - 1 ? i + columns : i) * 3;

                float ax = this.positions[right] - this.positions[left];
                float ay = this.positions[right + 1] - this.positions[left + 1];
                float az = this.positions[right + 2] - this.positions[left + 2];

                float bx = this.positions[down] - this.positions[up];
                float by = this.positions[down + 1] - this.positions[up + 1];
                float bz = this.positions[down + 2] - this.positions[up + 2];

                float nx = ay * bz - az * by;
                float ny = az * bx - ax * bz;
                float nz = ax * by - ay * bx;

                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

                if (length < 1e-8F)
                {
                    nx = 0F;
                    ny = 0F;
                    nz = 1F;
                }
                else
                {
                    nx /= length;
                    ny /= length;
                    nz /= length;
                }

                this.normals[i * 3] = nx;
                this.normals[i * 3 + 1] = ny;
                this.normals[i * 3 + 2] = nz;
            }
        }
    }

    @Override
    protected void normalAt(int i, float side, Vector3f out)
    {
        out.set(this.normals[i * 3] * side, this.normals[i * 3 + 1] * side, this.normals[i * 3 + 2] * side);
    }

    /** Two triangles per cell, then the same two wound the other way so the sheet has a back. */
    @Override
    protected void emit(MeshTarget target)
    {
        int columns = this.form.getColumns();
        int rows = this.form.getRows();

        for (int r = 0; r < rows - 1; r++)
        {
            for (int c = 0; c < columns - 1; c++)
            {
                int tl = r * columns + c;
                int tr = tl + 1;
                int bl = tl + columns;
                int br = bl + 1;

                /* Front. */
                this.corner(target, bl, columns, rows, 1F);
                this.corner(target, br, columns, rows, 1F);
                this.corner(target, tl, columns, rows, 1F);

                this.corner(target, br, columns, rows, 1F);
                this.corner(target, tr, columns, rows, 1F);
                this.corner(target, tl, columns, rows, 1F);

                /* Back — the same cell the other way round. */
                this.corner(target, tl, columns, rows, -1F);
                this.corner(target, br, columns, rows, -1F);
                this.corner(target, bl, columns, rows, -1F);

                this.corner(target, tl, columns, rows, -1F);
                this.corner(target, tr, columns, rows, -1F);
                this.corner(target, br, columns, rows, -1F);
            }
        }
    }

    /** The texture is laid per vertex here — a grid has no seam to unwind across. */
    private void corner(MeshTarget target, int i, int columns, int rows, float side)
    {
        this.vertex(target, i, (i % columns) / (float) (columns - 1), (i / columns) / (float) (rows - 1), side);
    }
}
