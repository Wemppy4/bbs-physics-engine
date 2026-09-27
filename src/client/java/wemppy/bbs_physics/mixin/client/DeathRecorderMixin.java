package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.Recorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.ragdoll.PhysicsDeaths;

@Mixin(value = Recorder.class, remap = false)
public class DeathRecorderMixin
{
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbs_physics$begin(CallbackInfo ci)
    {
        Recorder r = (Recorder) (Object) this;
        PhysicsDeaths.tick(r.film, r.exception, r.initialTick, r.initialTick - 1);
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void bbs_physics$tick(CallbackInfo ci)
    {
        Recorder r = (Recorder) (Object) this;
        if (!r.hasNotStarted()) PhysicsDeaths.tick(r.film, r.exception, r.initialTick, r.tick);
    }

    @Inject(method = "shutdown", at = @At("TAIL"))
    private void bbs_physics$end(CallbackInfo ci) { PhysicsDeaths.end(); }
}
