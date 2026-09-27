package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.network.ClientNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.ragdoll.PhysicsDeaths;

@Mixin(value = ClientNetwork.class, remap = false)
public class DeathActionRecordingMixin
{
    @Inject(method = "sendActionRecording", at = @At("HEAD"))
    private static void bbs_physics$take(String filmId, int replayId, int tick, int countdown, boolean state, CallbackInfo ci)
    {
        PhysicsDeaths.actionRecording(filmId, replayId, tick, state);
    }
}
