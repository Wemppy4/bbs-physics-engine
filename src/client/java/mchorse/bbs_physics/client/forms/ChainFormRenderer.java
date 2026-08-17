package mchorse.bbs_physics.client.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.chain.ChainForm;
import mchorse.bbs_physics.chain.ChainState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * Draws a chain: each segment where the simulation has it when a scene has claimed the form, and
 * the straight authored strand otherwise — the form editor's preview, and every frame the
 * recording has not reached (Р8.1).
 *
 * <p>Two looks. With a link form set, that form is drawn once per segment, hanging from the
 * segment's start — a cube link makes a chain, a textured billboard makes a ribbon, anything BBS
 * can draw rides along and turns with the segment's own twist. With none, a built-in rope band is
 * drawn: two crossed strips per segment wearing the addon's rope texture, so the form is never
 * invisible.</p>
 */
public class ChainFormRenderer extends FormRenderer<ChainForm>
{
    private static final Link ROPE = new Link(BBSPhysics.ASSETS, "textures/chain.png");

    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f segment = new Matrix4f();

    public ChainFormRenderer(ChainForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();

        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        this.applyTransforms(uiMatrix, context.getTransition());
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.translate(0F, 1F, 0F);
        stack.scale(1.5F, 1.5F, 1.5F);

        /* The strand hangs from its pivot — shift up by half its length to centre it in the slot. */
        stack.translate(0F, this.form.length.get() / 2F, 0F);

        this.renderChain(null, stack, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, 0F);

        stack.pop();
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        this.renderChain(context, context.stack, context.overlay, context.light, context.getTransition());
    }

    /**
     * Draws every segment into the stack the form was handed. {@code context} is null in the UI
     * preview, where there is no rendering context to pass a link form — the band is drawn there
     * regardless of what the link says, which also keeps the palette entry honest about being a
     * chain rather than a pile of whatever the link is.
     */
    private void renderChain(FormRenderingContext context, MatrixStack stack, int overlay, int light, float transition)
    {
        int segments = this.form.segments.get();
        float segmentLength = this.form.getSegmentLength();

        ChainState state = this.form.state;
        boolean simulated = state != null && state.isKnown() && state.getSegments() == segments;

        Form link = context == null ? null : this.form.link.get();

        for (int i = 0; i < segments; i++)
        {
            if (simulated)
            {
                state.getPosition(i, transition, this.position);
                state.getRotation(i, transition, this.rotation);
            }
            else
            {
                this.position.set(0F, this.form.restY(i), 0F);
                this.rotation.identity();
            }

            /* The segment's frame: its centre and turn, then up half a length so the origin sits
             * at the segment's start — where a link naturally hangs from. */
            this.segment.identity()
                .translate(this.position)
                .rotate(this.rotation)
                .translate(0F, segmentLength / 2F, 0F);

            stack.push();
            MatrixStackUtils.multiply(stack, this.segment);

            if (link != null)
            {
                FormUtilsClient.render(link, context);
            }
            else
            {
                this.renderBand(context, stack, overlay, light, segmentLength);
            }

            stack.pop();
        }
    }

    /** The built-in look: two crossed strips the length of the segment, wearing the rope texture. */
    private void renderBand(FormRenderingContext context, MatrixStack stack, int overlay, int light, float segmentLength)
    {
        float width = Math.max(this.form.radius.get(), 0.03F);

        Texture texture = BBSModClient.getTextures().getTexture(ROPE);

        Supplier<ShaderProgram> shader = context == null
            ? GameRenderer::getRenderTypeEntityTranslucentProgram
            : this.getShader(context, GameRenderer::getRenderTypeEntityTranslucentProgram, BBSShaders::getPickerBillboardProgram);

        GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;

        gameRenderer.getLightmapTextureManager().enable();
        gameRenderer.getOverlayTexture().setupOverlayColor();

        ShaderProgram finalShader = shader.get();

        BBSModClient.getTextures().bindTexture(texture);
        RenderSystem.setShader(() -> finalShader);

        texture.bind();

        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Matrix4f matrix = stack.peek().getPositionMatrix();
        Matrix3f normal = stack.peek().getNormalMatrix();

        builder.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);

        /* Two ribbons crossed at right angles, each drawn from both sides. */
        this.strip(builder, matrix, normal, overlay, light, width, segmentLength, true);
        this.strip(builder, matrix, normal, overlay, light, width, segmentLength, false);

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();

        BufferRenderer.drawWithGlobalProgram(builder.end());

        gameRenderer.getLightmapTextureManager().disable();
        gameRenderer.getOverlayTexture().teardownOverlayColor();
    }

    private void strip(BufferBuilder builder, Matrix4f matrix, Matrix3f normal, int overlay, int light, float width, float length, boolean acrossX)
    {
        float x1 = acrossX ? -width : 0F;
        float z1 = acrossX ? 0F : -width;
        float x2 = acrossX ? width : 0F;
        float z2 = acrossX ? 0F : width;

        float nx = acrossX ? 0F : 1F;
        float nz = acrossX ? 1F : 0F;

        /* Front. */
        this.corner(builder, matrix, normal, x1, -length, z1, 0F, 1F, overlay, light, nx, nz);
        this.corner(builder, matrix, normal, x2, -length, z2, 1F, 1F, overlay, light, nx, nz);
        this.corner(builder, matrix, normal, x1, 0F, z1, 0F, 0F, overlay, light, nx, nz);

        this.corner(builder, matrix, normal, x2, -length, z2, 1F, 1F, overlay, light, nx, nz);
        this.corner(builder, matrix, normal, x2, 0F, z2, 1F, 0F, overlay, light, nx, nz);
        this.corner(builder, matrix, normal, x1, 0F, z1, 0F, 0F, overlay, light, nx, nz);

        /* Back — the same strip the other way round, so the band exists from behind. */
        this.corner(builder, matrix, normal, x1, 0F, z1, 0F, 0F, overlay, light, -nx, -nz);
        this.corner(builder, matrix, normal, x2, -length, z2, 1F, 1F, overlay, light, -nx, -nz);
        this.corner(builder, matrix, normal, x1, -length, z1, 0F, 1F, overlay, light, -nx, -nz);

        this.corner(builder, matrix, normal, x1, 0F, z1, 0F, 0F, overlay, light, -nx, -nz);
        this.corner(builder, matrix, normal, x2, 0F, z2, 1F, 0F, overlay, light, -nx, -nz);
        this.corner(builder, matrix, normal, x2, -length, z2, 1F, 1F, overlay, light, -nx, -nz);
    }

    private void corner(BufferBuilder builder, Matrix4f matrix, Matrix3f normal, float x, float y, float z, float u, float v, int overlay, int light, float nx, float nz)
    {
        builder.vertex(matrix, x, y, z)
            .color(1F, 1F, 1F, 1F)
            .texture(u, v)
            .overlay(overlay)
            .light(light)
            .normal(normal, nx, 0F, nz)
            .next();
    }
}
