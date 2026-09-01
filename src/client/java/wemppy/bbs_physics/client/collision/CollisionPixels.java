package wemppy.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelQuad;
import mchorse.bbs_mod.cubic.data.model.ModelVertex;
import wemppy.bbs_physics.collision.CollisionKind;
import wemppy.bbs_physics.collision.CollisionThickness;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A bone as the painted surface of its cubes — the {@link
 * wemppy.bbs_physics.collision.CollisionMode#PIXELS} mode.
 *
 * <p><b>One rule, six times over.</b> Every side of a cube is its own grid of model pixels. The
 * texture under that side says which of them are painted; the painted ones are glued into as few
 * rectangles as a greedy pass finds; each rectangle becomes a plate lying on that side. Nothing
 * is ever built inside the cube — a cube here is a hollow shell with holes where the texture is
 * clear, and a body that passes through a gap between two locks of hair passes through, as it
 * should.</p>
 *
 * <p>Every case the mode exists for falls out of that rule without being a case:</p>
 * <ul>
 * <li>a <b>card</b> — a strand cube of no depth — has four sides of no area, which give nothing,
 *     and a front and back that are the same plane, which are read together into one plate;</li>
 * <li>a <b>second layer</b> — a bigger cube over the head with hair on three sides — gets plates
 *     on those three sides and nothing on the clear ones, so there is no invisible wall in front
 *     of the face;</li>
 * <li>a side that is <b>not drawn at all</b> ({@code null} UV) has no quad and gives nothing.</li>
 * </ul>
 *
 * <p><b>Pixels of the model, not of the texture.</b> The grid is one model pixel per cell whatever
 * the texture's resolution: a 2× texture is sampled four texels to a cell and the cell is painted
 * when at least half of them are. Finer than that is more plates for no visible difference, since
 * a cube cannot be drawn at a finer step than its own pixels.</p>
 *
 * <p><b>Where the texture is unknown</b> — no link, a load that failed — every cell counts as
 * painted and each side is one whole plate, which is what the retired "face" mode built on all
 * six sides at once. The cube still does not collide as a volume.</p>
 *
 * <p>The sides are read off the quads {@code ModelCube.generateQuads} built for the renderer, not
 * off the UV table: a quad already carries the mirroring, the UV rotation and the inflation as
 * the texture is actually mapped, so this cannot disagree with what is on screen.</p>
 */
public final class CollisionPixels
{
    /**
     * The extent, in blocks, under which two opposite sides are one sheet. A quarter pixel — the
     * plate's own thickness: two plates closer than that would be one plate drawn twice.
     */
    private static final float SHEET = CollisionShapes.PLATE_THICKNESS;

    /**
     * Which way along each axis a sheet's "outward" points: towards the side its right (+x), top
     * (+y) or front (−z) face is drawn on — the faces an author sees first in the model editor.
     */
    private static final float[] SHEET_OUTWARD = {1F, 1F, -1F};

    /** How many texels across a cell are sampled at most, so a huge texture stays cheap. */
    private static final int MAX_SAMPLES = 8;

    private CollisionPixels()
    {}

    /** The plates of one bone of a cubic model, in the bone's frame. */
    public static List<CollisionShapes.SubShape> of(Model model, String bone, Vector3f scale, TextureAlpha alpha, CollisionThickness thickness, float plate)
    {
        return of(model.getGroup(bone), scale, alpha, thickness, plate);
    }

    /**
     * The plates of one group's cubes, in the group's frame.
     *
     * @param plate how thick a plate is, in model pixels
     */
    public static List<CollisionShapes.SubShape> of(ModelGroup group, Vector3f scale, TextureAlpha alpha, CollisionThickness thickness, float plate)
    {
        if (group == null || group.cubes.isEmpty())
        {
            return Collections.emptyList();
        }

        List<CollisionShapes.SubShape> shapes = new ArrayList<>();
        Vector3f pivot = group.initial.translate;

        for (ModelCube cube : group.cubes)
        {
            platesOfCube(cube, pivot, scale, alpha, thickness == null ? CollisionThickness.OUTWARD : thickness, plate / CollisionShapes.PIXELS * 0.5F, shapes);
        }

        return shapes;
    }

    /**
     * Whether a bone is drawn as sheets — every cube of it thinner than a pixel on some axis. A
     * strand, a cape, a fringe: what the automatic pass marks as pixels rather than a volume,
     * because the volume of a sheet is a slab of air.
     */
    public static boolean isSheet(Model model, String bone)
    {
        ModelGroup group = model.getGroup(bone);

        if (group == null || group.cubes.isEmpty())
        {
            return false;
        }

        for (ModelCube cube : group.cubes)
        {
            float grow = 2F * cube.inflate;
            float thinnest = Math.min(Math.abs(cube.size.x) + grow, Math.min(Math.abs(cube.size.y) + grow, Math.abs(cube.size.z) + grow));

            if (thinnest > 1F)
            {
                return false;
            }
        }

        return true;
    }

    /* One cube */

    /** A side's painted cells: a grid over the two in-plane axes, in ascending coordinate order. */
    private record Mask(int cols, int rows, boolean[] cells)
    {
        Mask union(Mask other)
        {
            if (other == null || other.cols != this.cols || other.rows != this.rows)
            {
                return this;
            }

            boolean[] cells = this.cells.clone();

            for (int i = 0; i < cells.length; i++)
            {
                cells[i] |= other.cells[i];
            }

            return new Mask(this.cols, this.rows, cells);
        }
    }

    /** @param half half the plate thickness, in blocks */
    private static void platesOfCube(ModelCube cube, Vector3f bonePivot, Vector3f scale, TextureAlpha alpha, CollisionThickness thickness, float half, List<CollisionShapes.SubShape> out)
    {
        /* A cube whose quads were never built has no sides to read; it is measured whole rather
         * than skipped, so the bone does not vanish from collision for a reason nobody can see. */
        if (cube.quads.isEmpty())
        {
            out.add(CollisionShapes.ofCube(cube, bonePivot, scale));

            return;
        }

        float grow = cube.inflate;
        float[] a = {
            (cube.origin.x - grow) / CollisionShapes.PIXELS,
            (cube.origin.y - grow) / CollisionShapes.PIXELS,
            (cube.origin.z - grow) / CollisionShapes.PIXELS};
        float[] b = {
            (cube.origin.x + cube.size.x + grow) / CollisionShapes.PIXELS,
            (cube.origin.y + cube.size.y + grow) / CollisionShapes.PIXELS,
            (cube.origin.z + cube.size.z + grow) / CollisionShapes.PIXELS};
        float[] min = new float[3];
        float[] max = new float[3];

        for (int i = 0; i < 3; i++)
        {
            min[i] = Math.min(a[i], b[i]);
            max[i] = Math.max(a[i], b[i]);
        }

        /* Six masks, by axis and end: index axis * 2 + (1 for the far end). */
        Mask[] masks = new Mask[6];

        for (ModelQuad quad : cube.quads)
        {
            int axis = normalAxis(quad);

            if (axis < 0)
            {
                continue;
            }

            int side = axis * 2 + (quad.normal.get(axis) > 0F ? 1 : 0);

            masks[side] = maskOf(quad, axis, min, max, alpha);
        }

        for (int axis = 0; axis < 3; axis++)
        {
            Mask near = masks[axis * 2];
            Mask far = masks[axis * 2 + 1];

            /* How far the plate's centre stands off the surface, as a fraction of its half
             * thickness: 1 is flush on the far side of it, 0 straddles it. The overlay is told
             * the opposite sign — where the surface is, seen from the plate. */
            float off = thickness == CollisionThickness.CENTERED ? 0F : 1F;

            if (max[axis] - min[axis] < SHEET)
            {
                /* One sheet: what is painted on either face is painted on the sheet. A sheet has
                 * no inside, so "outward" is a convention — the side of its front, right or top
                 * face — and the author has the setting to turn it round, which is the whole
                 * reason the setting exists: two cards, one before the head and one behind it,
                 * want opposite answers. */
                Mask sheet = near == null ? far : near.union(far);
                float side = thickness == CollisionThickness.INWARD ? -SHEET_OUTWARD[axis] : SHEET_OUTWARD[axis];
                float at = (min[axis] + max[axis]) * 0.5F + side * off * half;

                emit(sheet, axis, at, -side * off, half, min, max, cube, bonePivot, scale, out);
            }
            else
            {
                /* Flush with the side, thickness standing off it — outward by default, into the
                 * air, because of what a cube usually has inside it: the second layer of a head
                 * is a cube around the head, and a plate sunk into that cube is a plate sunk into
                 * the head's own body, two solids in one place. Standing off, it meets nothing it
                 * should not, and the quarter pixel it stands off by is under what a body can be
                 * seen to sink in. Inward is there for the cube that has nothing inside it and a
                 * neighbour outside. */
                float side = thickness == CollisionThickness.INWARD ? -1F : 1F;

                emit(near, axis, min[axis] - side * off * half, side * off, half, min, max, cube, bonePivot, scale, out);
                emit(far, axis, max[axis] + side * off * half, -side * off, half, min, max, cube, bonePivot, scale, out);
            }
        }
    }

    private static int normalAxis(ModelQuad quad)
    {
        Vector3f n = quad.normal;

        if (Math.abs(n.x) > 0.5F) return 0;
        if (Math.abs(n.y) > 0.5F) return 1;
        if (Math.abs(n.z) > 0.5F) return 2;

        return -1;
    }

    /**
     * Reads one side's texture into a grid of model pixels, or null when the side has no area to
     * speak of — the edge of a card, which is not a face.
     */
    private static Mask maskOf(ModelQuad quad, int axis, float[] min, float[] max, TextureAlpha alpha)
    {
        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        float extentU = max[u] - min[u];
        float extentV = max[v] - min[v];

        if (extentU < SHEET || extentV < SHEET)
        {
            return null;
        }

        int cols = Math.max(1, Math.round(extentU * CollisionShapes.PIXELS));
        int rows = Math.max(1, Math.round(extentV * CollisionShapes.PIXELS));
        boolean[] cells = new boolean[cols * rows];

        if (alpha == null)
        {
            Arrays.fill(cells, true);

            return new Mask(cols, rows, cells);
        }

        /* The quad's corners, by where they sit on the side: the UV of any point on the side is
         * affine in its two in-plane coordinates, and three corners fix it. */
        Vector2f uv00 = cornerUv(quad, u, v, min[u], min[v]);
        Vector2f uv10 = cornerUv(quad, u, v, max[u], min[v]);
        Vector2f uv01 = cornerUv(quad, u, v, min[u], max[v]);

        if (uv00 == null || uv10 == null || uv01 == null)
        {
            Arrays.fill(cells, true);

            return new Mask(cols, rows, cells);
        }

        float duX = uv10.x - uv00.x;
        float duY = uv10.y - uv00.y;
        float dvX = uv01.x - uv00.x;
        float dvY = uv01.y - uv00.y;

        /* Texels per cell, so a texture finer than the model is sampled across the whole cell
         * rather than at one point of it. */
        float texelsU = (float) Math.hypot(duX * alpha.width, duY * alpha.height) / cols;
        float texelsV = (float) Math.hypot(dvX * alpha.width, dvY * alpha.height) / rows;
        int samplesU = Math.min(MAX_SAMPLES, Math.max(1, (int) Math.ceil(texelsU)));
        int samplesV = Math.min(MAX_SAMPLES, Math.max(1, (int) Math.ceil(texelsV)));
        int total = samplesU * samplesV;

        for (int j = 0; j < rows; j++)
        {
            for (int i = 0; i < cols; i++)
            {
                int painted = 0;

                for (int sj = 0; sj < samplesV; sj++)
                {
                    float t = (j + (sj + 0.5F) / samplesV) / rows;

                    for (int si = 0; si < samplesU; si++)
                    {
                        float s = (i + (si + 0.5F) / samplesU) / cols;
                        float tu = uv00.x + s * duX + t * dvX;
                        float tv = uv00.y + s * duY + t * dvY;

                        if (alpha.isPainted((int) Math.floor(tu * alpha.width), (int) Math.floor(tv * alpha.height)))
                        {
                            painted++;
                        }
                    }
                }

                /* Half or more — see TextureAlpha on why the tie goes to the smaller silhouette. */
                cells[i + j * cols] = painted * 2 >= total;
            }
        }

        return new Mask(cols, rows, cells);
    }

    /** The UV of the quad's vertex nearest to the corner {@code (cu, cv)} of the side. */
    private static Vector2f cornerUv(ModelQuad quad, int u, int v, float cu, float cv)
    {
        ModelVertex best = null;
        float bestDistance = Float.MAX_VALUE;

        for (ModelVertex vertex : quad.vertices)
        {
            float du = vertex.vertex.get(u) - cu;
            float dv = vertex.vertex.get(v) - cv;
            float distance = du * du + dv * dv;

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = vertex;
            }
        }

        return best == null ? null : best.uv;
    }

    /* Rectangles to plates */

    /**
     * Glues a mask's painted cells into rectangles and lays a plate on each, centred at {@code at}
     * along the side's axis, with the painted side towards {@code surface} (see {@code
     * SubShape.surface}). Greedy: from each unclaimed painted cell, as wide as the
     * row allows, then as tall as every row below allows at that width. Not the fewest rectangles
     * possible, but a whole painted side is one, and a strand is a handful.
     */
    private static void emit(Mask mask, int axis, float at, float surface, float halfThickness, float[] min, float[] max, ModelCube cube, Vector3f bonePivot, Vector3f scale, List<CollisionShapes.SubShape> out)
    {
        if (mask == null)
        {
            return;
        }

        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        int cols = mask.cols;
        int rows = mask.rows;
        boolean[] cells = mask.cells;
        boolean[] claimed = new boolean[cells.length];
        float stepU = (max[u] - min[u]) / cols;
        float stepV = (max[v] - min[v]) / rows;

        for (int j = 0; j < rows; j++)
        {
            for (int i = 0; i < cols; i++)
            {
                if (!cells[i + j * cols] || claimed[i + j * cols])
                {
                    continue;
                }

                int width = 1;

                while (i + width < cols && cells[i + width + j * cols] && !claimed[i + width + j * cols])
                {
                    width++;
                }

                int height = 1;

                rows:
                while (j + height < rows)
                {
                    for (int k = 0; k < width; k++)
                    {
                        int index = i + k + (j + height) * cols;

                        if (!cells[index] || claimed[index])
                        {
                            break rows;
                        }
                    }

                    height++;
                }

                for (int jj = 0; jj < height; jj++)
                {
                    for (int ii = 0; ii < width; ii++)
                    {
                        claimed[i + ii + (j + jj) * cols] = true;
                    }
                }

                float[] center = new float[3];
                float[] half = new float[3];

                center[u] = min[u] + (i + width * 0.5F) * stepU;
                center[v] = min[v] + (j + height * 0.5F) * stepV;
                center[axis] = at;
                half[u] = width * stepU * 0.5F;
                half[v] = height * stepV * 0.5F;
                half[axis] = halfThickness;

                out.add(plate(center, half, axis, surface, cube, bonePivot, scale));
            }
        }
    }

    /**
     * One plate, axis-aligned in the cube's own space, carried into the bone's frame by the same
     * steps a measured cube takes: the cube's rotation about its own pivot, the bone's pivot, the
     * model's scale, and the half turn of §10.1.
     */
    private static CollisionShapes.SubShape plate(float[] center, float[] half, int axis, float surface, ModelCube cube, Vector3f bonePivot, Vector3f scale)
    {
        /* Only the plate's own floor: MIN_HALF keeps a volume solvable, and a plate is not one.
         * In the plane a cell is half a pixel across at the least, well above it anyway. */
        Vector3f halves = new Vector3f(
            Math.max(half[0] * scale.x, CollisionShapes.PLATE_MIN_HALF),
            Math.max(half[1] * scale.y, CollisionShapes.PLATE_MIN_HALF),
            Math.max(half[2] * scale.z, CollisionShapes.PLATE_MIN_HALF));
        Vector3f offset = new Vector3f(center[0], center[1], center[2]);
        Quaternionf rotation = new Quaternionf();

        CollisionShapes.intoBoneFrame(offset, rotation, cube, bonePivot, scale);

        /* The half turn of §10.1 is applied to the rotation by conjugation (F·R·F), which a box
         * cannot tell from F·R — except through its own axes: the trailing F turns the plate's
         * local X and Z round. The side the pixels are on was named in the cube's axes, so along
         * those two it changes sign here; Y is the axis of the turn and keeps it. The overlay
         * drew every plate on its far side until this was accounted for. */
        float local = axis == 1 ? surface : -surface;

        return new CollisionShapes.SubShape(CollisionKind.BOX, halves, offset, rotation, local);
    }
}
