package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.forms.structure.StructureRenderData;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

/** Build independent fragment data without altering the manager's shared structure. */
@Mixin(StructureRenderData.class)
public interface StructureRenderDataAccessor
{
    @Invoker("<init>")
    static StructureRenderData bbs_physics$create(String id, Vec3i size, Map<BlockPos, BlockState> blocks,
        Map<BlockPos, NbtCompound> entities)
    {
        throw new AssertionError();
    }
}
