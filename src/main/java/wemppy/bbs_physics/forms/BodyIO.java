package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

/**
 * The rigid body modifier on disk: {@code {"enabled": true, "mass": 12.0, ...}}.
 *
 * <p>Only what differs from the default is written, and a form without the modifier writes nothing
 * at all — the same rule the collision markup and the ragdoll setup follow, and the reason a film
 * saved with the addon loaded is identical to one saved without it until physics is actually used.
 * </p>
 */
public final class BodyIO
{
    private static final String KEY_PASSIVE = "passive";
    private static final String KEY_MASS = "mass";
    private static final String KEY_FRICTION = "friction";
    private static final String KEY_RESTITUTION = "restitution";
    private static final String KEY_LINEAR_DAMPING = "linear_damping";
    private static final String KEY_ANGULAR_DAMPING = "angular_damping";
    private static final String KEY_GRAVITY = "gravity";
    private static final String KEY_ASLEEP = "asleep";
    private static final String KEY_LOCK_MOVE = "lock_move";
    private static final String KEY_LOCK_SPIN = "lock_spin";

    private BodyIO()
    {}

    public static FormBody fromData(BaseType data)
    {
        if (!(data instanceof MapType map) || map.isEmpty())
        {
            return FormBody.EMPTY;
        }

        return new FormBody(
            ModifierIO.isEnabled(map),
            map.getBool(KEY_PASSIVE),
            map.getFloat(KEY_MASS, FormBody.DEFAULT_MASS),
            map.getFloat(KEY_FRICTION, FormBody.DEFAULT_FRICTION),
            map.getFloat(KEY_RESTITUTION, FormBody.DEFAULT_RESTITUTION),
            map.getFloat(KEY_LINEAR_DAMPING, FormBody.DEFAULT_LINEAR_DAMPING),
            map.getFloat(KEY_ANGULAR_DAMPING, FormBody.DEFAULT_ANGULAR_DAMPING),
            map.getFloat(KEY_GRAVITY, FormBody.DEFAULT_GRAVITY),
            map.getBool(KEY_ASLEEP),
            map.getInt(KEY_LOCK_MOVE, 0),
            map.getInt(KEY_LOCK_SPIN, 0));
    }

    /** The keys the keyframable numbers used to be stored under, before they became form values. */
    private static final String[] KNOB_KEYS = {KEY_MASS, KEY_FRICTION, KEY_RESTITUTION, KEY_LINEAR_DAMPING, KEY_ANGULAR_DAMPING, KEY_GRAVITY};

    /** Whole, numbers included — what a preset or the clipboard carries. */
    public static MapType toData(FormBody body)
    {
        return toData(body, true);
    }

    /**
     * @param knobs whether the keyframable numbers go in. The form's own storage leaves them out:
     *              they live as values of the form ({@code PhysicsKnobValue}), and a copy in the
     *              blob would be a second source of truth
     */
    public static MapType toData(FormBody body, boolean knobs)
    {
        MapType map = new MapType();

        if (body == null || body.isEmpty())
        {
            return map;
        }

        ModifierIO.putEnabled(map, body.enabled());
        ModifierIO.putFlag(map, KEY_PASSIVE, body.passive());

        if (knobs)
        {
            ModifierIO.putFloat(map, KEY_MASS, body.mass(), FormBody.DEFAULT_MASS);
            ModifierIO.putFloat(map, KEY_FRICTION, body.friction(), FormBody.DEFAULT_FRICTION);
            ModifierIO.putFloat(map, KEY_RESTITUTION, body.restitution(), FormBody.DEFAULT_RESTITUTION);
            ModifierIO.putFloat(map, KEY_LINEAR_DAMPING, body.linearDamping(), FormBody.DEFAULT_LINEAR_DAMPING);
            ModifierIO.putFloat(map, KEY_ANGULAR_DAMPING, body.angularDamping(), FormBody.DEFAULT_ANGULAR_DAMPING);
            ModifierIO.putFloat(map, KEY_GRAVITY, body.gravity(), FormBody.DEFAULT_GRAVITY);
        }

        ModifierIO.putFlag(map, KEY_ASLEEP, body.asleep());

        if (body.lockMove() != 0)
        {
            map.putInt(KEY_LOCK_MOVE, body.lockMove());
        }

        if (body.lockSpin() != 0)
        {
            map.putInt(KEY_LOCK_SPIN, body.lockSpin());
        }

        return map;
    }

    /** Whether stored data still carries numbers from before they became form values. */
    public static boolean hasKnobs(BaseType data)
    {
        return ModifierIO.hasAny(data, KNOB_KEYS);
    }

    /** Whether stored data says the body is on, without parsing the rest — the per-frame check. */
    public static boolean isEnabled(BaseType data)
    {
        return ModifierIO.isEnabled(data);
    }

    /**
     * Whether stored data says the body is passive, without parsing the rest. Its own reader for the
     * same reason {@link #isEnabled} has one: the authority handle asks this of every simulated form
     * on every tick and every drawn frame, and building a record to read one boolean is work done
     * tens of thousands of times a second for nothing.
     */
    public static boolean isPassive(BaseType data)
    {
        return data instanceof MapType map && map.getBool(KEY_PASSIVE);
    }
}
