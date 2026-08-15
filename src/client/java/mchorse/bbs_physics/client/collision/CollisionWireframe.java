package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_physics.collision.CollisionKind;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Draws a collision shape as an outline, centred on the current stack frame.
 *
 * <p>A round shape is drawn round. That sounds obvious and it is the whole point: an overlay
 * exists so an author can compare what the engine collides with against what the model looks like,
 * and a capsule shown as its bounding box is wrong at exactly the places a capsule is chosen for —
 * the shoulders and the knees, where the box has corners the capsule does not.</p>
 *
 * <p>Boxes keep BBS's own thick-wire look ({@link Draw#renderBox}), because that is what the rest
 * of the overlay has always drawn and a sudden change of line weight would read as meaning
 * something. Round shapes are line rings, which is what every physics debug view draws them as.</p>
 */
public final class CollisionWireframe
{
    /** Segments per full ring. Enough to read as a circle at arm's length, cheap enough to spam. */
    private static final int SEGMENTS = 24;

    private CollisionWireframe()
    {}

    public static void draw(MatrixStack stack, CollisionKind kind, Vector3f half, float red, float green, float blue, float alpha)
    {
        switch (kind)
        {
            case BOX -> Draw.renderBox(stack,
                -half.x, -half.y, -half.z,
                half.x * 2F, half.y * 2F, half.z * 2F,
                red, green, blue, alpha);
            case SPHERE -> sphere(stack, half.x, red, green, blue, alpha);
            case CAPSULE -> capsule(stack, half.x, half.y, red, green, blue, alpha);
            case CYLINDER -> cylinder(stack, half.x, half.y, red, green, blue, alpha);
        }
    }

    private static void sphere(MatrixStack stack, float radius, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();
        Matrix4f matrix = stack.peek().getPositionMatrix();

        ring(builder, matrix, Axis.Y, radius, 0F, red, green, blue, alpha);
        ring(builder, matrix, Axis.X, radius, 0F, red, green, blue, alpha);
        ring(builder, matrix, Axis.Z, radius, 0F, red, green, blue, alpha);

        end(builder);
    }

    private static void cylinder(MatrixStack stack, float radius, float halfHeight, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();
        Matrix4f matrix = stack.peek().getPositionMatrix();

        ring(builder, matrix, Axis.Y, radius, -halfHeight, red, green, blue, alpha);
        ring(builder, matrix, Axis.Y, radius, halfHeight, red, green, blue, alpha);
        rails(builder, matrix, radius, -halfHeight, halfHeight, red, green, blue, alpha);

        end(builder);
    }

    /** {@code halfHeight} is the straight part, as Jolt measures it — the caps sit on top of it. */
    private static void capsule(MatrixStack stack, float radius, float halfHeight, float red, float green, float blue, float alpha)
    {
        BufferBuilder builder = begin();
        Matrix4f matrix = stack.peek().getPositionMatrix();

        ring(builder, matrix, Axis.Y, radius, -halfHeight, red, green, blue, alpha);
        ring(builder, matrix, Axis.Y, radius, halfHeight, red, green, blue, alpha);
        rails(builder, matrix, radius, -halfHeight, halfHeight, red, green, blue, alpha);

        cap(builder, matrix, radius, halfHeight, 1F, red, green, blue, alpha);
        cap(builder, matrix, radius, -halfHeight, -1F, red, green, blue, alpha);

        end(builder);
    }

    /** The two half circles that close a capsule's end, drawn in the XY and ZY planes. */
    private static void cap(BufferBuilder builder, Matrix4f matrix, float radius, float centre, float direction, float red, float green, float blue, float alpha)
    {
        for (int i = 0; i < SEGMENTS / 2; i++)
        {
            double a = Math.PI * i / (SEGMENTS / 2D);
            double b = Math.PI * (i + 1) / (SEGMENTS / 2D);

            float ax = (float) Math.cos(a) * radius;
            float ay = (float) Math.sin(a) * radius * direction;
            float bx = (float) Math.cos(b) * radius;
            float by = (float) Math.sin(b) * radius * direction;

            line(builder, matrix, ax, centre + ay, 0F, bx, centre + by, 0F, red, green, blue, alpha);
            line(builder, matrix, 0F, centre + ay, ax, 0F, centre + by, bx, red, green, blue, alpha);
        }
    }

    /** The four straight lines down the sides of a cylinder or a capsule. */
    private static void rails(BufferBuilder builder, Matrix4f matrix, float radius, float bottom, float top, float red, float green, float blue, float alpha)
    {
        line(builder, matrix, radius, bottom, 0F, radius, top, 0F, red, green, blue, alpha);
        line(builder, matrix, -radius, bottom, 0F, -radius, top, 0F, red, green, blue, alpha);
        line(builder, matrix, 0F, bottom, radius, 0F, top, radius, red, green, blue, alpha);
        line(builder, matrix, 0F, bottom, -radius, 0F, top, -radius, red, green, blue, alpha);
    }

    private enum Axis
    {
        X, Y, Z
    }

    /** A full circle around {@code axis}, {@code offset} along it. */
    private static void ring(BufferBuilder builder, Matrix4f matrix, Axis axis, float radius, float offset, float red, float green, float blue, float alpha)
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
                case X -> line(builder, matrix, offset, previousU, previousV, offset, u, v, red, green, blue, alpha);
                case Y -> line(builder, matrix, previousU, offset, previousV, u, offset, v, red, green, blue, alpha);
                case Z -> line(builder, matrix, previousU, previousV, offset, u, v, offset, red, green, blue, alpha);
            }

            previousU = u;
            previousV = v;
        }
    }

    private static void line(BufferBuilder builder, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2, float red, float green, float blue, float alpha)
    {
        builder.vertex(matrix, x1, y1, z1).color(red, green, blue, alpha).next();
        builder.vertex(matrix, x2, y2, z2).color(red, green, blue, alpha).next();
    }

    private static BufferBuilder begin()
    {
        BufferBuilder builder = Tessellator.getInstance().getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        builder.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        return builder;
    }

    private static void end(BufferBuilder builder)
    {
        BufferRenderer.drawWithGlobalProgram(builder.end());
    }
}
