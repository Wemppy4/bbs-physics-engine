package wemppy.bbs_physics.chain;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import wemppy.bbs_physics.forms.IModelPhysicsForm;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.ragdoll.RagdollState;

/**
 * Reading and writing a model form's chain modifier — the same arrangement as {@code FormRagdolls}:
 * the data lives on the form itself, put there by a mixin, and travels with it through save, copy
 * and network for free.
 */
public final class FormChains
{
    /** The key the modifier is stored under, prefixed so it cannot collide with a BBS key. */
    public static final String KEY = "bbs_physics_chain";

    private FormChains()
    {}

    /** The modifier of {@code form}, never null; empty for anything that is not a model. */
    public static FormChain get(Form form)
    {
        ValueData value = value(form);

        if (value == null || !(form instanceof IModelPhysicsForm model))
        {
            return FormChain.EMPTY;
        }

        FormChain chain = ChainIO.fromData(value.get());

        /* The numbers live as knob values of the form, where keyframes reach them — see
         * PhysicsForms.getBody for the rule and the one-time migration of an older blob. */
        if (ChainIO.hasKnobs(value.get()))
        {
            for (ChainKnob knob : ChainKnob.values())
            {
                model.bbs_physics$getChainKnob(knob).set(knob.of(chain));
            }

            MapType stripped = ChainIO.toData(chain, false);

            value.set(stripped.isEmpty() ? null : stripped);

            return chain;
        }

        for (ChainKnob knob : ChainKnob.values())
        {
            chain = knob.into(chain, model.bbs_physics$getChainKnob(knob).get());
        }

        return chain;
    }

    public static void set(Form form, FormChain chain)
    {
        ValueData value = value(form);

        if (value == null || !(form instanceof IModelPhysicsForm model))
        {
            return;
        }

        for (ChainKnob knob : ChainKnob.values())
        {
            PhysicsKnobValue stored = model.bbs_physics$getChainKnob(knob);
            float wanted = knob.of(chain);

            if (stored.getOriginalValue() != wanted)
            {
                stored.set(wanted);
            }
        }

        MapType map = ChainIO.toData(chain, false);

        value.set(map.isEmpty() ? null : map);
    }

    /** Whether the modifier is switched on, without parsing the bones — the per-frame check. */
    public static boolean isEnabled(Form form)
    {
        ValueData value = value(form);

        return value != null && ChainIO.isEnabled(value.get());
    }

    public static RagdollState getState(Form form)
    {
        return form instanceof IModelPhysicsForm chain ? chain.bbs_physics$getChainState() : null;
    }

    public static void setState(Form form, RagdollState state)
    {
        if (form instanceof IModelPhysicsForm chain)
        {
            chain.bbs_physics$setChainState(state);
        }
    }

    private static ValueData value(Form form)
    {
        return form instanceof IModelPhysicsForm holder ? holder.bbs_physics$getChain() : null;
    }
}
