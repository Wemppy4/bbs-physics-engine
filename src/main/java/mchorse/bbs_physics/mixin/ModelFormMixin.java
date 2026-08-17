package mchorse.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.forms.IModelPhysicsForm;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every model form its ragdoll: the stored setup — joints and the enabled flag.
 *
 * <p>On {@code ModelForm} rather than {@code Form} because a ragdoll is a property of having a
 * skeleton — a block has a shape (collision goes on every form) but nothing to joint.</p>
 *
 * <p>The animation-strength handle used to live here too and now sits on {@code Form} beside the
 * rigid body modifier: one handle for both (§4), because a crate and a character mean the same
 * thing by it and an author should only have to learn it once.</p>
 */
@Mixin(ModelForm.class)
public class ModelFormMixin implements IModelPhysicsForm
{
    @Unique
    private ValueData bbs_physics$ragdoll;

    @Unique
    private RagdollState bbs_physics$ragdollState;

    @Unique
    private ValueData bbs_physics$chain;

    @Unique
    private RagdollState bbs_physics$chainState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addRagdoll(CallbackInfo info)
    {
        ValueData ragdoll = new ValueData(FormRagdolls.KEY);
        ValueData chain = new ValueData(FormChains.KEY);

        ragdoll.invisible();
        chain.invisible();

        this.bbs_physics$ragdoll = ragdoll;
        this.bbs_physics$chain = chain;

        Form form = (Form) (Object) this;

        form.add(ragdoll);
        form.add(chain);
    }

    @Override
    public ValueData bbs_physics$getChain()
    {
        return this.bbs_physics$chain;
    }

    @Override
    public RagdollState bbs_physics$getChainState()
    {
        return this.bbs_physics$chainState;
    }

    @Override
    public void bbs_physics$setChainState(RagdollState state)
    {
        this.bbs_physics$chainState = state;
    }

    @Override
    public ValueData bbs_physics$getRagdoll()
    {
        return this.bbs_physics$ragdoll;
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
