package mchorse.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.collision.IFormCollision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every form a place to keep its collision markup.
 *
 * <p>It is one more value in the form's own group, so it is saved, loaded, copied, pasted and sent
 * over the network by the machinery that already does all of that for the form's other values —
 * nothing here has to know how a film is stored. Marked invisible, which in BBS means "not a
 * timeline track": collision is a description of a shape, not something to animate.</p>
 *
 * <p>Added from the base {@code Form} constructor rather than per form type, because the markup is
 * not a property of being a model — a block, an item and a group all have a shape too.</p>
 */
@Mixin(Form.class)
public class FormMixin implements IFormCollision
{
    @Unique
    private ValueData bbs_physics$collision;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addCollision(CallbackInfo info)
    {
        ValueData collision = new ValueData(FormCollisions.KEY);

        collision.invisible();

        this.bbs_physics$collision = collision;

        ((Form) (Object) this).add(collision);
    }

    @Override
    public ValueData bbs_physics$getCollision()
    {
        return this.bbs_physics$collision;
    }
}
