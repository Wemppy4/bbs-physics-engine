package mchorse.bbs_physics.client.forms;

import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.balloon.BalloonState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;

/**
 * Draws an inflated ball: the simulated mesh when a scene has one for this form, the perfect sphere
 * the author placed otherwise — the form editor's preview, the palette entry, and every frame the
 * recording has not reached (Р8.1). No canned pose here, unlike cloth: a sphere already looks like
 * what the form <em>is</em>.
 *
 * <p>The texture and blending machinery is {@link TexturedMeshFormRenderer}'s. Two things about the
 * mesh differ from cloth's because it is closed. Texture coordinates are laid per <em>face
 * corner</em> rather than per vertex: the seam meridian would otherwise unwind the whole texture
 * backwards across one cell, and the poles have no longitude of their own. And the normals radiate
 * from the centroid — a ball is convex from its middle even while dented, which spares the pole fans
 * any special casing.</p>
 */
public class BalloonFormRenderer extends TexturedMeshFormRenderer<BalloonForm>
{
    private final Vector3f centroid = new Vector3f();

    public BalloonFormRenderer(BalloonForm form)
    {
        super(form);
    }

    @Override
    protected void applyPreviewTransform(MatrixStack stack)
    {
        /* Fill the slot: the ball is centred on its origin, so it only needs scaling up. */
        float scale = 0.9F / Math.max(this.form.radius.get(), 0.1F);

        stack.scale(scale, scale, scale);
    }

    @Override
    protected int getVertexCount()
    {
        return this.form.getVertexCount();
    }

    /**
     * Where every vertex is for this frame, in the form's frame — the simulation's ball when the
     * scene has one, the author's sphere when it does not — and the centroid the normals radiate
     * from.
     */
    @Override
    protected void fillPositions(float transition)
    {
        int count = this.form.getVertexCount();
        BalloonState state = this.form.state;
        boolean simulated = state != null && state.isKnown() && state.getCount() == count;
        Vector3f point = new Vector3f();

        for (int i = 0; i < count; i++)
        {
            if (simulated)
            {
                this.positions[i * 3] = state.get(i, 0, transition);
                this.positions[i * 3 + 1] = state.get(i, 1, transition);
                this.positions[i * 3 + 2] = state.get(i, 2, transition);
            }
            else
            {
                this.form.spherePoint(i, point);

                this.positions[i * 3] = point.x;
                this.positions[i * 3 + 1] = point.y;
                this.positions[i * 3 + 2] = point.z;
            }
        }

        this.centroid.set(0F, 0F, 0F);

        for (int i = 0; i < count; i++)
        {
            this.centroid.add(this.positions[i * 3], this.positions[i * 3 + 1], this.positions[i * 3 + 2]);
        }

        this.centroid.div(count);
    }

    /**
     * Radiating from the centroid: a ball is convex from its middle even while dented, and the poles
     * need no longitude of their own this way.
     */
    @Override
    protected void normalAt(int i, float side, Vector3f out)
    {
        out.set(
            this.positions[i * 3] - this.centroid.x,
            this.positions[i * 3 + 1] - this.centroid.y,
            this.positions[i * 3 + 2] - this.centroid.z);

        if (out.lengthSquared() < 1e-16F)
        {
            out.set(0F, 1F, 0F);
        }
        else
        {
            out.normalize();
        }

        out.mul(side);
    }

    /**
     * The same fans and belts the rig builds, with texture coordinates laid per corner: u runs along
     * a ring without wrapping (the seam cell spans u1..1, not u1..0), v runs pole to pole, and a
     * pole takes the middle of its triangle's u span.
     */
    @Override
    protected void emit(MeshTarget target)
    {
        for (int pass = 0; pass < 2; pass++)
        {
            this.emitSide(target, pass == 0 ? 1F : -1F);
        }
    }

    private void emitSide(MeshTarget target, float side)
    {
        int segments = this.form.segments.get();

        /* The built ring count, not the authored one: a lopsided mesh is widened before it is
         * simulated (see BalloonForm.minimumRings), and a renderer drawing the authored one would
         * disagree with the simulation about how many vertices there are — which reads as the ball
         * never being simulated at all. */
        int rings = this.form.getRings();
        int south = this.form.getSouthPole();

        for (int s = 0; s < segments; s++)
        {
            float su1 = s / (float) segments;
            float su2 = (s + 1) / (float) segments;

            int a = 1 + s;
            int b = 1 + (s + 1) % segments;

            this.triangle(target, side,
                0, (su1 + su2) / 2F, 0F, b, su2, ringV(0, rings), a, su1, ringV(0, rings));

            int bottomA = 1 + (rings - 1) * segments + s;
            int bottomB = 1 + (rings - 1) * segments + (s + 1) % segments;

            this.triangle(target, side,
                south, (su1 + su2) / 2F, 1F, bottomA, su1, ringV(rings - 1, rings), bottomB, su2, ringV(rings - 1, rings));
        }

        for (int r = 0; r < rings - 1; r++)
        {
            float rv1 = ringV(r, rings);
            float rv2 = ringV(r + 1, rings);

            for (int s = 0; s < segments; s++)
            {
                float su1 = s / (float) segments;
                float su2 = (s + 1) / (float) segments;

                int tl = 1 + r * segments + s;
                int tr = 1 + r * segments + (s + 1) % segments;

                this.triangle(target, side, tl, su1, rv1, tr + segments, su2, rv2, tl + segments, su1, rv2);
                this.triangle(target, side, tl, su1, rv1, tr, su2, rv1, tr + segments, su2, rv2);
            }
        }
    }

    /** Where ring {@code r} sits on the texture, pole to pole. */
    private static float ringV(int r, int rings)
    {
        return (r + 1) / (float) (rings + 1);
    }

    private void triangle(MeshTarget target, float side,
        int i0, float u0, float v0, int i1, float u1, float v1, int i2, float u2, float v2)
    {
        if (side > 0F)
        {
            this.vertex(target, i0, u0, v0, side);
            this.vertex(target, i1, u1, v1, side);
            this.vertex(target, i2, u2, v2, side);
        }
        else
        {
            /* The same cells the other way round, so a translucent ball has an inside. */
            this.vertex(target, i2, u2, v2, side);
            this.vertex(target, i1, u1, v1, side);
            this.vertex(target, i0, u0, v0, side);
        }
    }
}
