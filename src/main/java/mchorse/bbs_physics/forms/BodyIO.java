package mchorse.bbs_physics.forms;

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
            map.getFloat(KEY_RESTITUTION, FormBody.DEFAULT_RESTITUTION));
    }

    public static MapType toData(FormBody body)
    {
        MapType map = new MapType();

        if (body == null || body.isEmpty())
        {
            return map;
        }

        ModifierIO.putEnabled(map, body.enabled());
        ModifierIO.putFlag(map, KEY_PASSIVE, body.passive());
        ModifierIO.putFloat(map, KEY_MASS, body.mass(), FormBody.DEFAULT_MASS);
        ModifierIO.putFloat(map, KEY_FRICTION, body.friction(), FormBody.DEFAULT_FRICTION);
        ModifierIO.putFloat(map, KEY_RESTITUTION, body.restitution(), FormBody.DEFAULT_RESTITUTION);

        return map;
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
