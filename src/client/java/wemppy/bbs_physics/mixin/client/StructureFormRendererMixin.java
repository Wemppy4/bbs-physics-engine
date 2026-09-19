package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;
import mchorse.bbs_mod.forms.structure.BakedStructure;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.forms.structure.StructureRenderWorld;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wemppy.bbs_physics.client.structure.DestructionRender;
import wemppy.bbs_physics.structure.StructureDestruction;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

/** Reuses BBS's complete structure draw path for each fragment, including picking and block entities. */
@Mixin(StructureFormRenderer.class)
public abstract class StructureFormRendererMixin extends FormRenderer<StructureForm>
{
    @Shadow private StructureRenderData data;
    @Shadow private StructureRenderWorld world;
    @Shadow private BakedStructure baked;
    @Shadow private List<BlockEntity> blockEntities;
    @Shadow private World structureWorld;
    @Shadow @Final @Mutable private Set<BlockPos> erroredBlockEntities;
    @Shadow private void ensureData() {}
    @Shadow protected abstract void render3D(FormRenderingContext context);

    @Unique private DestructionRender bbs_physics$render;
    @Unique private DestructionRender.Part bbs_physics$part;
    @Unique private final Matrix4f bbs_physics$matrix = new Matrix4f();

    protected StructureFormRendererMixin(StructureForm form) { super(form); }

    @Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$offset(CallbackInfoReturnable<Vector3f> info)
    {
        if (this.bbs_physics$part != null) info.setReturnValue(this.bbs_physics$part.offset);
    }

    @Inject(method = "render3D", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$fragments(FormRenderingContext context, CallbackInfo info)
    {
        if (this.bbs_physics$part != null) return;
        var state = StructureDestruction.state(this.form);
        if (state == null || !StructureDestruction.isEnabled(this.form) || !state.structure.equals(this.form.structure.get()))
        {
            this.bbs_physics$render = null;
            return;
        }
        if (!state.isBroken()) return;
        this.ensureData();
        if (this.data == null) return;
        String biome = this.form.biome.get();
        if (this.bbs_physics$render == null || this.bbs_physics$render.state != state
            || this.bbs_physics$render.source != this.data || !this.bbs_physics$render.biome.equals(biome))
        {
            this.bbs_physics$render = new DestructionRender(state, this.data, biome);
        }
        BitSet detached = state.detached();
        var remainder = this.bbs_physics$render.remainder(detached);
        if (remainder != null) this.bbs_physics$draw(remainder, context, null);
        for (int i = detached.nextSetBit(0); i >= 0; i = detached.nextSetBit(i + 1))
        {
            this.bbs_physics$draw(this.bbs_physics$render.block(i), context,
                state.transform(i, context.transition, this.bbs_physics$matrix));
        }
        info.cancel();
    }

    @Unique
    private void bbs_physics$draw(DestructionRender.Part part, FormRenderingContext context, Matrix4f transform)
    {
        StructureRenderData savedData = this.data;
        StructureRenderWorld savedWorld = this.world;
        BakedStructure savedBaked = this.baked;
        List<BlockEntity> savedEntities = this.blockEntities;
        World savedStructureWorld = this.structureWorld;
        Set<BlockPos> savedErrors = this.erroredBlockEntities;
        this.bbs_physics$part = part;
        this.data = part.data;
        this.world = part.world;
        this.baked = part.baked;
        this.blockEntities = part.entities;
        this.structureWorld = part.structureWorld;
        this.erroredBlockEntities = part.errors;
        context.stack.push();
        if (context.world != null) context.world.push();
        try
        {
            if (transform != null)
            {
                MatrixStackUtils.multiply(context.stack, transform);
                if (context.world != null) MatrixStackUtils.multiply(context.world, transform);
            }
            this.render3D(context);
        }
        finally
        {
            context.stack.pop();
            if (context.world != null) context.world.pop();
            part.baked = this.baked;
            part.entities = this.blockEntities;
            part.structureWorld = this.structureWorld;
            this.data = savedData;
            this.world = savedWorld;
            this.baked = savedBaked;
            this.blockEntities = savedEntities;
            this.structureWorld = savedStructureWorld;
            this.erroredBlockEntities = savedErrors;
            this.bbs_physics$part = null;
        }
    }
}
