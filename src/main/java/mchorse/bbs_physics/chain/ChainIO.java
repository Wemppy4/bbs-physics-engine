package mchorse.bbs_physics.chain;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_physics.forms.ModifierIO;

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

        Set<String> bones = ModifierIO.readNames(map, KEY_BONES);

        return new FormChain(
            ModifierIO.isEnabled(map),
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

        ModifierIO.putEnabled(map, chain.enabled());
        ModifierIO.putNames(map, KEY_BONES, chain.bones());
        ModifierIO.putFloat(map, KEY_STIFFNESS, chain.stiffness(), FormChain.DEFAULT_STIFFNESS);
        ModifierIO.putFloat(map, KEY_DAMPING, chain.damping(), FormChain.DEFAULT_DAMPING);
        ModifierIO.putFloat(map, KEY_GRAVITY, chain.gravity(), FormChain.DEFAULT_GRAVITY);
        ModifierIO.putFloat(map, KEY_MASS, chain.mass(), FormChain.DEFAULT_MASS);
        ModifierIO.putFlag(map, KEY_SELF_COLLISION, chain.selfCollision());

        return map;
    }

    /** Whether stored data says the modifier is on, without parsing the rest — the per-frame check. */
    public static boolean isEnabled(BaseType data)
    {
        return ModifierIO.isEnabled(data);
    }
}
