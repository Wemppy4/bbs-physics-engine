package wemppy.bbs_physics.client.collision;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws one ragdoll joint: a cross at the joint itself and a line to whatever the bone hangs on.
 *
 * <p>Deliberately not a cone of limits. The first question an author has is <em>what is attached to
 * what</em> — the three-step attachment (§5.3) is the least predictable thing in the addon — and a
 * line answers it at a glance, where a cone would mostly hide the model behind cones. Limits are
 * worth drawing later, once attachment stops being a mystery.</p>
 */
public final class JointWireframe
{
    /** Arm length of the cross at the joint, in blocks — about a model pixel and a half. */
    private static final float MARK = 0.1F;

    private JointWireframe()
    {}

    /**
     * @param pivot  where the joint is, in the frame currently on the stack
     * @param parent where the bone it hangs on is, or null for the trunk — which gets a cross and
     *               no line, because that is exactly what "held by nothing" looks like
     */
    public static void draw(MatrixStack stack, Vector3f pivot, Vector3f parent, int color)
    {
        float alpha = ((color >> 24) & 0xFF) / 255F;
        float red = ((color >> 16) & 0xFF) / 255F;
        float green = ((color >> 8) & 0xFF) / 255F;
        float blue = (color & 0xFF) / 255F;

        Matrix4f matrix = stack.peek().getPositionMatrix();

        BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        line(builder, matrix, pivot.x - MARK, pivot.y, pivot.z, pivot.x + MARK, pivot.y, pivot.z, red, green, blue, alpha);
        line(builder, matrix, pivot.x, pivot.y - MARK, pivot.z, pivot.x, pivot.y + MARK, pivot.z, red, green, blue, alpha);
        line(builder, matrix, pivot.x, pivot.y, pivot.z - MARK, pivot.x, pivot.y, pivot.z + MARK, red, green, blue, alpha);

        if (parent != null)
        {
            line(builder, matrix, pivot.x, pivot.y, pivot.z, parent.x, parent.y, parent.z, red, green, blue, alpha);
        }

        WireframeLayers.flushLines(builder);
    }

    private static void line(BufferBuilder builder, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2, float red, float green, float blue, float alpha)
    {
        builder.vertex(matrix, x1, y1, z1).color(red, green, blue, alpha);
        builder.vertex(matrix, x2, y2, z2).color(red, green, blue, alpha);
    }
}
