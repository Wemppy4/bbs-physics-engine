package mchorse.bbs_physics.client.collision;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_physics.BBSPhysics;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Which pixels of a texture are painted — the one thing the pixel mode ({@link
 * mchorse.bbs_physics.collision.CollisionMode#PIXELS}) needs to know about a texture.
 *
 * <p>Read back from the texture BBS has already uploaded, so what collides is exactly what is
 * drawn: a skin composed of several layers, a colour link, an animated texture's current frame —
 * whatever the renderer resolved, this sees the same bytes. Off the render thread, where the GPU
 * cannot be asked, the file is read instead.</p>
 *
 * <p>Cached per link and kept until BBS swaps the texture object — which is what its watchdog
 * does when the file changes on disk — because the read is a full GPU round trip, and the
 * collision preview asks for it every frame.</p>
 *
 * <p><b>The threshold is half.</b> A pixel counts as painted at 50% alpha or more. Cubic
 * textures are almost always all-or-nothing and the choice does not matter for them; where an
 * author has softened an edge, it errs towards the smaller silhouette, which is the right way to
 * be wrong: a lock that sinks a pixel into a shoulder is invisible, a lock that stops a pixel
 * short of it is not.</p>
 */
public final class TextureAlpha
{
    private static final int OPAQUE = 128;

    /** Past this many textures the cache is simply emptied — a bound, not a policy. */
    private static final int CACHE_LIMIT = 64;

    private static final Map<Link, TextureAlpha> CACHE = new HashMap<>();

    public final int width;
    public final int height;

    /** Set where the pixel is painted; indexed {@code x + y * width}, row 0 at the top. */
    private final BitSet painted;

    /** The uploaded texture this was read from, to notice when BBS replaces it. */
    private final Texture source;

    private TextureAlpha(int width, int height, BitSet painted, Texture source)
    {
        this.width = width;
        this.height = height;
        this.painted = painted;
        this.source = source;
    }

    /**
     * The alpha of the texture at {@code link}, or null when it cannot be known — no link, a
     * texture that failed to load. Null is the caller's cue to treat every pixel as painted: the
     * honest fallback for a texture nobody can see is the cube's whole side.
     */
    public static TextureAlpha of(Link link)
    {
        if (link == null)
        {
            return null;
        }

        TextureManager manager = BBSModClient.getTextures();
        Texture current = manager.textures.get(link);
        TextureAlpha cached = CACHE.get(link);

        /* Still the texture it was read from — or an animated one, which has no single object to
         * compare against and is read once. */
        if (cached != null && (current == null || cached.source == current))
        {
            return cached;
        }

        TextureAlpha fresh = read(manager, link);

        if (fresh == null)
        {
            return null;
        }

        if (CACHE.size() >= CACHE_LIMIT)
        {
            CACHE.clear();
        }

        CACHE.put(link, fresh);

        return fresh;
    }

    /**
     * An alpha that was never read from a texture — for a headless stand feeding the pixel mode a
     * pattern it drew itself. Never cached.
     */
    public static TextureAlpha of(int width, int height, BitSet painted)
    {
        return new TextureAlpha(width, height, painted, null);
    }

    /** Whether the pixel at {@code (x, y)} is painted. Off the texture counts as clear. */
    public boolean isPainted(int x, int y)
    {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height)
        {
            return false;
        }

        return this.painted.get(x + y * this.width);
    }

    private static TextureAlpha read(TextureManager manager, Link link)
    {
        Texture texture = manager.getTexture(link, org.lwjgl.opengl.GL11.GL_NEAREST, true);

        if (texture == null || texture == manager.getError() || !texture.isValid())
        {
            return null;
        }

        Pixels pixels = null;

        try
        {
            /* The GPU copy is what is drawn; the file is the fallback for a thread that cannot
             * ask the GPU. */
            pixels = RenderSystem.isOnRenderThread() ? Texture.pixelsFromTexture(texture) : manager.getPixels(link);

            if (pixels == null || pixels.getBuffer() == null)
            {
                return null;
            }

            return scan(pixels, texture);
        }
        catch (Exception e)
        {
            BBSPhysics.LOGGER.warn("Could not read the pixels of '{}' for collision; its cubes collide by their whole sides.", link, e);

            return null;
        }
        finally
        {
            if (pixels != null && pixels.getBuffer() != null)
            {
                pixels.delete();
            }
        }
    }

    private static TextureAlpha scan(Pixels pixels, Texture source)
    {
        int width = pixels.width;
        int height = pixels.height;
        int bits = pixels.bits;
        ByteBuffer buffer = pixels.getBuffer();
        BitSet painted = new BitSet(width * height);

        /* No alpha channel is no transparency: everything is painted. */
        if (bits < 4)
        {
            painted.set(0, width * height);

            return new TextureAlpha(width, height, painted, source);
        }

        int count = Math.min(width * height, buffer.capacity() / bits);

        for (int i = 0; i < count; i++)
        {
            if ((buffer.get(i * bits + 3) & 0xff) >= OPAQUE)
            {
                painted.set(i);
            }
        }

        return new TextureAlpha(width, height, painted, source);
    }
}
