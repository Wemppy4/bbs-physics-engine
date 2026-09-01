package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.settings.values.core.ValueData;
import wemppy.bbs_physics.chain.ChainKnob;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.forms.IModelPhysicsForm;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.ragdoll.FormRagdolls;
import wemppy.bbs_physics.ragdoll.RagdollKnob;
import wemppy.bbs_physics.ragdoll.RagdollState;
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

    @Unique
    private PhysicsKnobValue[] bbs_physics$chainKnobs;

    @Unique
    private PhysicsKnobValue[] bbs_physics$ragdollKnobs;

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

        /* The keyframable numbers of both modifiers, each hiding while its modifier is off — see
         * PhysicsKnobValue, and FormMixin for the rule that keeps them out of the file until then. */
        this.bbs_physics$chainKnobs = new PhysicsKnobValue[ChainKnob.values().length];
        this.bbs_physics$ragdollKnobs = new PhysicsKnobValue[RagdollKnob.values().length];

        for (ChainKnob knob : ChainKnob.values())
        {
            PhysicsKnobValue value = new PhysicsKnobValue(knob.id, knob.fallback, knob.min, knob.max, FormChains::isEnabled);

            this.bbs_physics$chainKnobs[knob.ordinal()] = value;
            form.add(value);
        }

        for (RagdollKnob knob : RagdollKnob.values())
        {
            PhysicsKnobValue value = new PhysicsKnobValue(knob.id, knob.fallback, knob.min, knob.max, FormRagdolls::isEnabled);

            this.bbs_physics$ragdollKnobs[knob.ordinal()] = value;
            form.add(value);
        }
    }

    @Override
    public PhysicsKnobValue bbs_physics$getChainKnob(ChainKnob knob)
    {
        return this.bbs_physics$chainKnobs[knob.ordinal()];
    }

    @Override
    public PhysicsKnobValue bbs_physics$getRagdollKnob(RagdollKnob knob)
    {
        return this.bbs_physics$ragdollKnobs[knob.ordinal()];
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
