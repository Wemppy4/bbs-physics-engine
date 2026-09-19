package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.StructureForm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.forms.ValueData;
import wemppy.bbs_physics.structure.DestructionState;
import wemppy.bbs_physics.structure.IStructurePhysicsForm;
import wemppy.bbs_physics.structure.StructureDestruction;

@Mixin(StructureForm.class)
public class StructureFormMixin implements IStructurePhysicsForm
{
    @Unique private ValueData bbs_physics$destruction;
    @Unique private DestructionState bbs_physics$destructionState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$init(CallbackInfo info)
    {
        this.bbs_physics$destruction = new ValueData(StructureDestruction.KEY);
        this.bbs_physics$destruction.invisible();
        ((StructureForm) (Object) this).add(this.bbs_physics$destruction);
    }

    @Override public ValueData bbs_physics$getDestruction() { return this.bbs_physics$destruction; }
    @Override public DestructionState bbs_physics$getDestructionState() { return this.bbs_physics$destructionState; }
    @Override public void bbs_physics$setDestructionState(DestructionState state) { this.bbs_physics$destructionState = state; }
}
