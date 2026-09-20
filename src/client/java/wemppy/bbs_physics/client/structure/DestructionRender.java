package wemppy.bbs_physics.client.structure;

import mchorse.bbs_mod.forms.structure.StructureRenderData;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.joml.Vector3f;
import mchorse.bbs_mod.api.client.render.StructureRenderPart;
import mchorse.bbs_mod.forms.forms.StructureForm;
import wemppy.bbs_physics.structure.DestructionState;

import java.util.*;

/** Renderer-owned caches: one current remainder and lazily baked individual debris blocks. */
public final class DestructionRender
{
    private final StructureForm form;
    public final DestructionState state;
    public final StructureRenderData source;
    public final String biome;
    private final Part[] blocks;
    private BitSet detached;
    private Part remainder;

    public DestructionRender(StructureForm form, DestructionState state, StructureRenderData source, String biome)
    {
        this.form = form;
        this.state = state;
        this.source = source;
        this.biome = biome;
        this.blocks = new Part[state.blocks.size()];
    }

    public Part remainder(BitSet detached)
    {
        if (!detached.equals(this.detached))
        {
            Set<BlockPos> remaining = new LinkedHashSet<>(this.source.getBlocks().keySet());
            for (int i = detached.nextSetBit(0); i >= 0; i = detached.nextSetBit(i + 1)) remaining.remove(this.state.blocks.get(i));
            this.remainder = remaining.isEmpty() ? null : this.part(remaining);
            this.detached = (BitSet) detached.clone();
        }
        return this.remainder;
    }

    public Part block(int index)
    {
        if (this.blocks[index] == null) this.blocks[index] = this.part(Set.of(this.state.blocks.get(index)));
        return this.blocks[index];
    }

    private Part part(Set<BlockPos> positions)
    {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions)
        {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, NbtCompound> entities = new LinkedHashMap<>();
        for (BlockPos p : positions)
        {
            BlockPos local = p.add(-minX, -minY, -minZ);
            states.put(local, this.source.getBlockState(p));
            NbtCompound nbt = this.source.getBlockEntities().get(p);
            if (nbt != null) entities.put(local, nbt.copy());
        }
        StructureRenderData data = StructureRenderPart.createData(this.source.id,
            new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1), states, entities);
        return new Part(new StructureRenderPart(this.form, data, this.biome,
            new Vector3f(this.state.offset).add(minX, minY, minZ)));
    }

    public static final class Part
    {
        public final StructureRenderPart renderer;
        Part(StructureRenderPart renderer) { this.renderer = renderer; }
    }
}
