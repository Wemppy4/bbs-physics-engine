package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.scene.FilmBake;

/** Adds baking beside the selected actor's properties, without changing BBS itself. */
@Mixin(UIReplayPropertiesPanel.class)
public abstract class UIReplayPropertiesPanelMixin
{
    @Shadow @Final private UIFilmPanel filmPanel;
    @Shadow private Replay replay;
    @Shadow public UIElement properties;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addBakeButton(UIFilmPanel panel, CallbackInfo info)
    {
        this.properties.add(FilmBake.button(this.filmPanel, () -> this.replay));
    }
}
