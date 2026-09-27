package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.actions.ActionRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ActionRecorder.class, remap = false)
public interface ActionRecorderAccessor
{
    @Accessor("tick") int bbs_physics$getTick();
    @Accessor("countdown") int bbs_physics$getCountdown();
}
