package wemppy.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.physics.SpringChainDef;
import mchorse.bbs_mod.cubic.physics.SpringChainSerializer;
import mchorse.bbs_mod.cubic.physics.SpringChainsConfig;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which bones of a model are already driven by BBS's own chain physics — hair, tails, capes, belts.
 *
 * <p>These must never be marked up as collision (§5.2). A bone the chain solver owns and the Jolt
 * side also owns has <b>two masters</b>: the kinematic body drags it along the animation while the
 * solver swings it somewhere else, and the strand ends up doing neither convincingly.</p>
 *
 * <p>Until Р8.4 that was the author's problem — the reason the markup defaulted to empty. Now the
 * automatic pass runs by itself the moment physics is added, so the exclusion has to be automatic
 * too, and it can be: BBS stores its chains as {@code start bone → end bone}, which is exactly the
 * list of bones to leave alone. Walking up the tree from each end to its start gives every bone in
 * between; a chain whose end is missing or unrelated contributes just its start, which is the safe
 * way to be wrong.</p>
 */
public final class ChainBones
{
    private ChainBones()
    {}

    public static Set<String> of(Form form, Model model)
    {
        if (!(form instanceof ModelForm modelForm) || model == null)
        {
            return Collections.emptySet();
        }

        /* The same chains under CML's name for them: spring chains, kept on the form as
         * `springs` rather than `physics`. */
        BaseType data = modelForm.springs.get();

        if (!(data instanceof MapType map) || map.isEmpty())
        {
            return Collections.emptySet();
        }

        SpringChainsConfig config = SpringChainSerializer.fromData(map);
        Set<String> bones = new HashSet<>();

        for (Map.Entry<String, SpringChainDef> entry : config.chains().entrySet())
        {
            String start = entry.getKey();

            bones.add(start);

            collectChain(model, start, entry.getValue().endBone(), bones);
        }

        return bones;
    }

    /**
     * Everything between a chain's start and its end, walked from the end upwards — the direction
     * that works, since a bone knows its parent and a start does not know which of its children the
     * chain went down.
     */
    private static void collectChain(Model model, String start, String end, Set<String> bones)
    {
        if (end == null || end.isEmpty())
        {
            return;
        }

        ModelGroup group = model.getGroup(end);

        while (group != null)
        {
            bones.add(group.id);

            if (group.id.equals(start))
            {
                return;
            }

            group = group.parent;
        }
    }
}
