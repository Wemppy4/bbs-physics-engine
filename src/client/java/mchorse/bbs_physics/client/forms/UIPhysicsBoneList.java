package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The bone list both physics screens are built on: BBS's bone tree, multi-select, with one
 * correction of its own.
 *
 * <p>Multi-select is the whole point of it — a rig is described in groups, and the gestures are the
 * ones BBS's pose bone list already taught (Shift for a run, Ctrl for one more), so nothing new has
 * to be learned to mark up ten fingers at once.</p>
 *
 * <p><b>The correction: a Shift run means the rows on screen.</b> {@link mchorse.bbs_mod.ui.framework.elements.input.list.UIList}
 * walks its backing list between the two ends of the run, which is exact until the search box
 * narrows what is drawn — then the run quietly swallows every bone that happens to lie between the
 * two clicks in the full skeleton, on screen or not. In a list that only selects, that is a
 * cosmetic surprise; here the selection is what edits are <em>written</em> into, so it would be a
 * mode switch silently rewriting bones the author never saw. The run is therefore walked over the
 * visible rows instead.</p>
 */
public class UIPhysicsBoneList extends UIBoneTreeList
{
    public UIPhysicsBoneList(Consumer<List<String>> callback)
    {
        super(callback);

        this.multi();
    }

    @Override
    protected void applySelectionOnClick(int index)
    {
        int anchor = this.current.isEmpty() ? -1 : this.current.get(0);

        if (!this.isFiltering() || !this.multi || !Window.isShiftPressed() || !this.exists(anchor))
        {
            super.applySelectionOnClick(index);

            return;
        }

        List<String> visible = this.visible();
        int to = visible.indexOf(this.getList().get(index));
        int from = visible.indexOf(this.getList().get(anchor));

        /* The run starts where the previous selection does, and that bone can itself be hidden by
         * the search — there is no run to draw between a row on screen and one that is not, so the
         * click just adds the row it landed on. */
        if (from < 0 || to < 0)
        {
            this.addIndex(index);

            return;
        }

        int step = from > to ? -1 : 1;

        for (int i = from; i != to + step; i += step)
        {
            this.addIndex(this.getList().indexOf(visible.get(i)));
        }
    }

    /** The rows the search is currently letting through, in the order they are drawn. */
    private List<String> visible()
    {
        List<String> visible = new ArrayList<>();

        for (int i = 0; ; i++)
        {
            String element = this.getElementAt(i);

            if (element == null)
            {
                break;
            }

            visible.add(element);
        }

        return visible;
    }
}
