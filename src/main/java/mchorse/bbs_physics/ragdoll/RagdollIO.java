package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ragdoll setup on disk: {@code {"enabled": true, "joints": {bone: {...}}}}.
 *
 * <p>Joints that are exactly the default are not written, so a model whose author only flipped the
 * switch stores one flag and nothing else — and a form the ragdoll was never enabled on stores
 * nothing at all, staying byte-identical to one the addon never saw.</p>
 */
public final class RagdollIO
{
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_JOINTS = "joints";

    private static final String KEY_KIND = "kind";
    private static final String KEY_SWING = "swing";
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

        return new FormRagdoll(map.getBool(KEY_ENABLED), joints);
    }

    public static MapType toData(FormRagdoll ragdoll)
    {
        MapType map = new MapType();

        if (ragdoll == null || ragdoll.isEmpty())
        {
            return map;
        }

        if (ragdoll.enabled())
        {
            map.putBool(KEY_ENABLED, true);
        }

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
        return data instanceof MapType map && map.getBool(KEY_ENABLED);
    }

    private static RagdollJoint jointFromData(MapType map)
    {
        RagdollJoint fallback = RagdollJoint.DEFAULT;

        return new RagdollJoint(
            RagdollJointKind.byId(map.getString(KEY_KIND), fallback.kind()),
            map.getFloat(KEY_SWING, fallback.swing()),
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
