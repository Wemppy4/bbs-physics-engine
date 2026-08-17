package mchorse.bbs_physics.client.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.forms.ITexturedForm;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * Drawing a soft form: one texture over a mesh whose vertices come from the simulation, or from the
 * shape the author placed on a frame the recording has not reached (Р8.1).
 *
 * <p>Everything here is the picture form's machinery, which cloth and the balloon were each carrying
 * a copy of: picking the shader for the shading switch, binding the texture with the author's filter
 * settings, blending the form's colour, and handing a translucent mesh to the end-of-frame queue so
 * it blends correctly against other translucent forms instead of occluding what is behind it.</p>
 *
 * <p>What a subclass supplies is the mesh: how many vertices there are, where they are this frame,
 * and which triangles join them. Both faces are always drawn — a sheet has a back and a see-through
 * ball has an inside.</p>
 */
public abstract class TexturedMeshFormRenderer<T extends Form & ITexturedForm> extends FormRenderer<T>
{
    /** Where every vertex is this frame, in the form's own frame: x y z per vertex. */
    protected float[] positions = new float[0];

    /** The corner of the texture the mesh wears, worked out from the crop once per draw. */
    private float u1;
    private float v1;
    private float u2;
    private float v2;

    protected TexturedMeshFormRenderer(T form)
    {
        super(form);
    }

    /* What a subclass supplies */

    /** How many vertices the mesh has this frame. */
    protected abstract int getVertexCount();

    /** Fills {@link #positions} for this frame — from the recording, or from the authored shape. */
    protected abstract void fillPositions(float transition);

    /**
     * Emits every triangle of the mesh, calling {@link #vertex} for each corner.
     *
     * <p>Both faces, in whatever order the shape wants them: a sheet has a back and a see-through
     * ball has an inside, and the order the two are written in decides how a translucent one blends
     * against itself. The {@code side} handed to {@link #vertex} is 1 for the outward face and −1
     * for the one wound the other way.</p>
     */
    protected abstract void emit(MeshTarget target);

    /** The normal at vertex {@code i}, in the form's own frame. */
    protected abstract void normalAt(int i, float side, Vector3f out);

    /** How much bigger than its slot the palette's little preview should draw this form. */
    protected abstract void applyPreviewTransform(MatrixStack stack);

    /* The shared half */

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        MatrixStack stack = context.batcher.getContext().getMatrices();

        stack.push();

        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        this.applyTransforms(uiMatrix, context.getTransition());
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.translate(0F, 1F, 0F);
        this.applyPreviewTransform(stack);

        this.beforePreview();

        try
        {
            this.draw(
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                GameRenderer::getRenderTypeEntityTranslucentProgram,
                stack, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, Colors.WHITE,
                0F, false);
        }
        finally
        {
            this.afterPreview();
        }

        stack.pop();
    }

    /** Cloth uses these to swap in its canned drape for the palette entry; nothing else needs them. */
    protected void beforePreview()
    {}

    protected void afterPreview()
    {}

    @Override
    public void render3D(FormRenderingContext context)
    {
        boolean shading = this.form.getShading().get();

        if (BBSRendering.isIrisShadersEnabled())
        {
            shading = true;
        }

        VertexFormat format = shading ? VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL : VertexFormats.POSITION_TEXTURE_LIGHT_COLOR;
        Supplier<ShaderProgram> shader = this.getShader(context,
            shading ? GameRenderer::getRenderTypeEntityTranslucentProgram : GameRenderer::getPositionTexLightmapColorProgram,
            shading ? BBSShaders::getPickerBillboardProgram : BBSShaders::getPickerBillboardNoShadingProgram
        );

        this.draw(format, shader, context.stack, context.overlay, context.light, context.color, context.getTransition(), !context.isPicking());
    }

    private void draw(VertexFormat format, Supplier<ShaderProgram> shader, MatrixStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        Link link = this.form.getTexture().get();

        if (link == null)
        {
            return;
        }

        int count = this.getVertexCount();

        if (this.positions.length != count * 3)
        {
            this.positions = new float[count * 3];
        }

        this.fillPositions(transition);

        Texture texture = BBSModClient.getTextures().getTexture(link);

        /* The crop picks the region of the texture the mesh wears, in pixels off each side — the
         * picture form's convention, so a texture atlas built for one works here unchanged. It does
         * not touch the shape: these forms are sized in blocks because they are physical objects,
         * where a picture takes its proportions from its pixels. */
        Vector4f crop = this.form.getCrop().get();

        this.u1 = crop.x / texture.width;
        this.v1 = crop.y / texture.height;
        this.u2 = 1F - crop.z / texture.width;
        this.v2 = 1F - crop.w / texture.height;

        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Color color = new Color().set(overlayColor, true);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Matrix3f normal = matrices.peek().getNormalMatrix();

        FormColorBlend.blend(color, this.form.getColor().get(), this.form.additiveColor.get());

        GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;

        gameRenderer.getLightmapTextureManager().enable();
        gameRenderer.getOverlayTexture().setupOverlayColor();

        ShaderProgram finalShader = shader.get();

        BBSModClient.getTextures().bindTexture(texture);
        RenderSystem.setShader(() -> finalShader);

        boolean linear = this.form.getLinear().get();
        boolean mipmap = this.form.getMipmap().get();

        texture.bind();
        texture.setFilterMipmap(linear, mipmap);
        builder.begin(VertexFormat.DrawMode.TRIANGLES, format);

        MeshTarget target = new MeshTarget(format, builder, matrix, normal, color, overlay, light);

        this.emit(target);

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();

        boolean translucent = texture.hasTranslucency() || color.a < 1F || linear || mipmap;

        if (defer && translucent && FormTranslucentQueue.isActive())
        {
            /* The same end-of-frame deferral the picture form does, for the same reasons: correct
             * blending against other translucent forms, no occlusion of what is behind. */
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);

            buffer.bind();
            buffer.upload(builder.end());
            VertexBuffer.unbind();

            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            Vector3f origin = modelView.transformPosition(matrix.getTranslation(new Vector3f()));

            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(
                buffer, () -> finalShader, texture, modelView, null, origin, true,
                () ->
                {
                    texture.bind();
                    texture.setFilterMipmap(linear, mipmap);
                },
                () -> texture.setFilterMipmap(false, false)
            ));
        }
        else
        {
            BufferRenderer.drawWithGlobalProgram(builder.end());
        }

        texture.setFilterMipmap(false, false);

        gameRenderer.getLightmapTextureManager().disable();
        gameRenderer.getOverlayTexture().teardownOverlayColor();
    }

    /**
     * One corner of one triangle: vertex {@code i} of the mesh, wearing the texture at ({@code u},
     * {@code v}) in 0..1 of the cropped region.
     */
    protected void vertex(MeshTarget target, int i, float u, float v, float side)
    {
        float x = this.positions[i * 3];
        float y = this.positions[i * 3 + 1];
        float z = this.positions[i * 3 + 2];

        float tu = this.u1 + (this.u2 - this.u1) * u;
        float tv = this.v1 + (this.v2 - this.v1) * v;

        if (target.format == VertexFormats.POSITION_TEXTURE_LIGHT_COLOR)
        {
            /* The picking pass, which wants nothing but where the surface is. */
            target.builder.vertex(target.matrix, x, y, z)
                .texture(tu, tv)
                .light(target.light)
                .color(target.color.r, target.color.g, target.color.b, target.color.a)
                .next();

            return;
        }

        this.normalAt(i, side, target.normal);

        target.builder.vertex(target.matrix, x, y, z)
            .color(target.color.r, target.color.g, target.color.b, target.color.a)
            .texture(tu, tv)
            .overlay(target.overlay)
            .light(target.light)
            .normal(target.normalMatrix, target.normal.x, target.normal.y, target.normal.z)
            .next();
    }

    /** Everything one draw's vertices are written with — passed around rather than re-derived. */
    protected static final class MeshTarget
    {
        private final VertexFormat format;
        private final VertexConsumer builder;
        private final Matrix4f matrix;
        private final Matrix3f normalMatrix;
        private final Color color;
        private final int overlay;
        private final int light;

        private final Vector3f normal = new Vector3f();

        private MeshTarget(VertexFormat format, VertexConsumer builder, Matrix4f matrix, Matrix3f normalMatrix, Color color, int overlay, int light)
        {
            this.format = format;
            this.builder = builder;
            this.matrix = matrix;
            this.normalMatrix = normalMatrix;
            this.color = color;
            this.overlay = overlay;
            this.light = light;
        }
    }
}
