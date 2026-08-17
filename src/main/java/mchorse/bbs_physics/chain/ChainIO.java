package mchorse.bbs_physics.chain;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Chain modifier on disk: {@code {"enabled": true, "bones": [bone], "stiffness": …}}.
 *
 * <p>Only what differs from the defaults is written, and a form the modifier was never added to
 * stores nothing at all — staying byte-identical to a form the addon never saw, the same bargain
 * the collision markup and the ragdoll keep.</p>
 */
public final class ChainIO
{
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BONES = "bones";
    private static final String KEY_STIFFNESS = "stiffness";
    private static final String KEY_DAMPING = "damping";
    private static final String KEY_GRAVITY = "gravity";
    private static final String KEY_MASS = "mass";
    private static final String KEY_SELF_COLLISION = "self_collision";

    private ChainIO()
    {}

    public static FormChain fromData(BaseType data)
    {
        if (!(data instanceof MapType map) || map.isEmpty())
        {
            return FormChain.EMPTY;
        }

        Set<String> bones = new LinkedHashSet<>();

        if (map.has(KEY_BONES, BaseType.TYPE_LIST))
        {
            ListType list = map.getList(KEY_BONES);

            for (int i = 0; i < list.size(); i++)
            {
                String bone = list.getString(i);

                if (!bone.isEmpty())
                {
                    bones.add(bone);
                }
            }
        }

        return new FormChain(
            map.getBool(KEY_ENABLED),
            bones,
            map.getFloat(KEY_STIFFNESS, FormChain.DEFAULT_STIFFNESS),
            map.getFloat(KEY_DAMPING, FormChain.DEFAULT_DAMPING),
            map.getFloat(KEY_GRAVITY, FormChain.DEFAULT_GRAVITY),
            map.getFloat(KEY_MASS, FormChain.DEFAULT_MASS),
            map.getBool(KEY_SELF_COLLISION));
    }

    public static MapType toData(FormChain chain)
    {
        MapType map = new MapType();

        if (chain == null || chain.isEmpty())
        {
            return map;
        }

        if (chain.enabled())
        {
            map.putBool(KEY_ENABLED, true);
        }

        if (!chain.bones().isEmpty())
        {
            ListType bones = new ListType();

            for (String bone : chain.bones())
            {
                bones.addString(bone);
            }

            map.put(KEY_BONES, bones);
        }

        putIfChanged(map, KEY_STIFFNESS, chain.stiffness(), FormChain.DEFAULT_STIFFNESS);
        putIfChanged(map, KEY_DAMPING, chain.damping(), FormChain.DEFAULT_DAMPING);
        putIfChanged(map, KEY_GRAVITY, chain.gravity(), FormChain.DEFAULT_GRAVITY);
        putIfChanged(map, KEY_MASS, chain.mass(), FormChain.DEFAULT_MASS);

        if (chain.selfCollision())
        {
            map.putBool(KEY_SELF_COLLISION, true);
        }

        return map;
    }

    /** Whether stored data says the modifier is on, without parsing the rest — the per-frame check. */
    public static boolean isEnabled(BaseType data)
    {
        return data instanceof MapType map && map.getBool(KEY_ENABLED);
    }

    private static void putIfChanged(MapType map, String key, float value, float fallback)
    {
        if (value != fallback)
        {
            map.putFloat(key, value);
        }
    }
}
