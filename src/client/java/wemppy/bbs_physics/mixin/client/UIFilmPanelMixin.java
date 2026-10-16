package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.forms.PhysicsKeys;
import wemppy.bbs_physics.client.scene.UIFilmPhysicsOverlayPanel;

/**
 * Adds "Film physics" to the film editor's gear menu, between the player settings and the details
 * — both are settings of the film itself. BBS's addon API has no point for this menu.
 */
@Mixin(value = UIFilmPanel.class, remap = false)
public abstract class UIFilmPanelMixin
{
    /** Before the details entry: its label is the one key in this menu built by {@code L10n.lang}. */
    @Inject(
        method = "fillFilmContextMenu(Lmchorse/bbs_mod/ui/utils/context/ContextMenuManager;)V",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/l10n/L10n;lang(Ljava/lang/String;)Lmchorse/bbs_mod/l10n/keys/IKey;", ordinal = 0)
    )
    private void bbs_physics$addFilmPhysics(ContextMenuManager menu, CallbackInfo ci)
    {
        UIFilmPanel panel = (UIFilmPanel) (Object) this;
        Film film = panel.getData();

        if (film == null)
        {
            return;
        }

        menu.action(Icons.PHYSICS, PhysicsKeys.FILM_PHYSICS, () ->
            UIOverlay.addOverlay(panel.getContext(), new UIFilmPhysicsOverlayPanel(film)));
    }
}
