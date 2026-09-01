package wemppy.bbs_physics.collision;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import wemppy.bbs_physics.forms.IPhysicsForm;

/**
 * Reading and writing a form's collision markup.
 *
 * <p>The markup is stored on the form itself, as one more of its values, put there by a mixin —
 * BBS knows nothing about it. That was a deliberate choice over adding a collision tab to BBS
 * proper (§5.2, and it is not to be re-proposed): <b>BBS without the addon has to stay BBS without
 * the addon</b>, and a settings tab that does nothing at all is worse than no tab.</p>
 *
 * <p>The cost of that choice, named honestly: a form saved with the addon and then re-saved by a
 * BBS that does not have it loses the markup, because a value group writes back only the children
 * it knows. That is a general property of BBS's storage, not something collision introduces, and
 * it is on the list of things worth fixing in BBS one day.</p>
 */
public final class FormCollisions
{
    /**
     * The key the markup is stored under. Prefixed, and deliberately so: it shares a namespace
     * with every value BBS may add to a form in the future, and a collision there would silently
     * swap one for the other.
     */
    public static final String KEY = "bbs_physics_collision";

    private FormCollisions()
    {}

    /**
     * The markup of {@code form}, never null. Empty when the form has none — and also when the
     * mixin is not in place, which is what a form built by a BBS the addon never attached to
     * looks like.
     */
    public static FormCollision get(Form form)
    {
        ValueData value = value(form);

        return value == null ? FormCollision.EMPTY : CollisionIO.fromData(value.get());
    }

    /** Writes the markup back onto the form, clearing the value when there is nothing to store. */
    public static void set(Form form, FormCollision collision)
    {
        ValueData value = value(form);

        if (value == null)
        {
            return;
        }

        MapType map = CollisionIO.toData(collision);

        value.set(map.isEmpty() ? null : map);
    }

    /** Whether {@code form} has anything marked up at all — the cheap check before doing work. */
    public static boolean has(Form form)
    {
        ValueData value = value(form);

        return value != null && value.get() instanceof MapType map && !map.isEmpty();
    }

    private static ValueData value(Form form)
    {
        return form instanceof IPhysicsForm holder ? holder.bbs_physics$getCollision() : null;
    }
}
