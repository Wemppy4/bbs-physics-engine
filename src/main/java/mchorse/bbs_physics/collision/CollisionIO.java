package mchorse.bbs_physics.collision;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collision markup on disk. The stored shape is {@code {slot: {"mode": "...", "shapes": [...]}}},
 * with the form's own slot under the empty key and a model's bones under their names.
 *
 * <p>Slots that say nothing are not written, so an untouched form carries no markup at all rather
 * than a map of "none" for every bone it has. That keeps a film the addon has merely been present
 * for byte-identical to one it has not.</p>
 */
public final class CollisionIO
{
    private static final String KEY_MODE = "mode";
    private static final String KEY_SHAPES = "shapes";

    private static final String KEY_KIND = "kind";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_ROTATION = "rotation";
    private static final String KEY_SIZE = "size";

    private CollisionIO()
    {}

    public static FormCollision fromData(BaseType data)
    {
        if (!(data instanceof MapType map) || map.isEmpty())
        {
            return FormCollision.EMPTY;
        }

        Map<String, CollisionSlot> slots = new LinkedHashMap<>();

        for (String key : new ArrayList<>(map.keys()))
        {
            if (!map.has(key, BaseType.TYPE_MAP))
            {
                continue;
            }

            CollisionSlot slot = slotFromData(map.getMap(key));

            if (!slot.isEmpty())
            {
                slots.put(key, slot);
            }
        }

        return new FormCollision(slots);
    }

    public static MapType toData(FormCollision collision)
    {
        MapType map = new MapType();

        if (collision == null)
        {
            return map;
        }

        for (Map.Entry<String, CollisionSlot> entry : collision.slots().entrySet())
        {
            CollisionSlot slot = entry.getValue();

            if (slot != null && !slot.isEmpty())
            {
                map.put(entry.getKey(), slotToData(slot));
            }
        }

        return map;
    }

    private static CollisionSlot slotFromData(MapType map)
    {
        CollisionMode mode = CollisionMode.byId(map.getString(KEY_MODE), CollisionMode.NONE);
        List<CollisionShape> shapes = new ArrayList<>();

        if (map.has(KEY_SHAPES, BaseType.TYPE_LIST))
        {
            ListType list = map.getList(KEY_SHAPES);

            for (int i = 0; i < list.size(); i++)
            {
                if (list.has(i, BaseType.TYPE_MAP))
                {
                    shapes.add(shapeFromData(list.getMap(i)));
                }
            }
        }

        return new CollisionSlot(mode, shapes);
    }

    private static MapType slotToData(CollisionSlot slot)
    {
        MapType map = new MapType();

        map.putString(KEY_MODE, slot.mode().id);

        if (slot.mode() == CollisionMode.SHAPES)
        {
            ListType list = new ListType();

            for (CollisionShape shape : slot.shapes())
            {
                list.add(shapeToData(shape));
            }

            map.put(KEY_SHAPES, list);
        }

        return map;
    }

    private static CollisionShape shapeFromData(MapType map)
    {
        return new CollisionShape(
            CollisionKind.byId(map.getString(KEY_KIND), CollisionKind.BOX),
            vector(map, KEY_OFFSET, 0F),
            vector(map, KEY_ROTATION, 0F),
            vector(map, KEY_SIZE, 0.5F));
    }

    private static MapType shapeToData(CollisionShape shape)
    {
        MapType map = new MapType();

        map.putString(KEY_KIND, shape.kind().id);
        map.put(KEY_OFFSET, DataStorageUtils.vector3fToData(shape.offset()));
        map.put(KEY_ROTATION, DataStorageUtils.vector3fToData(shape.rotation()));
        map.put(KEY_SIZE, DataStorageUtils.vector3fToData(shape.size()));

        return map;
    }

    private static Vector3f vector(MapType map, String key, float fallback)
    {
        return DataStorageUtils.vector3fFromData(map.getList(key), new Vector3f(fallback));
    }
}
