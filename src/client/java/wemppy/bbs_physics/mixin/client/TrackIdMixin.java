package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shorten display labels only; serialized track addresses retain their namespace. */
@Mixin(value = TrackId.class, remap = false)
public class TrackIdMixin
{
    @Inject(method = "label", at = @At("RETURN"), cancellable = true)
    private void bbs_physics$label(CallbackInfoReturnable<String> info)
    {
        TrackId track = (TrackId) (Object) this;
        String label = info.getReturnValue();

        if (track.kind() == TrackKind.PROPERTY && label.startsWith("bbs_physics_"))
        {
            info.setReturnValue(label.substring("bbs_physics_".length()));
        }
    }
}
