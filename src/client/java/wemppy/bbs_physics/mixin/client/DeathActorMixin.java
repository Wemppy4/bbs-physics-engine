package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wemppy.bbs_physics.ragdoll.DeathReplay;

/** Keep the film's physical body after Minecraft removes the dead actor shell. */
@Mixin(value = BaseFilmController.class, remap = false)
public class DeathActorMixin
{
    @Inject(method = "claimActor", at = @At("RETURN"), cancellable = true)
    private void bbs_physics$keepBody(Replay replay, IEntity entity, CallbackInfoReturnable<Boolean> cir)
    {
        BaseFilmController controller = (BaseFilmController) (Object) this;
        // The whole timeline owns this body's life: scrubbing before the event must stand it up.
        var death = DeathReplay.at(replay, Integer.MAX_VALUE);
        if (death == null) return;
        if (!death.baked.get() && (wemppy.bbs_physics.BBSPhysicsSettings.enabled == null
            || !wemppy.bbs_physics.BBSPhysicsSettings.enabled.get())) return;
        var actors = controller.getActors();
        Integer id = actors == null ? null : actors.get(replay.getId());
        if (id != null) BBSModClient.getFilms().markActorDrawn(id);
        // The recorded impact must flash even when editor playback fires no real bullet.
        int sinceHit = replay.getTick(controller.getTick()) - death.tick.get();
        if (sinceHit >= 0 && sinceHit < 10)
            entity.setHurtTimer(Math.max(entity.getHurtTimer(), 10 - sinceHit));
        entity.setDeathTime(0);
        cir.setReturnValue(true);
    }
}
