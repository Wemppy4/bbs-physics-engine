package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.client.collision.UICollisionFormPanel;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the collision tab into every form editor.
 *
 * <p>{@code registerDefaultPanels} is the one call every form editor makes, and it makes it last —
 * after its own type-specific tabs and just before the general one. Hooking its head therefore
 * lands the collision tab in the same place for a model, a block, an item and a group alike,
 * without this mixin having to know that any of those exist.</p>
 *
 * <p>The tab belongs here rather than in BBS: every form has a shape, but a shape only means
 * anything while there is an engine to collide it with. Adding it to BBS proper was considered and
 * turned down — BBS without the addon has to stay BBS without the addon.</p>
 */
@Mixin(UIForm.class)
public class UIFormMixin
{
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "registerDefaultPanels", at = @At("HEAD"))
    private void bbs_physics$addCollisionPanel(CallbackInfo info)
    {
        UIForm editor = (UIForm) (Object) this;

        editor.registerPanel(new UICollisionFormPanel(editor), PhysicsKeys.COLLISION_TITLE, Icons.SHAPES);
    }
}
