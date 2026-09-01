package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.client.scene.FilmScenes;
import wemppy.bbs_physics.client.scene.SceneStatus;
import wemppy.bbs_physics.client.scene.SceneStatusHUD;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the scene's numbers over the film editor's viewport.
 *
 * <p>{@code renderHUD} is where BBS writes its own overlays into the viewport — the recording dot,
 * the tick counter — so it is both the right place and one that already receives the viewport's
 * area, which a readout pinned to a corner needs. Drawing from the world pass instead would mean
 * billboarding text in three dimensions to say something that is not about any place in the scene.
 * </p>
 */
@Mixin(UIFilmController.class)
public class UIFilmControllerMixin
{
    @Inject(method = "renderHUD", at = @At("TAIL"))
    private void bbs_physics$onRenderHUD(UIContext context, Area area, CallbackInfo info)
    {
        if (BBSPhysicsSettings.debug == null || !BBSPhysicsSettings.debug.get())
        {
            return;
        }

        UIFilmController self = (UIFilmController) (Object) this;
        SceneStatus status = FilmScenes.getStatus(self.editorController);

        if (status != null)
        {
            SceneStatusHUD.render(context, area, status);
        }
    }
}
