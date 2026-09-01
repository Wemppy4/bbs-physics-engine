package wemppy.bbs_physics.client.forms;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.client.render.picker.BBSPickerRenderer;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import wemppy.bbs_physics.forms.ITexturedForm;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
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

    /**
     * The palette's little preview.
     *
     * <p>It is drawn off-screen now. Since 1.21.6 the GUI records its cells first and composites them
     * after, so an immediate 3D draw into a list cell has nothing to composite into; every 3D form
     * type instead submits itself as a special element, and BBS calls back into
     * {@link #renderUIPreview} inside the off-screen pass.</p>
     */
    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.submitUIPreview(context, x1, y1, x2, y2);
    }

    @Override
    public void renderUIPreview(MatrixStack stack, float angle, float transition, int x1, int y1, int x2, int y2)
    {
        Matrix4f uiMatrix = getUIPreviewMatrix(angle, y1, y2);

        this.applyTransforms(uiMatrix, transition);

        stack.push();

        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.translate(0F, 1F, 0F);
        this.applyPreviewTransform(stack);

        this.beforePreview();

        try
        {
            this.draw(
                VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                BBSShaders::getBoundCulledModelLayer, null, false,
                stack, OverlayTexture.DEFAULT_UV, LightmapTextureManager.MAX_LIGHT_COORDINATE, Colors.WHITE,
                transition);
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

        /* Picking draws the same mesh through the picker pipeline, which writes the form's index
         * instead of the texture — but still samples it, so a cropped-out corner is not pickable.
         * The picking index rides the BBSPicker uniform block now, which is what setupTarget fills;
         * the picker shaders themselves are pipelines, chosen at the draw. */
        if (context.isPicking())
        {
            this.setupTarget(context);

            VertexFormat pickFormat = shading ? VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL : VertexFormats.POSITION_TEXTURE_LIGHT_COLOR;
            RenderPipeline picker = shading ? BBSShaders.getPickerBillboardProgram() : BBSShaders.getPickerBillboardNoShadingProgram();

            this.draw(pickFormat, null, picker, false, context.stack, context.overlay, context.light, context.color, context.getTransition());

            return;
        }

        /* Both layers cull, because the mesh emits every triangle twice — once per side, wound and
         * normalled the other way — and expects the card to keep the side facing the viewer. That is
         * what the draws used to get from the global GL state, which vanilla keeps culling on.
         *
         * Only the shaded path can be deferred: the unlit one draws through vanilla's
         * position_tex_color, which knows nothing of the opaque/translucent split the queue makes. */
        VertexFormat format = shading ? VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL : VertexFormats.POSITION_TEXTURE_COLOR;
        Supplier<RenderLayer> layer = shading ? BBSShaders::getBoundCulledModelLayer : BBSShaders::getBoundBillboardLayer;

        this.draw(format, layer, null, shading, context.stack, context.overlay, context.light, context.color, context.getTransition());
    }

    private void draw(VertexFormat format, Supplier<RenderLayer> shader, RenderPipeline picker, boolean deferrable, MatrixStack matrices, int overlay, int light, int overlayColor, float transition)
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

        Color color = new Color().set(overlayColor, true);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        MatrixStack.Entry entry = matrices.peek();

        FormColorBlend.blend(color, this.form.getColor().get(), this.form.additiveColor.get());

        /* The lightmap, the overlay, the blend function and the shader were all global state set
         * around the draw. They belong to the layer now, and the layer is picked below. */
        BBSModClient.getTextures().bindTexture(texture);

        boolean linear = this.form.getLinear().get();
        boolean mipmap = this.form.getMipmap().get();

        texture.bind();
        texture.setFilterMipmap(linear, mipmap);

        /* After the bind, never before: a layer is resolved from the last bound texture, so that it
         * carries this form's own texture rather than whatever was bound last by the time a deferred
         * draw is replayed. Picking has no layer — the picker pipeline binds its own sampler. */
        RenderLayer layer = picker == null ? shader.get() : null;

        if (picker != null)
        {
            BBSPickerRenderer.setSampler0(texture);
        }

        BufferBuilder builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, format);
        MeshTarget target = new MeshTarget(format, builder, matrix, entry, color, overlay, light);

        this.emit(target);

        BuiltBuffer built = builder.endNullable();

        if (built != null)
        {
            if (picker != null)
            {
                /* The camera already sits in the vertices — they were built against the stack's
                 * position matrix — so the pass wants nothing but the global model view. */
                BBSPickerRenderer.draw(picker, built, RenderSystem.getModelViewMatrix());
            }
            else if (deferrable)
            {
                /* The end-of-frame queue decides for itself now: an intrinsically see-through texture
                 * splits into an immediate opaque pass and a deferred translucent one, a uniform fade
                 * defers whole, and an opaque form at full colour just draws. All the old hand-rolled
                 * branch did, and the split besides.
                 *
                 * Depth writing stays ON, which the old deferral turned off. It borrowed the picture
                 * form's command, and a picture is a plate with nothing to occlude of itself — a soft
                 * form is a body, whose far side must not paint over its near one. The queue's split
                 * forces it on for the opaque half regardless, so the old setting was no longer a
                 * whole answer anyway. */
                Vector3f origin = new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(matrix.getTranslation(new Vector3f()));

                FormTranslucentQueue.submit(built,
                    new BBSShaders.ModelVariant(FormTranslucentQueue.PASS_SINGLE, true, true),
                    texture, color.a, null, origin);
            }
            else
            {
                layer.draw(built);
            }
        }

        texture.setFilterMipmap(false, false);
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

        if (target.format == VertexFormats.POSITION_TEXTURE_COLOR)
        {
            /* The unlit path: vanilla's position_tex_color reads position, UV and colour, no more. */
            target.builder.vertex(target.matrix, x, y, z)
                .texture(tu, tv)
                .color(target.color.r, target.color.g, target.color.b, target.color.a);

            return;
        }

        if (target.format == VertexFormats.POSITION_TEXTURE_LIGHT_COLOR)
        {
            /* The unlit picking pass, whose shader still declares LIGHT. */
            target.builder.vertex(target.matrix, x, y, z)
                .texture(tu, tv)
                .light(target.light)
                .color(target.color.r, target.color.g, target.color.b, target.color.a);

            return;
        }

        this.normalAt(i, side, target.normal);

        target.builder.vertex(target.matrix, x, y, z)
            .color(target.color.r, target.color.g, target.color.b, target.color.a)
            .texture(tu, tv)
            .overlay(target.overlay)
            .light(target.light)
            .normal(target.entry, target.normal.x, target.normal.y, target.normal.z);
    }

    /** Everything one draw's vertices are written with — passed around rather than re-derived. */
    protected static final class MeshTarget
    {
        private final VertexFormat format;
        private final VertexConsumer builder;
        private final Matrix4f matrix;
        private final MatrixStack.Entry entry;
        private final Color color;
        private final int overlay;
        private final int light;

        private final Vector3f normal = new Vector3f();

        private MeshTarget(VertexFormat format, VertexConsumer builder, Matrix4f matrix, MatrixStack.Entry entry, Color color, int overlay, int light)
        {
            this.format = format;
            this.builder = builder;
            this.matrix = matrix;
            this.entry = entry;
            this.color = color;
            this.overlay = overlay;
            this.light = light;
        }
    }
}
