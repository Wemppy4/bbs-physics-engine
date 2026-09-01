package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.graphics.window.Window;

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
 * <p><b>One thing is missing against BBS's.</b> There a Shift run is walked over the rows on
 * screen, so that a run drawn while the search box is narrowing the list cannot swallow bones the
 * author never saw. CML's list has no hook to correct that through — the selection is decided
 * inside its click handling — so a Shift run here walks the whole skeleton between the two clicks,
 * search or no search. Worth knowing before shift-clicking through a filtered list.</p>
 */
public class UIPhysicsBoneList extends PhysicsBoneList
{
    public UIPhysicsBoneList(Consumer<List<String>> callback)
    {
        super(callback);

        this.multi();
    }

}
