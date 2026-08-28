package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.dashboard.panels.UISelectionScreen;
import mchorse.bbs_physics.BBSPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps the banner of the selection screen — the picture above the list of films, worlds and the
 * rest — for the addon's own.
 *
 * <p>BBS made the banner overridable, but only for a subclass of its own; the screens are built
 * by BBS itself, so there is no subclass of ours to put there. The override is taken from here
 * instead. The texture lives under {@code textures/banners/} the way BBS's does — the texture
 * picker skips that folder, which keeps the banner out of the list of pickable textures.</p>
 */
@Mixin(UISelectionScreen.class)
public class UISelectionScreenMixin
{
    private static final Link BBS_PHYSICS$BANNER = new Link(BBSPhysics.ASSETS, "textures/banners/bg.png");

    @Inject(method = "getBannerTexture", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$swapBanner(CallbackInfoReturnable<Link> info)
    {
        info.setReturnValue(BBS_PHYSICS$BANNER);
    }
}
