package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * The list of a model's bones the physics panels are built on.
 *
 * <p>BBS has a bone <em>tree</em> here — indented by hierarchy, with the marked-up bones coloured.
 * CML has no such widget, and rebuilding one against its list would be five hundred lines of
 * borrowed behaviour, so this is the flat form of the same thing: every bone of the model, one per
 * row, in the order the tree would have walked them. Hierarchy is therefore readable from the
 * order but not from indentation, which is the whole of what is lost.</p>
 *
 * <p>What is kept is what the markup actually needs: multi-select, so a rig described in groups can
 * be marked up in groups, and the search box, so a named bone is one word away in a skeleton of a
 * hundred.</p>
 */
public class PhysicsBoneList extends UIStringList
{
    public PhysicsBoneList(Consumer<List<String>> callback)
    {
        super(callback);
    }

    /**
     * Fill with the model's bones, leaving out the hidden ones.
     *
     * @param hidden bones not to offer at all, or null for none
     */
    public void fillBones(IModel model, Collection<String> hidden)
    {
        this.clear();

        if (model != null)
        {
            for (String bone : model.getGroupKeysInHierarchyOrder())
            {
                if (hidden == null || !hidden.contains(bone))
                {
                    this.add(bone);
                }
            }
        }

        this.update();
    }

    /** Fill with names that do not come from a model — a mob form's parts, say. */
    public void fillFlat(Collection<String> bones)
    {
        this.clear();

        if (bones != null)
        {
            this.add(bones);
        }

        this.update();
    }

    /**
     * Add one more row to the selection, leaving what is already selected alone.
     *
     * <p>CML's list selects by replacing, or extends only through its own click handling; the
     * multi-select gestures here need to say "and this one too" outright.</p>
     */
    public void addIndex(int index)
    {
        if (!this.exists(index) || this.current.contains(index))
        {
            return;
        }

        this.current.add(index);

        if (this.callback != null)
        {
            this.callback.accept(this.getCurrent());
        }
    }

    /** The row under the cursor, or −1. CML spells this {@code getHoveredIndex}. */
    public int getIndexAtCursor(UIContext context)
    {
        return this.getHoveredIndex(context);
    }
}
