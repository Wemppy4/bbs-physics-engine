package wemppy.bbs_physics.client.chain;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.chain.FormChain;
import wemppy.bbs_physics.chain.FormChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Silences BBS's own chain solver on the bones our chain modifier drives.
 *
 * <p><b>Why it has to exist at all.</b> A strand claimed here becomes a rigid body led by Jolt and
 * written back through {@code orient}/{@code offset}; BBS's solver runs a moment later in the same
 * constraint phase and would write the same bones from its own simulation. Two owners for one
 * strand is a strand doing neither thing — the exact case Р6 named when the collision markup was
 * told to leave chain bones alone.</p>
 *
 * <p><b>How.</b> Not by cancelling the solver — an author may well keep a skirt on the old physics
 * while the hair moves to ours, and cancelling wholesale would take both. Instead the config it
 * compiles from is handed over with the claimed chains removed: BBS then honestly has nothing to
 * say about those bones and everything else runs untouched. A chain is dropped when either end of
 * it is claimed, because a strand half-owned is the same fight as a strand wholly owned.</p>
 *
 * <p>The filtered map is cached per source map and rebuilt only when the claimed set changes:
 * {@code ModelPhysicsCache} keys its compiled chains by the map instance, so handing it a fresh
 * copy every frame would recompile every chain of every model every frame.</p>
 */
public final class ChainMute
{
    private static final String KEY_BONES = "bones";
    private static final String KEY_END = "end";

    /** Source map → what we last handed on, and the claim it was filtered for. */
    private static final Map<MapType, Filtered> CACHE = new WeakHashMap<>();

    private ChainMute()
    {}

    /**
     * The physics config BBS should compile for {@code form} — the original when nothing is
     * claimed, and a copy without the claimed chains when something is.
     */
    public static MapType filter(Form form, MapType data)
    {
        if (data == null || form == null)
        {
            return data;
        }

        FormChain chain = FormChains.get(form);

        if (!chain.enabled() || chain.bones().isEmpty() || !data.has(KEY_BONES, BaseType.TYPE_MAP))
        {
            return data;
        }

        Set<String> claimed = chain.bones();
        Filtered cached = CACHE.get(data);

        if (cached != null && cached.claimed.equals(claimed))
        {
            return cached.result;
        }

        MapType bones = data.getMap(KEY_BONES);
        List<String> drop = new ArrayList<>(0);

        for (String root : new ArrayList<>(bones.keys()))
        {
            if (!bones.has(root, BaseType.TYPE_MAP))
            {
                continue;
            }

            String end = bones.getMap(root).getString(KEY_END, "");

            if (claimed.contains(root) || (!end.isEmpty() && claimed.contains(end)))
            {
                drop.add(root);
            }
        }

        MapType result = data;

        if (!drop.isEmpty())
        {
            result = data.copy() instanceof MapType copy ? copy : data;

            if (result != data)
            {
                MapType copiedBones = result.getMap(KEY_BONES);

                for (String root : drop)
                {
                    copiedBones.remove(root);
                }
            }
        }

        CACHE.put(data, new Filtered(Set.copyOf(claimed), result));

        return result;
    }

    private record Filtered(Set<String> claimed, MapType result)
    {}
}
