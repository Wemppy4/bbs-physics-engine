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
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.cloth.ClothState;
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

import java.util.function.Supplier;

/**
 * Draws a sheet of cloth: the simulated grid when a scene has one for this form, the flat
 * rectangle the author placed otherwise — the form editor's preview, and every frame the recording
 * has not reached (Р8.1).
 *
 * <p>The machinery is the picture form's — one texture, the translucent-queue deferral, both faces
 * drawn so the sheet has a back. What is different is that the vertices come from
 * {@link ClothState} in the form's own frame (the recording stores them converted, so nothing is
 * evaluated here) and the normals are worked out per vertex from the draped grid, because a bent
 * sheet lit as if it were flat reads as flat.</p>
 */
public class ClothFormRenderer extends FormRenderer<ClothForm>
{
    private float[] positions = new float[0];
    private float[] normals = new float[0];

    public ClothFormRenderer(ClothForm form)
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

        this.renderCloth(
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

        this.renderCloth(format, shader, context.stack, context.overlay, context.light, context.color, context.getTransition(), !context.isPicking());
    }

    private void renderCloth(VertexFormat format, Supplier<ShaderProgram> shader, MatrixStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        Link link = this.form.texture.get();

        if (link == null)
        {
            return;
        }

        int columns = this.form.getColumns();
        int rows = this.form.getRows();

        this.fillPositions(columns, rows, transition);
        this.fillNormals(columns, rows);

        Texture texture = BBSModClient.getTextures().getTexture(link);
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

        for (int r = 0; r < rows - 1; r++)
        {
            for (int c = 0; c < columns - 1; c++)
            {
                int tl = r * columns + c;
                int tr = tl + 1;
                int bl = tl + columns;
                int br = bl + 1;

                /* Front. */
                this.fill(format, builder, matrix, normal, bl, columns, rows, color, overlay, light, 1F);
                this.fill(format, builder, matrix, normal, br, columns, rows, color, overlay, light, 1F);
                this.fill(format, builder, matrix, normal, tl, columns, rows, color, overlay, light, 1F);

                this.fill(format, builder, matrix, normal, br, columns, rows, color, overlay, light, 1F);
                this.fill(format, builder, matrix, normal, tr, columns, rows, color, overlay, light, 1F);
                this.fill(format, builder, matrix, normal, tl, columns, rows, color, overlay, light, 1F);

                /* Back — the same cells the other way round, so the sheet exists from behind. */
                this.fill(format, builder, matrix, normal, tl, columns, rows, color, overlay, light, -1F);
                this.fill(format, builder, matrix, normal, br, columns, rows, color, overlay, light, -1F);
                this.fill(format, builder, matrix, normal, bl, columns, rows, color, overlay, light, -1F);

                this.fill(format, builder, matrix, normal, tl, columns, rows, color, overlay, light, -1F);
                this.fill(format, builder, matrix, normal, tr, columns, rows, color, overlay, light, -1F);
                this.fill(format, builder, matrix, normal, br, columns, rows, color, overlay, light, -1F);
            }
        }

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
     * Where every vertex is for this frame, in the form's frame: the simulation's drape when the
     * scene has one, the author's flat rectangle when it does not.
     */
    private void fillPositions(int columns, int rows, float transition)
    {
        int count = columns * rows * 3;

        if (this.positions.length != count)
        {
            this.positions = new float[count];
            this.normals = new float[count];
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
                else
                {
                    this.positions[i * 3] = this.form.flatX(c);
                    this.positions[i * 3 + 1] = this.form.flatY(r);
                    this.positions[i * 3 + 2] = 0F;
                }
            }
        }
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

    private void fill(VertexFormat format, VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix, int i, int columns, int rows, Color color, int overlay, int light, float side)
    {
        float x = this.positions[i * 3];
        float y = this.positions[i * 3 + 1];
        float z = this.positions[i * 3 + 2];

        float u = (i % columns) / (float) (columns - 1);
        float v = (i / columns) / (float) (rows - 1);

        if (format == VertexFormats.POSITION_TEXTURE_LIGHT_COLOR)
        {
            consumer.vertex(matrix, x, y, z).texture(u, v).light(light).color(color.r, color.g, color.b, color.a).next();

            return;
        }

        consumer.vertex(matrix, x, y, z)
            .color(color.r, color.g, color.b, color.a)
            .texture(u, v)
            .overlay(overlay)
            .light(light)
            .normal(normalMatrix, this.normals[i * 3] * side, this.normals[i * 3 + 1] * side, this.normals[i * 3 + 2] * side)
            .next();
    }
}
