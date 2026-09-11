package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingScreen;
import wemppy.bbs_physics.BBSPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Swaps the banner of the landing screen — the picture across the top of the card that greets an
 * author before they open anything — for the addon's own.
 *
 * <p>BBS 2.4 had this as an overridable method, and the override was taken from here because the
 * screens are built by BBS itself and there was no subclass of ours to put in their place. In 2.6
 * the screen was rewritten and the banner became a constant, so the swap is made one step later:
 * at the call that turns the link into a texture, which is the last place the choice is still a
 * value. The texture lives under {@code textures/banners/} the way BBS's does — the texture picker
 * skips that folder, which keeps the banner out of the list of pickable textures.</p>
 *
 * <p>This is the most fragile thing the addon does to BBS: it is a cosmetic swap with no hook
 * behind it, and the day {@code renderBanner} stops asking for its texture that way, the mixin
 * fails to apply and the addon refuses to load. Nothing about physics depends on it.</p>
 */
@Mixin(UILandingScreen.class)
public class UILandingScreenMixin
{
    private static final Link BBS_PHYSICS$BANNER = new Link(BBSPhysics.ASSETS, "textures/banners/bg.png");

    @ModifyArg(
        method = "renderBanner",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/graphics/texture/TextureManager;getTexture(Lmchorse/bbs_mod/resources/Link;)Lmchorse/bbs_mod/graphics/texture/Texture;")
    )
    private Link bbs_physics$swapBanner(Link link)
    {
        return BBS_PHYSICS$BANNER;
    }
}
