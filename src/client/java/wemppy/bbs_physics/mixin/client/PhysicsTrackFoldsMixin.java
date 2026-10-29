package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.film.replays.CategoryFold;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wemppy.bbs_physics.client.forms.PhysicsTracks;

@Mixin(value = CategoryFold.class, remap = false)
public class PhysicsTrackFoldsMixin
{
    /** Only the first appearance sets a default; subsequent rebuilds retain the user's fold state. */
    @Redirect(method = "settle", at = @At(value = "INVOKE",
        target = "Lmchorse/bbs_mod/ui/framework/elements/input/items/FoldState;set(Ljava/lang/Object;Z)V"))
    private static void bbs_physics$initialFold(FoldState<String> folds, Object key, boolean expanded)
    {
        String id = (String) key;
        folds.set(id, expanded && !id.startsWith(PhysicsTracks.DISABLED_SECTION));
    }
}
