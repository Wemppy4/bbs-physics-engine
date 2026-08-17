package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.forms.IModelPhysicsForm;

/**
 * Reading and writing a model form's ragdoll setup — the same arrangement as
 * {@code FormCollisions}: the data lives on the form itself, put there by a mixin, and travels
 * with it through save, copy and network for free.
 */
public final class FormRagdolls
{
    /** The key the setup is stored under, prefixed for the same reason the collision key is. */
    public static final String KEY = "bbs_physics_ragdoll";

    private FormRagdolls()
    {}

    /** The setup of {@code form}, never null; empty for anything that is not a model. */
    public static FormRagdoll get(Form form)
    {
        ValueData value = value(form);

        return value == null ? FormRagdoll.EMPTY : RagdollIO.fromData(value.get());
    }

    public static void set(Form form, FormRagdoll ragdoll)
    {
        ValueData value = value(form);

        if (value == null)
        {
            return;
        }

        MapType map = RagdollIO.toData(ragdoll);

        value.set(map.isEmpty() ? null : map);
    }

    /** Whether the ragdoll is switched on, without parsing the joints — the per-frame check. */
    public static boolean isEnabled(Form form)
    {
        ValueData value = value(form);

        return value != null && RagdollIO.isEnabled(value.get());
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
