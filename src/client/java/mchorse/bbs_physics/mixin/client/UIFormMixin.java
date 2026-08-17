package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.client.collision.UICollisionFormPanel;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.client.forms.UIBalloonForm;
import mchorse.bbs_physics.client.forms.UIClothForm;
import mchorse.bbs_physics.client.forms.UIPhysicsFormPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the Physics tab into every form editor.
 *
 * <p>{@code registerDefaultPanels} is the one call every form editor makes, and it makes it last —
 * after its own type-specific tabs and just before the general one. Hooking its head therefore
 * lands the tab in the same place for a model, a block, an item and a group alike, without this
 * mixin having to know that any of those exist.</p>
 *
 * <p><b>Two tabs, not three and not one.</b> Collision, ragdoll and physics were three tabs until
 * Р7 folded them into one; the ragdoll half then turned out to need its own answer — which bones
 * fall, as opposed to which have a shape — and with that answer in place the tab split back into
 * two. Shape is described once per model in Collision and forgotten; Physics is what an author
 * returns to. The three-tab arrangement is not coming back: it had half the ragdoll tab greyed out
 * telling the author to go and use the collision tab first.</p>
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
    private void bbs_physics$addPhysicsPanel(CallbackInfo info)
    {
        UIForm editor = (UIForm) (Object) this;

        if (editor instanceof UIClothForm || editor instanceof UIBalloonForm)
        {
            /* The soft forms are their own kind of physics: they have no collision markup to
             * describe and no modifier to add or remove, so both shared tabs would be rows of
             * things that do not apply. Everything cloth- or balloon-shaped lives in its own tab
             * instead. */
            return;
        }

        editor.registerPanel(new UICollisionFormPanel(editor), PhysicsKeys.COLLISION_TITLE, Icons.SHAPES);
        editor.registerPanel(new UIPhysicsFormPanel(editor), PhysicsKeys.PHYSICS_TITLE, Icons.PHYSICS);
    }
}
