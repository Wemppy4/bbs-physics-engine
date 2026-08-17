package mchorse.bbs_physics.client.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
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
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.balloon.BalloonState;
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
 * Draws an inflated ball: the simulated mesh when a scene has one for this form, the perfect
 * sphere the author placed otherwise — the form editor's preview, the palette entry, and every
 * frame the recording has not reached (Р8.1). No canned pose here, unlike cloth: a sphere already
 * looks like what the form <em>is</em>.
 *
 * <p>The machinery is the cloth renderer's — one texture, the translucent-queue deferral, both
 * faces drawn so a see-through ball has an inside. Two things differ because the mesh is closed.
 * Texture coordinates are laid per <em>face corner</em> rather than per vertex: the seam meridian
 * would otherwise unwind the whole texture backwards across one cell, and the poles have no
 * longitude of their own. And the normals radiate from the centroid — a ball is convex from its
 * middle even while dented, which spares the pole fans any special casing.</p>
 */
public class BalloonFormRenderer extends FormRenderer<BalloonForm>
{
    private float[] positions = new float[0];
    private final Vector3f centroid = new Vector3f();

    /** The corner of the texture the ball wears, worked out from the crop once per draw. */
    private float u1;
    private float v1;
    private float u2;
    private float v2;

    public BalloonFormRenderer(BalloonForm form)
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

        /* Fill the slot: the ball is centred on its origin, so it only needs scaling up. */
        float scale = 0.9F / Math.max(this.form.radius.get(), 0.1F);

        stack.scale(scale, scale, scale);

        this.renderBalloon(
            VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
            GameRenderer::getRenderTypeEntityTranslucentProgram,
            stack, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, Colors.WHITE,
            0F, false);

        stack.pop();
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        boolean shading = this.form.shading.get();

        if (BBSRendering.isIrisShadersEnabled())
        {
            shading = true;
        }

        VertexFormat format = shading ? VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL : VertexFormats.POSITION_TEXTURE_LIGHT_COLOR;
        Supplier<ShaderProgram> shader = this.getShader(context,
            shading ? GameRenderer::getRenderTypeEntityTranslucentProgram : GameRenderer::getPositionTexLightmapColorProgram,
            shading ? BBSShaders::getPickerBillboardProgram : BBSShaders::getPickerBillboardNoShadingProgram
        );

        this.renderBalloon(format, shader, context.stack, context.overlay, context.light, context.color, context.getTransition(), !context.isPicking());
    }

    private void renderBalloon(VertexFormat format, Supplier<ShaderProgram> shader, MatrixStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        Link link = this.form.texture.get();

        if (link == null)
        {
            return;
        }

        int segments = this.form.segments.get();
        int rings = this.form.rings.get();
        int count = this.form.getVertexCount();

        this.fillPositions(count, transition);

        Texture texture = BBSModClient.getTextures().getTexture(link);

        /* The crop picks the region of the texture the ball wears — the picture form's convention,
         * same as cloth. It does not touch the ball's size. */
        Vector4f crop = this.form.crop.get();

        this.u1 = crop.x / texture.width;
        this.v1 = crop.y / texture.height;
        this.u2 = 1F - crop.z / texture.width;
        this.v2 = 1F - crop.w / texture.height;

        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Color color = new Color().set(overlayColor, true);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Matrix3f normal = matrices.peek().getNormalMatrix();

        FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());

        GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;

        gameRenderer.getLightmapTextureManager().enable();
        gameRenderer.getOverlayTexture().setupOverlayColor();

        ShaderProgram finalShader = shader.get();

        BBSModClient.getTextures().bindTexture(texture);
        RenderSystem.setShader(() -> finalShader);

        boolean linear = this.form.linear.get();
        boolean mipmap = this.form.mipmap.get();

        texture.bind();
        texture.setFilterMipmap(linear, mipmap);
        builder.begin(VertexFormat.DrawMode.TRIANGLES, format);

        /* The same fans and belts the rig builds, with texture coordinates laid per corner:
         * u runs along a ring without wrapping (the seam cell spans u1..1, not u1..0), v runs
         * pole to pole, and a pole takes the middle of its triangle's u span. */
        int south = this.form.getSouthPole();

        for (int side = 0; side < 2; side++)
        {
            float flip = side == 0 ? 1F : -1F;

            for (int s = 0; s < segments; s++)
            {
                float su1 = s / (float) segments;
                float su2 = (s + 1) / (float) segments;

                int a = 1 + s;
                int b = 1 + (s + 1) % segments;

                this.triangle(format, builder, matrix, normal, color, overlay, light, flip,
                    0, (su1 + su2) / 2F, 0F, b, su2, ringV(0, rings), a, su1, ringV(0, rings));

                int bottomA = 1 + (rings - 1) * segments + s;
                int bottomB = 1 + (rings - 1) * segments + (s + 1) % segments;

                this.triangle(format, builder, matrix, normal, color, overlay, light, flip,
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

                    this.triangle(format, builder, matrix, normal, color, overlay, light, flip,
                        tl, su1, rv1, tr + segments, su2, rv2, tl + segments, su1, rv2);
                    this.triangle(format, builder, matrix, normal, color, overlay, light, flip,
                        tl, su1, rv1, tr, su2, rv1, tr + segments, su2, rv2);
                }
            }
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();

        boolean translucent = texture.hasTranslucency() || color.a < 1F || linear || mipmap;

        if (defer && translucent && FormTranslucentQueue.isActive())
        {
            /* The same end-of-frame deferral the picture form does, for the same reasons. */
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

    /** Where ring {@code r} sits on the texture, pole to pole. */
    private static float ringV(int r, int rings)
    {
        return (r + 1) / (float) (rings + 1);
    }

    /**
     * Where every vertex is for this frame, in the form's frame — the simulation's ball when the
     * scene has one, the author's sphere when it does not — and the centroid the normals radiate
     * from.
     */
    private void fillPositions(int count, float transition)
    {
        if (this.positions.length != count * 3)
        {
            this.positions = new float[count * 3];
        }

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

    private void triangle(VertexFormat format, VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix, Color color, int overlay, int light, float side,
        int i0, float u0, float v0, int i1, float u1, float v1, int i2, float u2, float v2)
    {
        if (side > 0F)
        {
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i0, u0, v0);
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i1, u1, v1);
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i2, u2, v2);
        }
        else
        {
            /* The same cells the other way round, so a translucent ball has an inside. */
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i2, u2, v2);
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i1, u1, v1);
            this.corner(format, consumer, matrix, normalMatrix, color, overlay, light, side, i0, u0, v0);
        }
    }

    private void corner(VertexFormat format, VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix, Color color, int overlay, int light, float side,
        int i, float cornerU, float cornerV)
    {
        float x = this.positions[i * 3];
        float y = this.positions[i * 3 + 1];
        float z = this.positions[i * 3 + 2];

        float u = this.u1 + (this.u2 - this.u1) * cornerU;
        float v = this.v1 + (this.v2 - this.v1) * cornerV;

        if (format == VertexFormats.POSITION_TEXTURE_LIGHT_COLOR)
        {
            consumer.vertex(matrix, x, y, z).texture(u, v).light(light).color(color.r, color.g, color.b, color.a).next();

            return;
        }

        /* Radiating from the centroid: a ball is convex from its middle even while dented, and
         * the poles need no longitude of their own this way. */
        float nx = x - this.centroid.x;
        float ny = y - this.centroid.y;
        float nz = z - this.centroid.z;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

        if (length < 1e-8F)
        {
            nx = 0F;
            ny = 1F;
            nz = 0F;
        }
        else
        {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        consumer.vertex(matrix, x, y, z)
            .color(color.r, color.g, color.b, color.a)
            .texture(u, v)
            .overlay(overlay)
            .light(light)
            .normal(normalMatrix, nx * side, ny * side, nz * side)
            .next();
    }
}
