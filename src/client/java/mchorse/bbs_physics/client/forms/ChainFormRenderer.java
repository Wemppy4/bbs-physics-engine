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
 *
 * <p><b>The strand is drawn joint to joint, not segment by segment</b>, and that is the whole of
 * why it has no gaps. A capsule of a fixed length drawn at each body's own place opens a hole
 * between neighbours the moment the strand moves: a joint holds its two bodies together only as
 * well as two solver sub-steps manage, a swinging rope pulls those millimetres open, and the draw
 * interpolation widens them again by lerping positions while slerping rotations. So the renderer
 * works out the <em>seams</em> first — each one the midpoint of where its two segments say it is,
 * which makes it one point both of them share by construction — and then stretches every link
 * between its two seams. Neighbours cannot come apart because they are drawn from the same
 * numbers, whatever the solver did.</p>
 */
public class ChainFormRenderer extends FormRenderer<ChainForm>
{
    private static final Link ROPE = new Link(BBSPhysics.ASSETS, "textures/chain.png");

    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f segment = new Matrix4f();

    /** The seams: one more than there are segments, the strand's top and tip included. */
    private Vector3f[] seams = new Vector3f[0];

    /** Each segment's own turn, kept so a stretched link still carries the strand's twist. */
    private Quaternionf[] turns = new Quaternionf[0];

    private final Vector3f end = new Vector3f();
    private final Vector3f direction = new Vector3f();
    private final Vector3f down = new Vector3f();
    private final Quaternionf aim = new Quaternionf();

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

        this.fillSeams(segments, segmentLength, transition, simulated ? state : null);

        for (int i = 0; i < segments; i++)
        {
            this.direction.set(this.seams[i + 1]).sub(this.seams[i]);

            float span = this.direction.length();

            if (span < 1.0e-5F)
            {
                /* Two seams in the same spot — nothing to draw, and normalising would be a
                 * division by zero handed to the matrix stack. */
                continue;
            }

            this.direction.div(span);

            /* The link's frame: standing on the seam it starts at, turned so that its own down
             * axis points at the seam it ends on, and stretched to reach it. The segment's turn
             * is kept underneath the aiming, so a link still twists with the strand — only its
             * length and its lean are taken over. */
            this.down.set(0F, -1F, 0F);
            this.turns[i].transform(this.down);
            this.aim.rotationTo(this.down, this.direction).mul(this.turns[i]);

            this.segment.identity()
                .translate(this.seams[i])
                .rotate(this.aim)
                .scale(1F, span / segmentLength, 1F);

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

    /**
     * Works out where the strand's seams are for this frame, in the form's own frame.
     *
     * <p>A segment says where its own two ends are; two neighbours disagree about the seam they
     * share by however much the solver and the frame interpolation let them drift. Taking the
     * midpoint of the two answers gives both of them <em>one</em> point, so the links drawn from
     * it meet exactly — the gap is closed by construction rather than by tightening anything in
     * the simulation, which could only ever make it smaller.</p>
     *
     * @param state the recorded strand, or null to lay the seams out on the straight authored line
     */
    private void fillSeams(int segments, float segmentLength, float transition, ChainState state)
    {
        if (this.seams.length != segments + 1)
        {
            this.seams = new Vector3f[segments + 1];
            this.turns = new Quaternionf[segments];

            for (int i = 0; i <= segments; i++)
            {
                this.seams[i] = new Vector3f();

                if (i < segments)
                {
                    this.turns[i] = new Quaternionf();
                }
            }
        }

        if (state == null)
        {
            for (int i = 0; i <= segments; i++)
            {
                this.seams[i].set(0F, -i * segmentLength, 0F);

                if (i < segments)
                {
                    this.turns[i].identity();
                }
            }

            return;
        }

        for (int i = 0; i < segments; i++)
        {
            state.getPosition(i, transition, this.position);
            state.getRotation(i, transition, this.rotation);

            this.turns[i].set(this.rotation);

            /* This segment's own two ends, half a length either way along its local axis. */
            this.down.set(0F, segmentLength / 2F, 0F);
            this.rotation.transform(this.down);

            this.end.set(this.position).sub(this.down);
            this.position.add(this.down);

            if (i == 0)
            {
                this.seams[0].set(this.position);
            }
            else
            {
                /* Halfway between what the two neighbours claim — the one point they now share. */
                this.seams[i].add(this.position).mul(0.5F);
            }

            this.seams[i + 1].set(this.end);
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
