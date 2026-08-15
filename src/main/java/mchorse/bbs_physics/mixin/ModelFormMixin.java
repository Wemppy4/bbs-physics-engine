package mchorse.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.IRagdollForm;
import mchorse.bbs_physics.ragdoll.RagdollAuthorityValue;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every model form its ragdoll: the stored setup (joints, the enabled flag) and the
 * animation-strength handle.
 *
 * <p>On {@code ModelForm} rather than {@code Form} because a ragdoll is a property of having a
 * skeleton — a block has a shape (collision goes on every form) but nothing to joint. The handle
 * is a visible float value, which is all it takes for BBS's film editor to offer it as a
 * keyframeable track; visibility is conditional on the ragdoll being enabled, so the track only
 * exists where it means something.</p>
 */
@Mixin(ModelForm.class)
public class ModelFormMixin implements IRagdollForm
{
    @Unique
    private ValueData bbs_physics$ragdoll;

    @Unique
    private ValueFloat bbs_physics$authority;

    @Unique
    private RagdollState bbs_physics$ragdollState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addRagdoll(CallbackInfo info)
    {
        ValueData ragdoll = new ValueData(FormRagdolls.KEY);

        ragdoll.invisible();

        ValueFloat authority = new RagdollAuthorityValue(FormRagdolls.AUTHORITY_KEY);

        authority.slider();

        this.bbs_physics$ragdoll = ragdoll;
        this.bbs_physics$authority = authority;

        Form form = (Form) (Object) this;

        form.add(ragdoll);
        form.add(authority);
    }

    @Override
    public ValueData bbs_physics$getRagdoll()
    {
        return this.bbs_physics$ragdoll;
    }

    @Override
    public ValueFloat bbs_physics$getAuthority()
    {
        return this.bbs_physics$authority;
    }

    @Override
    public RagdollState bbs_physics$getRagdollState()
    {
        return this.bbs_physics$ragdollState;
    }

    @Override
    public void bbs_physics$setRagdollState(RagdollState state)
    {
        this.bbs_physics$ragdollState = state;
    }
}
