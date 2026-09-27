package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.ragdoll.PhysicsDeaths;

@Mixin(value = UIFilmPanel.class, remap = false)
public class DeathFilmPanelMixin
{
    @Inject(method = "applyRecordedKeyframes", at = @At("TAIL"))
    private void bbs_physics$merge(Recorder recorder, Film film, CallbackInfo ci) { PhysicsDeaths.merge(film); }

    @Inject(method = "receiveActions", at = @At("TAIL"))
    private void bbs_physics$finish(String id, int replay, int tick, BaseType clips, CallbackInfo ci)
    {
        UIFilmPanel panel = (UIFilmPanel) (Object) this;
        Film film = panel.getData();
        if (film != null && film.getId().equals(id) && PhysicsDeaths.finish(film, replay, tick))
        {
            var selected = panel.replayEditor.getReplay();
            if (selected != null) panel.actionEditor.setClips(selected.actions);
            panel.save();
        }
    }
}
