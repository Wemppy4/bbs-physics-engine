package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.client.scene.UIPhysicsScenePanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the scene's physics panel to the dashboard.
 *
 * <p>{@code registerPanels} is where BBS lists its own — films, models, textures — and the tail of
 * it is the natural place for one more, after everything BBS ships with. The panel is the addon's,
 * so BBS without the addon has no idea it exists.</p>
 */
@Mixin(UIDashboard.class)
public class UIDashboardMixin
{
    @Inject(method = "registerPanels", at = @At("TAIL"))
    private void bbs_physics$addScenePanel(CallbackInfo info)
    {
        UIDashboard dashboard = (UIDashboard) (Object) this;

        dashboard.getPanels().registerPanel(new UIPhysicsScenePanel(dashboard), PhysicsKeys.SCENE_TITLE, Icons.PHYSICS);
    }
}
