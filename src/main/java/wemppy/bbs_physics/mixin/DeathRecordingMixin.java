package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.film.Film;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.ragdoll.DeathCapture;

@Mixin(value = ActionManager.class, remap = false)
public class DeathRecordingMixin
{
    @Inject(method = "startRecording", at = @At("TAIL"))
    private void bbs_physics$begin(Film film, ServerPlayerEntity player, int tick, int countdown, int replayIndex, CallbackInfo ci)
    {
        var recorder = ((ActionManagerAccessor) this).bbs_physics$getRecorders().get(player);
        if (recorder != null) DeathCapture.begin(recorder, replayIndex);
    }
}
