package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.RigBone;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bone tree of a model form, whichever kind of model it is — the cubic groups or the BOBJ
 * armature — for the rigs that simulate bones and have to know who hangs off whom.
 *
 * <p>Asked through BBS's {@link RigBone}, which both skeletons implement, rather than through the
 * models' own tree walks: those disagree between the two kinds and between BBS versions (a BOBJ
 * model has answered "children" with the bone's siblings, and "no parent" with an empty name in one
 * version and null in the next), while a bone's parent is the one thing every skeleton agrees on.</p>
 */
public final class ModelBones
{
    private ModelBones()
    {}

    /** The model drawn for the form, or null while it has not loaded. */
    public static IModel of(ModelForm form)
    {
        ModelInstance instance = form == null ? null : ModelFormRenderer.getModel(form);

        return instance == null ? null : instance.model;
    }

    /**
     * Whether the simulated pose can be handed back into this model's bones: the two skeletons
     * whose bones carry the constraint stage's {@code orient}/{@code offset}.
     */
    public static boolean isPosable(IModel model)
    {
        return model instanceof Model || model instanceof BOBJModel;
    }

    /**
     * Whether a bone's matrix in the matrix cache carries the cubic half turn on its right
     * ({@code group × T(pivot) × Ry(π)}, §10.1). A BOBJ bone's is its skeleton frame as it is, so a
     * pose read off a body following it needs no turn taken off.
     */
    public static boolean flipsBoneFrames(IModel model)
    {
        return model instanceof Model;
    }

    public static boolean has(IModel model, String bone)
    {
        return model != null && bone != null && model.getBone(bone) != null;
    }

    /** The bone's parent's name, or null at the root and for a bone the model does not have. */
    public static String parent(IModel model, String bone)
    {
        RigBone rigBone = model == null || bone == null ? null : model.getBone(bone);
        RigBone parent = rigBone == null ? null : rigBone.getParentBone();

        return parent == null ? null : parent.getBoneName();
    }

    /** Whether {@code bone} sits somewhere under {@code ancestor} in the model's bone tree. */
    public static boolean isDescendant(IModel model, String bone, String ancestor)
    {
        String parent = parent(model, bone);

        while (parent != null)
        {
            if (parent.equals(ancestor))
            {
                return true;
            }

            parent = parent(model, parent);
        }

        return false;
    }

    /**
     * The bone's direct children, in the model's own order: a cubic group's children as listed, a
     * BOBJ armature's bones by index.
     */
    public static List<String> children(IModel model, String bone)
    {
        if (model instanceof Model cubic)
        {
            ModelGroup group = cubic.getGroup(bone);

            if (group == null)
            {
                return Collections.emptyList();
            }

            List<String> children = new ArrayList<>(group.children.size());

            for (ModelGroup child : group.children)
            {
                children.add(child.id);
            }

            return children;
        }

        if (model == null || bone == null)
        {
            return Collections.emptyList();
        }

        List<String> children = new ArrayList<>();

        for (RigBone candidate : model.getRigBones())
        {
            RigBone parent = candidate == null ? null : candidate.getParentBone();

            if (parent != null && bone.equals(parent.getBoneName()))
            {
                children.add(candidate.getBoneName());
            }
        }

        return children;
    }
}
