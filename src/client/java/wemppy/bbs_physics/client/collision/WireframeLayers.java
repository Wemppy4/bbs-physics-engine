package wemppy.bbs_physics.client.collision;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.graphics.Draw;
import wemppy.bbs_physics.BBSPhysics;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/**
 * Where the overlay's geometry goes when it is finished.
 *
 * <p>The overlay is drawn twice over, in two different ways. In the world it is depth tested, like
 * everything else in the scene. In the form editor it is drawn <em>through</em> the model, because
 * the whole reason to look at it there is to see the shapes that sit inside the mesh — which is
 * what {@code RenderSystem.disableDepthTest()} bought before 1.21.5.</p>
 *
 * <p>That switch no longer exists: depth testing is now a property of the pipeline a buffer is
 * drawn through, decided when the pipeline is built rather than when the draw happens. So the two
 * ways became two layers. The depth tested pair is BBS's own ({@link Draw#flushTriangles} /
 * {@link Draw#flushLines}); the pair below is the same thing with the depth test off, built the
 * same way BBS builds its own so that both sides of the overlay keep matching.</p>
 *
 * <p>{@link #setDepthTest(boolean)} stands where the GL call stood, and the callers bracket their
 * drawing with it exactly as they bracketed enable/disable — see {@link CollisionPreview}.</p>
 */
public final class WireframeLayers
{
    private static final RenderPipeline TRIANGLES_NO_DEPTH = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of(BBSPhysics.MOD_ID, "pipeline/wireframe_no_depth"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .build()
    );

    private static final RenderPipeline LINES_NO_DEPTH = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of(BBSPhysics.MOD_ID, "pipeline/wireframe_lines_no_depth"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .build()
    );

    private static RenderLayer trianglesNoDepth;
    private static RenderLayer linesNoDepth;

    private static boolean depthTest = true;

    private WireframeLayers()
    {}

    /**
     * Draw the overlay through the model, or against it.
     *
     * @return what it was, to be put back the way the GL state used to be put back
     */
    public static boolean setDepthTest(boolean value)
    {
        boolean was = depthTest;

        depthTest = value;

        return was;
    }

    /** Finish a TRIANGLES buffer and submit it. No-op on an empty one. */
    public static void flushTriangles(BufferBuilder builder)
    {
        if (depthTest)
        {
            Draw.flushTriangles(builder);

            return;
        }

        if (trianglesNoDepth == null)
        {
            trianglesNoDepth = RenderLayer.of(BBSPhysics.MOD_ID + "_wireframe_no_depth",
                RenderSetup.builder(TRIANGLES_NO_DEPTH).translucent().build());
        }

        flush(builder, trianglesNoDepth);
    }

    /** Finish a DEBUG_LINES buffer and submit it. No-op on an empty one. */
    public static void flushLines(BufferBuilder builder)
    {
        if (depthTest)
        {
            Draw.flushLines(builder);

            return;
        }

        if (linesNoDepth == null)
        {
            linesNoDepth = RenderLayer.of(BBSPhysics.MOD_ID + "_wireframe_lines_no_depth",
                RenderSetup.builder(LINES_NO_DEPTH).translucent().build());
        }

        flush(builder, linesNoDepth);
    }

    private static void flush(BufferBuilder builder, RenderLayer layer)
    {
        BuiltBuffer built = builder.endNullable();

        if (built != null)
        {
            layer.draw(built);
        }
    }
}
