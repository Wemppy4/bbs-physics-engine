package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The reading and writing every modifier does the same way.
 *
 * <p>The bargain all of them keep is worth stating once here: <b>only what differs from the default
 * is written, and a form nobody has touched writes nothing at all</b>. That is what keeps a film
 * saved with the addon loaded byte-identical to one saved without it — which matters more than it
 * sounds, because BBS drops keys it does not recognize when it re-saves, so every byte the addon
 * writes is a byte a plain BBS would quietly throw away.</p>
 */
public final class ModifierIO
{
    /** The flag every modifier stores under the same name: whether the author added it at all. */
    public static final String KEY_ENABLED = "enabled";

    private ModifierIO()
    {}

    /**
     * Whether stored data says a modifier is switched on, without parsing the rest of it.
     *
     * <p>The per-frame question, asked of every form of every actor while a scene is assembled and
     * whenever one is looked for — hence not going through the full parse.</p>
     */
    public static boolean isEnabled(BaseType data)
    {
        return data instanceof MapType map && map.getBool(KEY_ENABLED);
    }

    /** Writes the flag, and only when it is on. */
    public static void putEnabled(MapType map, boolean enabled)
    {
        if (enabled)
        {
            map.putBool(KEY_ENABLED, true);
        }
    }

    /** Writes a number, and only when the author moved it off the default. */
    /** Whether stored data has any of {@code keys} — how a blob from before the knobs moved out is told. */
    public static boolean hasAny(BaseType data, String[] keys)
    {
        if (!(data instanceof MapType map))
        {
            return false;
        }

        for (String key : keys)
        {
            if (map.has(key))
            {
                return true;
            }
        }

        return false;
    }

    public static void putFloat(MapType map, String key, float value, float fallback)
    {
        if (value != fallback)
        {
            map.putFloat(key, value);
        }
    }

    /** Writes a flag, and only when it is on. */
    public static void putFlag(MapType map, String key, boolean value)
    {
        if (value)
        {
            map.putBool(key, true);
        }
    }

    /**
     * Reads a list of bone names, in the order they were written — insertion-ordered because these
     * are lists an author built up by ticking, and a set that reordered them would reshuffle the
     * panel under them.
     */
    public static Set<String> readNames(MapType map, String key)
    {
        Set<String> names = new LinkedHashSet<>();

        if (!map.has(key, BaseType.TYPE_LIST))
        {
            return names;
        }

        ListType list = map.getList(key);

        for (int i = 0; i < list.size(); i++)
        {
            String name = list.getString(i);

            if (!name.isEmpty())
            {
                names.add(name);
            }
        }

        return names;
    }

    /** Writes a list of bone names, and only when there are any. */
    public static void putNames(MapType map, String key, Collection<String> names)
    {
        if (names.isEmpty())
        {
            return;
        }

        ListType list = new ListType();

        for (String name : names)
        {
            list.addString(name);
        }

        map.put(key, list);
    }
}
