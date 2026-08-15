package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.collision.CollisionShape;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import org.joml.Vector3f;

/**
 * A box the size of what a form draws — the "fit to bounds" button of §5.2.
 *
 * <p>A form has no bounding box in BBS; the size of a drawn thing is only known to whoever draws
 * it. So this measures the one case where the answer is exact and worth having — a block, which
 * carries its own outline — and otherwise hands back the block-sized box every form is drawn
 * inside. A starting point to drag, not an answer, which is what the button is for.</p>
 */
public final class FormBounds
{
    /**
     * How a block form is placed: BBS draws the block from its corner and shifts it half a block
     * on X and Z, so the block a viewer sees stands centred on the form with its feet at zero.
     */
    private static final float BLOCK_SHIFT = 0.5F;

    private FormBounds()
    {}

    public static CollisionShape of(Form form)
    {
        if (form instanceof BlockForm block)
        {
            CollisionShape shape = ofBlock(block.blockState.get());

            if (shape != null)
            {
                return shape;
            }
        }

        return new CollisionShape(CollisionKind.BOX, new Vector3f(0F, 0.5F, 0F), new Vector3f(), new Vector3f(1F));
    }

    /** The block's own outline, or null when it has none to give — an empty shape, or one that
     *  wants a world this has no business asking for. */
    private static CollisionShape ofBlock(BlockState state)
    {
        if (state == null)
        {
            return null;
        }

        try
        {
            VoxelShape outline = state.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);

            if (outline.isEmpty())
            {
                return null;
            }

            Box box = outline.getBoundingBox();

            Vector3f size = new Vector3f(
                (float) (box.maxX - box.minX),
                (float) (box.maxY - box.minY),
                (float) (box.maxZ - box.minZ));
            Vector3f offset = new Vector3f(
                (float) (box.minX + box.maxX) * 0.5F - BLOCK_SHIFT,
                (float) (box.minY + box.maxY) * 0.5F,
                (float) (box.minZ + box.maxZ) * 0.5F - BLOCK_SHIFT);

            return new CollisionShape(CollisionKind.BOX, offset, new Vector3f(), size);
        }
        catch (Throwable e)
        {
            /* A block that insists on a real world to describe itself. The plain box below is a
             * better outcome than a broken button. */
            return null;
        }
    }
}
