package wemppy.bbs_physics.client.collision;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
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

        BufferBuilder builder = Tessellator.getInstance().getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        builder.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        CollisionWireframe.line(builder, stack, pivot.x - MARK, pivot.y, pivot.z, pivot.x + MARK, pivot.y, pivot.z, red, green, blue, alpha);
        CollisionWireframe.line(builder, stack, pivot.x, pivot.y - MARK, pivot.z, pivot.x, pivot.y + MARK, pivot.z, red, green, blue, alpha);
        CollisionWireframe.line(builder, stack, pivot.x, pivot.y, pivot.z - MARK, pivot.x, pivot.y, pivot.z + MARK, red, green, blue, alpha);

        if (parent != null)
        {
            CollisionWireframe.line(builder, stack, pivot.x, pivot.y, pivot.z, parent.x, parent.y, parent.z, red, green, blue, alpha);
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());
    }
}
