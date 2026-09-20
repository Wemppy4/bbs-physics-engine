package wemppy.bbs_physics.ragdoll;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.forms.ValueData;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsType;
import wemppy.bbs_physics.forms.IModelPhysicsForm;
import wemppy.bbs_physics.forms.PhysicsKnobValue;

/**
 * Reading and writing a model form's ragdoll setup — the same arrangement as
 * {@code FormCollisions}: the data lives on the form itself, put there by a mixin, and travels
 * with it through save, copy and network for free.
 */
public final class FormRagdolls
{
    /** The key the setup is stored under, prefixed for the same reason the collision key is. */
    public static final String KEY = "bbs_physics:ragdoll";

    private FormRagdolls()
    {}

    /** The setup of {@code form}, never null; empty for anything that is not a model. */
    public static FormRagdoll get(Form form)
    {
        ValueData value = value(form);

        if (value == null || !(form instanceof IModelPhysicsForm model))
        {
            return FormRagdoll.EMPTY;
        }

        FormRagdoll ragdoll = RagdollIO.fromData(value.get());

        /* The numbers live as knob values of the form, where keyframes reach them — see
         * PhysicsForms.getBody for the rule and the one-time migration of an older blob. */
        if (RagdollIO.hasKnobs(value.get()))
        {
            for (RagdollKnob knob : RagdollKnob.values())
            {
                model.bbs_physics$getRagdollKnob(knob).set(knob.of(ragdoll));
            }

            MapType stripped = RagdollIO.toData(ragdoll, false);

            value.set(stripped.isEmpty() ? null : stripped);

            return ragdoll.withEnabled(isEnabled(form));
        }

        for (RagdollKnob knob : RagdollKnob.values())
        {
            ragdoll = knob.into(ragdoll, model.bbs_physics$getRagdollKnob(knob).get());
        }

        return ragdoll.withEnabled(isEnabled(form));
    }

    public static void set(Form form, FormRagdoll ragdoll)
    {
        ValueData value = value(form);

        if (value == null || !(form instanceof IModelPhysicsForm model))
        {
            return;
        }

        for (RagdollKnob knob : RagdollKnob.values())
        {
            PhysicsKnobValue stored = model.bbs_physics$getRagdollKnob(knob);
            float wanted = knob.of(ragdoll);

            if (stored.getOriginalValue() != wanted)
            {
                stored.set(wanted);
            }
        }

        MapType map = RagdollIO.toData(ragdoll, false);

        value.set(map.isEmpty() ? null : map);
    }

    /** Whether the ragdoll is switched on, without parsing the joints — the per-frame check. */
    public static boolean isEnabled(Form form)
    {
        return PhysicsForms.getType(form) == PhysicsType.RAGDOLL;
    }

    public static RagdollState getState(Form form)
    {
        return form instanceof IModelPhysicsForm ragdoll ? ragdoll.bbs_physics$getRagdollState() : null;
    }

    public static void setState(Form form, RagdollState state)
    {
        if (form instanceof IModelPhysicsForm ragdoll)
        {
            ragdoll.bbs_physics$setRagdollState(state);
        }
    }

    private static ValueData value(Form form)
    {
        return form instanceof IModelPhysicsForm holder ? holder.bbs_physics$getRagdoll() : null;
    }
}
