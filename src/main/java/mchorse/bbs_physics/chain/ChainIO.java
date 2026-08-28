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
    private static final String KEY_FALLOFF = "falloff";
    private static final String KEY_BEND = "bend";

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
            map.getBool(KEY_SELF_COLLISION),
            map.getFloat(KEY_FALLOFF, FormChain.DEFAULT_FALLOFF),
            map.getFloat(KEY_BEND, FormChain.DEFAULT_BEND));
    }

    private static final String[] KNOB_KEYS = {KEY_STIFFNESS, KEY_DAMPING, KEY_GRAVITY, KEY_MASS, KEY_FALLOFF, KEY_BEND};

    /** Whole, numbers included — what a preset or the clipboard carries. */
    public static MapType toData(FormChain chain)
    {
        return toData(chain, true);
    }

    /** Whether stored data still carries numbers from before they became form values. */
    public static boolean hasKnobs(BaseType data)
    {
        return ModifierIO.hasAny(data, KNOB_KEYS);
    }

    /** @param knobs whether the keyframable numbers go in — see {@code BodyIO.toData} */
    public static MapType toData(FormChain chain, boolean knobs)
    {
        MapType map = new MapType();

        if (chain == null || chain.isEmpty())
        {
            return map;
        }

        ModifierIO.putEnabled(map, chain.enabled());
        ModifierIO.putNames(map, KEY_BONES, chain.bones());
        if (knobs)
        {
            ModifierIO.putFloat(map, KEY_STIFFNESS, chain.stiffness(), FormChain.DEFAULT_STIFFNESS);
            ModifierIO.putFloat(map, KEY_DAMPING, chain.damping(), FormChain.DEFAULT_DAMPING);
            ModifierIO.putFloat(map, KEY_GRAVITY, chain.gravity(), FormChain.DEFAULT_GRAVITY);
            ModifierIO.putFloat(map, KEY_MASS, chain.mass(), FormChain.DEFAULT_MASS);
            ModifierIO.putFloat(map, KEY_FALLOFF, chain.falloff(), FormChain.DEFAULT_FALLOFF);
            ModifierIO.putFloat(map, KEY_BEND, chain.bend(), FormChain.DEFAULT_BEND);
        }

        ModifierIO.putFlag(map, KEY_SELF_COLLISION, chain.selfCollision());

        return map;
    }

    /** Whether stored data says the modifier is on, without parsing the rest — the per-frame check. */
    public static boolean isEnabled(BaseType data)
    {
        return ModifierIO.isEnabled(data);
    }
}
