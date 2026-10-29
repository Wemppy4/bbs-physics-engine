package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.forms.forms.Form;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wemppy.bbs_physics.client.forms.PhysicsTracks;

import java.util.List;

@Mixin(value = TrackCatalog.class, remap = false)
public class PhysicsTrackCatalogMixin
{
    @Inject(method = "of(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/film/replays/FormProperties;)Ljava/util/List;", at = @At("RETURN"))
    private static void bbs_physics$filter(Form root, FormProperties properties, CallbackInfoReturnable<List<TrackDescriptor>> info)
    {
        PhysicsTracks.filter(info.getReturnValue());
    }
}
