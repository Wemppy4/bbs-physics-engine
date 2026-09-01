package wemppy.bbs_physics.ragdoll;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import wemppy.bbs_physics.forms.ModifierIO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ragdoll setup on disk: {@code {"enabled": true, "excluded": [bone], "joints": {bone: {...}}}}.
 *
 * <p>Joints that are exactly the default are not written, and neither is an empty exclusion list, so
 * a model whose author only flipped the switch stores one flag and nothing else — and a form the
 * ragdoll was never enabled on stores nothing at all, staying byte-identical to one the addon never
 * saw.</p>
 */
public final class RagdollIO
{
    private static final String KEY_EXCLUDED = "excluded";
    private static final String KEY_JOINTS = "joints";
    private static final String KEY_MASS = "mass";
    private static final String KEY_DAMPING = "damping";
    private static final String KEY_FRICTION = "friction";
    private static final String KEY_GRAVITY = "gravity";
    private static final String KEY_NO_SELF_COLLIDE = "no_self_collide";
    private static final String KEY_MUSCLES = "muscles";
    private static final String KEY_MUSCLE_DAMPING = "muscle_damping";

    private static final String KEY_KIND = "kind";
    private static final String KEY_SWING = "swing";
    private static final String KEY_SWING_PLANE = "swing_plane";
    private static final String KEY_TWIST_MIN = "twist_min";
    private static final String KEY_TWIST_MAX = "twist_max";
    private static final String KEY_HINGE_AXIS = "hinge_axis";
    private static final String KEY_HINGE_MIN = "hinge_min";
    private static final String KEY_HINGE_MAX = "hinge_max";
    private static final String KEY_ATTACH_TO = "attach_to";

    private RagdollIO()
    {}

    public static FormRagdoll fromData(BaseType data)
    {
        if (!(data instanceof MapType map) || map.isEmpty())
        {
            return FormRagdoll.EMPTY;
        }

        Map<String, RagdollJoint> joints = new LinkedHashMap<>();

        if (map.has(KEY_JOINTS, BaseType.TYPE_MAP))
        {
            MapType list = map.getMap(KEY_JOINTS);

            for (String bone : new ArrayList<>(list.keys()))
            {
                if (list.has(bone, BaseType.TYPE_MAP))
                {
                    joints.put(bone, jointFromData(list.getMap(bone)));
                }
            }
        }

        Set<String> excluded = ModifierIO.readNames(map, KEY_EXCLUDED);

        return new FormRagdoll(ModifierIO.isEnabled(map), excluded, joints,
            map.getFloat(KEY_MASS, FormRagdoll.DEFAULT_MASS),
            map.getFloat(KEY_DAMPING, FormRagdoll.DEFAULT_DAMPING),
            map.getFloat(KEY_FRICTION, FormRagdoll.DEFAULT_FRICTION),
            map.getFloat(KEY_GRAVITY, FormRagdoll.DEFAULT_GRAVITY),
            !map.getBool(KEY_NO_SELF_COLLIDE),
            map.getFloat(KEY_MUSCLES, FormRagdoll.DEFAULT_MUSCLES),
            map.getFloat(KEY_MUSCLE_DAMPING, FormRagdoll.DEFAULT_MUSCLE_DAMPING));
    }

    private static final String[] KNOB_KEYS = {KEY_MASS, KEY_DAMPING, KEY_FRICTION, KEY_GRAVITY, KEY_MUSCLES, KEY_MUSCLE_DAMPING};

    /** Whole, numbers included — what a preset or the clipboard carries. */
    public static MapType toData(FormRagdoll ragdoll)
    {
        return toData(ragdoll, true);
    }

    /** Whether stored data still carries numbers from before they became form values. */
    public static boolean hasKnobs(BaseType data)
    {
        return ModifierIO.hasAny(data, KNOB_KEYS);
    }

    /** @param knobs whether the keyframable numbers go in — see {@code BodyIO.toData} */
    public static MapType toData(FormRagdoll ragdoll, boolean knobs)
    {
        MapType map = new MapType();

        if (ragdoll == null || ragdoll.isEmpty())
        {
            return map;
        }

        ModifierIO.putEnabled(map, ragdoll.enabled());
        ModifierIO.putNames(map, KEY_EXCLUDED, ragdoll.excluded());
        if (knobs)
        {
            ModifierIO.putFloat(map, KEY_MASS, ragdoll.mass(), FormRagdoll.DEFAULT_MASS);
            ModifierIO.putFloat(map, KEY_DAMPING, ragdoll.damping(), FormRagdoll.DEFAULT_DAMPING);
            ModifierIO.putFloat(map, KEY_FRICTION, ragdoll.friction(), FormRagdoll.DEFAULT_FRICTION);
            ModifierIO.putFloat(map, KEY_GRAVITY, ragdoll.gravity(), FormRagdoll.DEFAULT_GRAVITY);
            ModifierIO.putFloat(map, KEY_MUSCLES, ragdoll.muscles(), FormRagdoll.DEFAULT_MUSCLES);
            ModifierIO.putFloat(map, KEY_MUSCLE_DAMPING, ragdoll.muscleDamping(), FormRagdoll.DEFAULT_MUSCLE_DAMPING);
        }

        ModifierIO.putFlag(map, KEY_NO_SELF_COLLIDE, !ragdoll.selfCollide());

        MapType joints = new MapType();

        for (Map.Entry<String, RagdollJoint> entry : ragdoll.joints().entrySet())
        {
            if (!entry.getValue().isDefault())
            {
                joints.put(entry.getKey(), jointToData(entry.getValue()));
            }
        }

        if (!joints.isEmpty())
        {
            map.put(KEY_JOINTS, joints);
        }

        return map;
    }

    /** Whether stored data says the ragdoll is on, without parsing the rest — the cheap check. */
    public static boolean isEnabled(BaseType data)
    {
        return ModifierIO.isEnabled(data);
    }

    private static RagdollJoint jointFromData(MapType map)
    {
        RagdollJoint fallback = RagdollJoint.DEFAULT;

        return new RagdollJoint(
            RagdollJointKind.byId(map.getString(KEY_KIND), fallback.kind()),
            map.getFloat(KEY_SWING, fallback.swing()),
            /* Absent in files from before the second axis existed: the cone was round then. */
            map.getFloat(KEY_SWING_PLANE, map.getFloat(KEY_SWING, fallback.swing())),
            map.getFloat(KEY_TWIST_MIN, fallback.twistMin()),
            map.getFloat(KEY_TWIST_MAX, fallback.twistMax()),
            map.getInt(KEY_HINGE_AXIS, fallback.hingeAxis()),
            map.getFloat(KEY_HINGE_MIN, fallback.hingeMin()),
            map.getFloat(KEY_HINGE_MAX, fallback.hingeMax()),
            map.getString(KEY_ATTACH_TO, fallback.attachTo()));
    }

    private static MapType jointToData(RagdollJoint joint)
    {
        MapType map = new MapType();

        map.putString(KEY_KIND, joint.kind().id);
        map.putFloat(KEY_SWING, joint.swing());
        map.putFloat(KEY_SWING_PLANE, joint.swingPlane());
        map.putFloat(KEY_TWIST_MIN, joint.twistMin());
        map.putFloat(KEY_TWIST_MAX, joint.twistMax());
        map.putInt(KEY_HINGE_AXIS, joint.hingeAxis());
        map.putFloat(KEY_HINGE_MIN, joint.hingeMin());
        map.putFloat(KEY_HINGE_MAX, joint.hingeMax());

        if (!joint.attachTo().isEmpty())
        {
            map.putString(KEY_ATTACH_TO, joint.attachTo());
        }

        return map;
    }
}
