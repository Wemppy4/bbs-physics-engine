package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_physics.client.scene.FilmScenes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the addon hears that the author changed something.
 *
 * <p>A simulation is a consequence of the numbers it was fed, so an edited keyframe does not adjust
 * the result — it makes the whole result someone else's. Without this the editor's physics answers
 * the film as it was when the scene was built, and the only way to see an edit take is to leave the
 * film and come back, which is precisely what an animator reports as "coordinates do nothing".</p>
 *
 * <p>This handler is where every edit in BBS's editors lands: values are collected as they change
 * and submitted in batches, and each value in the batch passes through here on its way into the
 * undo stack. That makes it both the cheapest signal available — no polling, no hashing a film
 * twenty times a second — and one that is already debounced by the undo timer, so dragging a slider
 * for five seconds does not restart the simulation on every pixel.</p>
 *
 * <p>Only the cast is listened for. A camera clip or a subtitle changes nothing physical, and
 * restarting a simulation because the author moved the camera would be visible as the scene
 * twitching for no reason.</p>
 */
@Mixin(UIFormUndoHandler.class)
public class UIFormUndoHandlerMixin
{
    @Inject(method = "handleValue", at = @At("HEAD"))
    private void bbs_physics$onValueChanged(BaseValue value, CallbackInfo info)
    {
        if (value != null && value.getPath().toString().contains("replays"))
        {
            FilmScenes.onFilmEdited();
        }
    }
}
