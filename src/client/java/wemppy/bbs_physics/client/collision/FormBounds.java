package wemppy.bbs_physics.client.collision;

import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.forms.ExtrudedForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ItemForm;
import mchorse.bbs_mod.forms.forms.MobForm;
import wemppy.bbs_physics.collision.CollisionKind;
import wemppy.bbs_physics.collision.CollisionShape;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Boxes the size of what a form draws — the "box from bounds" button of §5.2, which is the whole
 * of auto-markup for anything that is not a model.
 *
 * <p>A form has no bounding box in BBS; the size of a drawn thing is only known to whoever draws
 * it. So this asks each kind of form the way its renderer would, and measures exactly:</p>
 *
 * <ul>
 * <li><b>A block</b> from its own outline, box by box — stairs get two boxes and a slab gets a
 * slab. The block is drawn from its corner and shifted half a block on X and Z, so the boxes
 * stand centred on the form with their feet at zero, as the block does.</li>
 * <li><b>An item</b> from the quads of its baked model, under the display transform the form
 * renders it with, and shifted to the centre the way vanilla shifts every item. A flat item
 * comes out as the sixteenth-of-a-block plate its sprite is built into; a block item comes out as
 * the block. Items drawn by code rather than by a model (a chest, a shield) have no quads to
 * measure and fall back to the block-sized box.</li>
 * <li><b>A picture</b> (billboard) and <b>an extruded texture</b> from the painted pixels of
 * their texture — the transparent margin around a sprite is not part of the thing — as a plate
 * a sixteenth of a block thick for the extrusion, and as thin as the engine allows for the
 * picture, which has no thickness at all.</li>
 * <li><b>A mob</b> from its kind's hitbox: width and height, standing on the origin.</li>
 * </ul>
 *
 * <p>Anything else gets the block-sized box every form is drawn inside — a starting point to
 * drag, not an answer, which is what the button is for.</p>
 */
public final class FormBounds
{
    /**
     * How a block form is placed: BBS draws the block from its corner and shifts it half a block
     * on X and Z, so the block a viewer sees stands centred on the form with its feet at zero.
     */
    private static final float BLOCK_SHIFT = 0.5F;

    /** The depth of an extruded texture: half a pixel each way — see {@code TextureExtruder}. */
    private static final float EXTRUDED_DEPTH = 1F / 16F;

    /**
     * The depth a picture is given. It has none, and Jolt needs some — this is the plate
     * thickness the pixel mode uses for a flat contour, a quarter of a pixel.
     */
    private static final float PICTURE_DEPTH = 1F / 64F;

    /** Floats per vertex in a baked quad: position, colour, texture, light, normal. */
    private static final int QUAD_STRIDE = 8;

    private FormBounds()
    {}

    public static List<CollisionShape> of(Form form)
    {
        List<CollisionShape> shapes = null;

        try
        {
            if (form instanceof BlockForm block)
            {
                shapes = ofBlock(block.blockState.get());
            }
            else if (form instanceof ItemForm item)
            {
                shapes = ofItem(item);
            }
            else if (form instanceof BillboardForm picture)
            {
                shapes = ofPicture(picture);
            }
            else if (form instanceof ExtrudedForm extruded)
            {
                shapes = ofExtruded(extruded);
            }
            else if (form instanceof MobForm mob)
            {
                shapes = ofMob(mob);
            }
        }
        catch (Throwable e)
        {
            /* A block that insists on a real world to describe itself, an item whose model is not
             * there yet. The plain box below is a better outcome than a broken button. */
            shapes = null;
        }

        if (shapes == null || shapes.isEmpty())
        {
            return List.of(new CollisionShape(CollisionKind.BOX, new Vector3f(0F, 0.5F, 0F), new Vector3f(), new Vector3f(1F)));
        }

        return shapes;
    }

    /** The block's own outline, a box per piece of it, or null when it has none to give. */
    private static List<CollisionShape> ofBlock(BlockState state)
    {
        if (state == null)
        {
            return null;
        }

        VoxelShape outline = state.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);

        if (outline.isEmpty())
        {
            return null;
        }

        List<CollisionShape> shapes = new ArrayList<>();

        for (Box box : outline.getBoundingBoxes())
        {
            shapes.add(box(
                (float) box.minX - BLOCK_SHIFT, (float) box.minY, (float) box.minZ - BLOCK_SHIFT,
                (float) box.maxX - BLOCK_SHIFT, (float) box.maxY, (float) box.maxZ - BLOCK_SHIFT));
        }

        return shapes;
    }

    /**
     * The item as its baked model draws it: every quad's corners, put through the display
     * transform the form renders with and the half-block shift vanilla applies after it, and
     * boxed.
     */
    private static List<CollisionShape> ofItem(ItemForm form)
    {
        ItemStack stack = form.stack.get();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (stack == null || stack.isEmpty() || mc.getItemRenderer() == null)
        {
            return null;
        }

        BakedModel model = mc.getItemRenderer().getModel(stack, null, null, 0);

        if (model == null || model.isBuiltin())
        {
            return null;
        }

        /* Exactly what ItemRenderer.renderItem does before it hands the quads over, so that the
         * box lands where the item is drawn under this form's display mode. */
        MatrixStack matrices = new MatrixStack();

        model.getTransformation().getTransformation(form.modelTransform.get()).apply(false, matrices);
        matrices.translate(-0.5F, -0.5F, -0.5F);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Random random = Random.create(42L);
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        Vector4f point = new Vector4f();
        boolean any = false;

        for (int side = -1; side < Direction.values().length; side++)
        {
            Direction direction = side < 0 ? null : Direction.values()[side];

            for (BakedQuad quad : model.getQuads(null, direction, random))
            {
                int[] data = quad.getVertexData();

                for (int at = 0; at + 2 < data.length; at += QUAD_STRIDE)
                {
                    point.set(Float.intBitsToFloat(data[at]), Float.intBitsToFloat(data[at + 1]), Float.intBitsToFloat(data[at + 2]), 1F);
                    matrix.transform(point);

                    min.min(new Vector3f(point.x, point.y, point.z));
                    max.max(new Vector3f(point.x, point.y, point.z));
                    any = true;
                }
            }
        }

        return any ? List.of(box(min.x, min.y, min.z, max.x, max.y, max.z)) : null;
    }

    /**
     * The picture's quad, trimmed to its painted pixels. Mirrors {@code BillboardFormRenderer}:
     * the quad is a unit square squashed to the texture's aspect, the crop cuts its UVs, and the
     * offset and rotation turn it about the crop's centre.
     */
    private static List<CollisionShape> ofPicture(BillboardForm form)
    {
        TextureAlpha alpha = TextureAlpha.of(form.texture.get());

        if (alpha == null)
        {
            return null;
        }

        float w = alpha.width;
        float h = alpha.height;
        Vector4f crop = form.crop.get();

        /* The crop in pixels, then the painted extent inside it. */
        int x0 = Math.max(0, Math.round(crop.x));
        int y0 = Math.max(0, Math.round(crop.y));
        int x1 = Math.min(alpha.width, Math.round(w - crop.z));
        int y1 = Math.min(alpha.height, Math.round(h - crop.w));
        int[] painted;

        if (form.offsetX.get() != 0F || form.offsetY.get() != 0F || form.rotation.get() != 0F)
        {
            /* The offset and the rotation move the picture inside the quad — they are applied to
             * the UVs, not to the corners — so the painted pixels are no longer where the crop says.
             * The whole crop is the honest answer then. */
            painted = x1 > x0 && y1 > y0 ? new int[] {x0, y0, x1, y1} : null;
        }
        else
        {
            painted = painted(alpha, x0, y0, x1, y1);
        }

        if (painted == null)
        {
            return null;
        }

        /* Pixel bounds → UVs → the quad's own coordinates, as the renderer does it: a unit
         * square around the origin, squashed on the longer side's axis to the aspect ratio. */
        float ratioX = w > h ? h / w : 1F;
        float ratioY = h > w ? w / h : 1F;

        float left = (painted[0] / w - 0.5F) * ratioY;
        float right = (painted[2] / w - 0.5F) * ratioY;
        float top = -(painted[1] / h - 0.5F) * ratioX;
        float bottom = -(painted[3] / h - 0.5F) * ratioX;

        return List.of(box(left, bottom, -PICTURE_DEPTH * 0.5F, right, top, PICTURE_DEPTH * 0.5F));
    }

    /**
     * The extruded texture, trimmed to its painted pixels. Mirrors {@code TextureExtruder}: a unit
     * square squashed to the aspect, a pixel is a cell of it, and the whole thing is a sixteenth
     * of a block deep.
     */
    private static List<CollisionShape> ofExtruded(ExtrudedForm form)
    {
        TextureAlpha alpha = TextureAlpha.of(form.texture.get());

        if (alpha == null)
        {
            return null;
        }

        int[] painted = painted(alpha, 0, 0, alpha.width, alpha.height);

        if (painted == null)
        {
            return null;
        }

        float px = 0.5F;
        float py = 0.5F;

        if (alpha.width > alpha.height)
        {
            py = alpha.height / (float) alpha.width * 0.5F;
        }
        else if (alpha.height > alpha.width)
        {
            px = alpha.width / (float) alpha.height * 0.5F;
        }

        float sx = px * 2F / alpha.width;
        float sy = py * 2F / alpha.height;

        return List.of(box(
            painted[0] * sx - px, py - painted[3] * sy, -EXTRUDED_DEPTH * 0.5F,
            painted[2] * sx - px, py - painted[1] * sy, EXTRUDED_DEPTH * 0.5F));
    }

    /** The mob's kind's hitbox, standing on the origin. */
    private static List<CollisionShape> ofMob(MobForm form)
    {
        EntityType<?> type = EntityType.get(form.mobID.get()).orElse(null);

        if (type == null)
        {
            return null;
        }

        EntityDimensions dimensions = type.getDimensions();
        float half = dimensions.width() * 0.5F;

        return List.of(box(-half, 0F, -half, half, dimensions.height(), half));
    }

    /**
     * The painted extent of a region of a texture, as {@code x0, y0, x1, y1} in pixels with the
     * far edge exclusive, or null when nothing in the region is painted.
     */
    private static int[] painted(TextureAlpha alpha, int x0, int y0, int x1, int y1)
    {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;

        for (int y = y0; y < y1; y++)
        {
            for (int x = x0; x < x1; x++)
            {
                if (alpha.isPainted(x, y))
                {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        return maxX < 0 ? null : new int[] {minX, minY, maxX + 1, maxY + 1};
    }

    private static CollisionShape box(float x0, float y0, float z0, float x1, float y1, float z1)
    {
        return new CollisionShape(
            CollisionKind.BOX,
            new Vector3f((x0 + x1) * 0.5F, (y0 + y1) * 0.5F, (z0 + z1) * 0.5F),
            new Vector3f(),
            new Vector3f(x1 - x0, y1 - y0, z1 - z0));
    }
}
