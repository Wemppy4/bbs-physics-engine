package wemppy.bbs_physics.client.collision;

import mchorse.bbs_mod.graphics.Draw;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.collision.CollisionKind;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;


/**
 * Draws a collision shape as an outline, centred on the current stack frame.
 *
 * <p>A round shape is drawn round. That sounds obvious and it is the whole point: an overlay
 * exists so an author can compare what the engine collides with against what the model looks like,
 * and a capsule shown as its bounding box is wrong at exactly the places a capsule is chosen for —
 * the shoulders and the knees, where the box has corners the capsule does not.</p>
 *
 * <p>Boxes keep BBS's own thick-wire look ({@link Draw#renderBox}), because that is what the rest
 * of the overlay has always drawn. Round shapes, joints and impulse markers use geometry bars too,
 * so the thickness setting works without relying on the driver's support for wide GL lines.</p>
 */
public final class CollisionWireframe
{
    /** Segments per full ring. Enough to read as a circle at arm's length, cheap enough to spam. */
    private static final int SEGMENTS = 24;

    /**
     * Half thickness of an outline bar at the setting's default, in blocks — a third of what BBS's
     * own box draws, which is what "thinner by default" asked for.
     */
    private static final float BASE_THICKNESS = 1F / 288F;

    /** Below this a bar disappears into the depth buffer at any distance, setting or no setting. */
    private static final float MIN_THICKNESS = 0.0006F;

    private CollisionWireframe()
    {}

    /** A shape as the collector described it — a plate is outlined on the pixels it stands on. */
    public static void draw(MatrixStack stack, CollisionShapes.SubShape shape, float red, float green, float blue, float alpha)
    {
        draw(stack, shape.kind(), shape.half(), shape.surface(), red, green, blue, alpha);
    }

    public static void draw(MatrixStack stack, CollisionKind kind, Vector3f half, float red, float green, float blue, float alpha)
    {
        draw(stack, kind, half, 0F, red, green, blue, alpha);
    }

    /** @param surface the painted side of a plate — see {@code CollisionShapes.SubShape.surface} */
    public static void draw(MatrixStack stack, CollisionKind kind, Vector3f half, float surface, float red, float green, float blue, float alpha)
    {
        switch (kind)
        {
            case BOX -> box(stack, half, surface, red, green, blue, alpha);
            case SPHERE -> sphere(stack, half.x, red, green, blue, alpha);
            case CAPSULE -> capsule(stack, half.x, half.y, red, green, blue, alpha);
            case CYLINDER -> cylinder(stack, half.x, half.y, red, green, blue, alpha);
        }
    }

    /**
     * A box as twelve edge bars, thin enough to read as lines.
     *
     * <p>Written out here rather than handed to {@link Draw#renderBox} for one reason: BBS's own
     * box bakes its bar thickness in (a ninety-sixth of a block, plus a little for size), and that
     * is thicker than some of the shapes this overlay now draws. A face plate is a quarter of a
     * pixel through — outlined with BBS's bars it reads as a narrow <em>box</em>, which is exactly
     * what an author reported. So the thickness is ours, and a setting: fine lines to inspect a
     * thin plate against the model, heavier ones to see a rig across a room.</p>
     *
     * <p>Bars rather than GL lines, and not by choice: a core profile is free to ignore any line
     * width but one, so a line-width setting would do nothing on most machines. Bars are geometry
     * — they scale, and they respect perspective the way the shape they outline does.</p>
     */
    public static void box(MatrixStack stack, Vector3f half, float surface, float red, float green, float blue, float alpha)
    {
        boolean flatten = BBSPhysicsSettings.debugFlatPlates == null || BBSPhysicsSettings.debugFlatPlates.get();
        int flat = flatten ? flatAxis(half) : -1;

        if (flat >= 0)
        {
            plate(stack, half, flat, surface, red, green, blue, alpha);

            return;
        }

        float t = thickness(half);

        float x1 = -half.x;
        float y1 = -half.y;
        float z1 = -half.z;
        float x2 = half.x;
        float y2 = half.y;
        float z2 = half.z;

        BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        /* Four uprights, then the two rings that cap them. Each bar is grown by t on every side, so
         * the corners overlap and there are no gaps to look through. */
        Draw.fillBox(builder, stack, x1 - t, y1 - t, z1 - t, x1 + t, y2 + t, z1 + t, red, green, blue, alpha);
        Draw.fillBox(builder, stack, x2 - t, y1 - t, z1 - t, x2 + t, y2 + t, z1 + t, red, green, blue, alpha);
        Draw.fillBox(builder, stack, x1 - t, y1 - t, z2 - t, x1 + t, y2 + t, z2 + t, red, green, blue, alpha);
        Draw.fillBox(builder, stack, x2 - t, y1 - t, z2 - t, x2 + t, y2 + t, z2 + t, red, green, blue, alpha);

        for (float y : new float[] {y1, y2})
        {
            Draw.fillBox(builder, stack, x1 - t, y - t, z1 - t, x2 + t, y + t, z1 + t, red, green, blue, alpha);
            Draw.fillBox(builder, stack, x1 - t, y - t, z2 - t, x2 + t, y + t, z2 + t, red, green, blue, alpha);
            Draw.fillBox(builder, stack, x1 - t, y - t, z1 - t, x1 + t, y + t, z2 + t, red, green, blue, alpha);
            Draw.fillBox(builder, stack, x2 - t, y - t, z1 - t, x2 + t, y + t, z2 + t, red, green, blue, alpha);
        }

        WireframeLayers.flushTriangles(builder);
    }

    /**
     * Which axis a box is a plate along — the one no thicker than a model pixel — or −1 for a box
     * that is a box.
     */
    private static int flatAxis(Vector3f half)
    {
        /* Up to a pixel through: the author can thicken a plate, and it stays a plate. */
        float limit = 1F / CollisionShapes.PIXELS / 2F;

        if (half.x <= limit && half.x <= half.y && half.x <= half.z) return 0;
        if (half.y <= limit && half.y <= half.x && half.y <= half.z) return 1;
        if (half.z <= limit) return 2;

        return -1;
    }

    /**
     * A plate as a flat rectangle — four bars that have width in the plane and next to no depth
     * across it — drawn on the plate's <em>painted</em> side ({@code surface}, see {@code
     * SubShape.surface}), which is the pixels it collides for; in its mid-plane when it has no
     * such side.
     *
     * <p>A plate is a surface as far as an author is concerned, and twelve bars around a
     * quarter-pixel box standing a quarter pixel off the model showed a box floating beside the
     * hair, which is the one thing the overlay must not suggest. A setting ({@code
     * debug_flat_plates}, on by default) turns it off for whoever wants to see the sliver itself.
     * The little depth the bars keep is only so they do not vanish into the depth buffer
     * edge-on.</p>
     */
    private static void plate(MatrixStack stack, Vector3f half, int axis, float surface, float red, float green, float blue, float alpha)
    {
        float scale = BBSPhysicsSettings.debugLineWidth == null ? 1F : BBSPhysicsSettings.debugLineWidth.get();
        float t = Math.max(BASE_THICKNESS * scale, MIN_THICKNESS);
        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        float hu = half.get(u);
        float hv = half.get(v);
        float at = Math.signum(surface) * half.get(axis);

        BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        /* Two bars along u at ±hv, two along v at ±hu, each grown by t in the plane so the corners
         * meet, and by the bare minimum across it. */
        bar(builder, stack, axis, u, v, -hu - t, hu + t, -hv - t, -hv + t, at, red, green, blue, alpha);
        bar(builder, stack, axis, u, v, -hu - t, hu + t, hv - t, hv + t, at, red, green, blue, alpha);
        bar(builder, stack, axis, u, v, -hu - t, -hu + t, -hv - t, hv + t, at, red, green, blue, alpha);
        bar(builder, stack, axis, u, v, hu - t, hu + t, -hv - t, hv + t, at, red, green, blue, alpha);

        WireframeLayers.flushTriangles(builder);
    }

    /** A box given by its extents along the plate's two in-plane axes, {@code at} along the third. */
    private static void bar(BufferBuilder builder, MatrixStack stack, int axis, int u, int v, float u1, float u2, float v1, float v2, float at, float red, float green, float blue, float alpha)
    {
        float[] min = new float[3];
        float[] max = new float[3];

        min[u] = u1;
        max[u] = u2;
        min[v] = v1;
        max[v] = v2;
        min[axis] = at - MIN_THICKNESS;
        max[axis] = at + MIN_THICKNESS;

        Draw.fillBox(builder, stack, min[0], min[1], min[2], max[0], max[1], max[2], red, green, blue, alpha);
    }

    /**
     * Half the thickness of an outline bar, in blocks.
     *
     * <p>The author's setting scales a base that is deliberately finer than BBS's, and the bar is
     * additionally never allowed past a third of the shape's own smallest half extent — outlining a
     * quarter-pixel plate with a bar thicker than the plate is how the outline starts lying about
     * the shape.</p>
     */
    private static float thickness(Vector3f half)
    {
        float scale = BBSPhysicsSettings.debugLineWidth == null ? 1F : BBSPhysicsSettings.debugLineWidth.get();
        float smallest = Math.min(half.x, Math.min(half.y, half.z));

        return Math.max(Math.min(BASE_THICKNESS * scale, smallest / 3F), MIN_THICKNESS);
    }

    private static void sphere(MatrixStack stack, float radius, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();

        ring(builder, stack, Axis.Y, radius, 0F, red, green, blue, alpha);
        ring(builder, stack, Axis.X, radius, 0F, red, green, blue, alpha);
        ring(builder, stack, Axis.Z, radius, 0F, red, green, blue, alpha);

        end(builder);
    }

    private static void cylinder(MatrixStack stack, float radius, float halfHeight, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();

        ring(builder, stack, Axis.Y, radius, -halfHeight, red, green, blue, alpha);
        ring(builder, stack, Axis.Y, radius, halfHeight, red, green, blue, alpha);
        rails(builder, stack, radius, -halfHeight, halfHeight, red, green, blue, alpha);

        end(builder);
    }

    /** {@code halfHeight} is the straight part, as Jolt measures it — the caps sit on top of it. */
    private static void capsule(MatrixStack stack, float radius, float halfHeight, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();

        ring(builder, stack, Axis.Y, radius, -halfHeight, red, green, blue, alpha);
        ring(builder, stack, Axis.Y, radius, halfHeight, red, green, blue, alpha);
        rails(builder, stack, radius, -halfHeight, halfHeight, red, green, blue, alpha);

        cap(builder, stack, radius, halfHeight, 1F, red, green, blue, alpha);
        cap(builder, stack, radius, -halfHeight, -1F, red, green, blue, alpha);

        end(builder);
    }

    /** The two half circles that close a capsule's end, drawn in the XY and ZY planes. */
    private static void cap(BufferBuilder builder, MatrixStack stack, float radius, float centre, float direction, float red, float green, float blue, float alpha)
    {
        for (int i = 0; i < SEGMENTS / 2; i++)
        {
            double a = Math.PI * i / (SEGMENTS / 2D);
            double b = Math.PI * (i + 1) / (SEGMENTS / 2D);

            float ax = (float) Math.cos(a) * radius;
            float ay = (float) Math.sin(a) * radius * direction;
            float bx = (float) Math.cos(b) * radius;
            float by = (float) Math.sin(b) * radius * direction;

            line(builder, stack, ax, centre + ay, 0F, bx, centre + by, 0F, red, green, blue, alpha);
            line(builder, stack, 0F, centre + ay, ax, 0F, centre + by, bx, red, green, blue, alpha);
        }
    }

    /** The four straight lines down the sides of a cylinder or a capsule. */
    private static void rails(BufferBuilder builder, MatrixStack stack, float radius, float bottom, float top, float red, float green, float blue, float alpha)
    {
        line(builder, stack, radius, bottom, 0F, radius, top, 0F, red, green, blue, alpha);
        line(builder, stack, -radius, bottom, 0F, -radius, top, 0F, red, green, blue, alpha);
        line(builder, stack, 0F, bottom, radius, 0F, top, radius, red, green, blue, alpha);
        line(builder, stack, 0F, bottom, -radius, 0F, top, -radius, red, green, blue, alpha);
    }

    private enum Axis
    {
        X, Y, Z
    }

    /** A full circle around {@code axis}, {@code offset} along it. */
    private static void ring(BufferBuilder builder, MatrixStack stack, Axis axis, float radius, float offset, float red, float green, float blue, float alpha)
    {
        float previousU = radius;
        float previousV = 0F;

        for (int i = 1; i <= SEGMENTS; i++)
        {
            double angle = Math.PI * 2D * i / SEGMENTS;
            float u = (float) Math.cos(angle) * radius;
            float v = (float) Math.sin(angle) * radius;

            switch (axis)
            {
                case X -> line(builder, stack, offset, previousU, previousV, offset, u, v, red, green, blue, alpha);
                case Y -> line(builder, stack, previousU, offset, previousV, u, offset, v, red, green, blue, alpha);
                case Z -> line(builder, stack, previousU, previousV, offset, u, v, offset, red, green, blue, alpha);
            }

            previousU = u;
            previousV = v;
        }
    }

    /** A line with the same world-space thickness as the box outlines. */
    static void line(BufferBuilder builder, MatrixStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float red, float green, float blue, float alpha)
    {
        if (x1 == x2 && y1 == y2 && z1 == z2)
        {
            return;
        }

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float halfWidth = lineThickness() / 2F;

        stack.push();
        stack.translate(x1, y1, z1);
        /* Rotate the bar's +Z axis directly onto the segment. Camera yaw conventions do not
         * describe this rotation. Extend both ends so adjacent bars meet around a bend. */
        stack.multiply(new Quaternionf().rotationTo(0F, 0F, 1F, dx / length, dy / length, dz / length));
        Draw.fillBox(builder, stack, -halfWidth, -halfWidth, -halfWidth,
            halfWidth, halfWidth, length + halfWidth, red, green, blue, alpha);
        stack.pop();
    }

    /**
     * A buffer for the round outlines — bars of geometry, like the boxes. Since 1.21.5 a hardware
     * line's width belongs to its pipeline and BBS's line pipeline is built at width one, so bars
     * are the only way the width setting reaches round shapes on this branch.
     */
    private static BufferBuilder begin()
    {
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    }

    static void end(BufferBuilder builder)
    {
        WireframeLayers.flushTriangles(builder);
    }

    /** Full width of a line bar in blocks, scaled by the debug thickness setting. */
    private static float lineThickness()
    {
        float scale = BBSPhysicsSettings.debugLineWidth == null ? 1F : BBSPhysicsSettings.debugLineWidth.get();

        return 2F * Math.max(BASE_THICKNESS * scale, MIN_THICKNESS);
    }
}
