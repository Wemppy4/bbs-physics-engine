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
    private static final String KEY_ENABLED = "enabled";
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
            map.getBool(KEY_ENABLED),
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

        if (body.enabled())
        {
            map.putBool(KEY_ENABLED, true);
        }

        if (body.passive())
        {
            map.putBool(KEY_PASSIVE, true);
        }

        if (body.mass() != FormBody.DEFAULT_MASS)
        {
            map.putFloat(KEY_MASS, body.mass());
        }

        if (body.friction() != FormBody.DEFAULT_FRICTION)
        {
            map.putFloat(KEY_FRICTION, body.friction());
        }

        if (body.restitution() != FormBody.DEFAULT_RESTITUTION)
        {
            map.putFloat(KEY_RESTITUTION, body.restitution());
        }

        return map;
    }

    /** Whether stored data says the body is on, without parsing the rest — the per-frame check. */
    public static boolean isEnabled(BaseType data)
    {
        return data instanceof MapType map && map.getBool(KEY_ENABLED);
    }
}
