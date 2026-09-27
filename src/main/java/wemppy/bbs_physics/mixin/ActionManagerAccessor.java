package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.ActionRecorder;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(value = ActionManager.class, remap = false)
public interface ActionManagerAccessor
{
    @Accessor("recorders") Map<ServerPlayerEntity, ActionRecorder> bbs_physics$getRecorders();
}
