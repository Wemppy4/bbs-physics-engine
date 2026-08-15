package mchorse.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.collision.IFormCollision;
import mchorse.bbs_physics.forms.IPhysicsForm;
import mchorse.bbs_physics.forms.PhysicsAuthorityValue;
import mchorse.bbs_physics.forms.PhysicsBodyState;
import mchorse.bbs_physics.forms.PhysicsForms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every form its physics: the collision markup, the rigid body modifier, the
 * animation-strength handle, and the runtime slot the simulation answers into.
 *
 * <p>Everything stored here is one more value in the form's own group, so it is saved, loaded,
 * copied, pasted and sent over the network by the machinery that already does all of that —
 * nothing in the addon has to know how a film is stored. The markup and the modifier are marked
 * invisible, which in BBS means "not a timeline track": a shape and a switch are descriptions, not
 * things to animate. The handle is deliberately visible, which is exactly what turns it into a
 * track (and it hides itself while the form has no physics — see {@link PhysicsAuthorityValue}).
 * </p>
 *
 * <p>All of it on the base {@code Form} rather than per type, because none of it is a property of
 * being a model: a block, an item and a group have a shape too, and any of them can be dropped.
 * That is the whole of Р7 in one line — physics is something an existing object <em>gains</em>,
 * not a wrapper it has to be put inside.</p>
 */
@Mixin(Form.class)
public class FormMixin implements IFormCollision, IPhysicsForm
{
    @Unique
    private ValueData bbs_physics$collision;

    @Unique
    private ValueData bbs_physics$body;

    @Unique
    private ValueFloat bbs_physics$authority;

    @Unique
    private PhysicsBodyState bbs_physics$bodyState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addPhysics(CallbackInfo info)
    {
        ValueData collision = new ValueData(FormCollisions.KEY);
        ValueData body = new ValueData(PhysicsForms.BODY_KEY);

        collision.invisible();
        body.invisible();

        ValueFloat authority = new PhysicsAuthorityValue(PhysicsForms.AUTHORITY_KEY);

        authority.slider();

        this.bbs_physics$collision = collision;
        this.bbs_physics$body = body;
        this.bbs_physics$authority = authority;

        Form form = (Form) (Object) this;

        form.add(collision);
        form.add(body);
        form.add(authority);
    }

    @Override
    public ValueData bbs_physics$getCollision()
    {
        return this.bbs_physics$collision;
    }

    @Override
    public ValueData bbs_physics$getBody()
    {
        return this.bbs_physics$body;
    }

    @Override
    public ValueFloat bbs_physics$getAuthority()
    {
        return this.bbs_physics$authority;
    }

    @Override
    public PhysicsBodyState bbs_physics$getBodyState()
    {
        return this.bbs_physics$bodyState;
    }

    @Override
    public void bbs_physics$setBodyState(PhysicsBodyState state)
    {
        this.bbs_physics$bodyState = state;
    }
}
