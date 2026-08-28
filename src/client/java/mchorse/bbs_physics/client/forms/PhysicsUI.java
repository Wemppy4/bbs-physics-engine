package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

/**
 * The one row shape every settings panel here is built out of: a name on the left, the thing that
 * edits it on the right.
 *
 * <p>BBS has this as {@code UI.labelRow}; CML's copy of the interface toolkit does not, so it is
 * spelled out here from the primitives both of them do have. The layout is the same — a row of the
 * standard control height, the label pinned to the left and vertically centred, the control given a
 * fixed width so that a column of these rows lines its controls up.</p>
 *
 * <p>One thing is lost against BBS's version: there the label takes the leftover width through a
 * flex weight, so a long name wraps into the space it needs instead of running under the control.
 * CML's element has no such weight, so the name simply gets whatever the control leaves.</p>
 */
public final class PhysicsUI
{
    /** How wide the editing half of a row is. The same 90 pixels BBS lays its own rows out on. */
    public static final int VALUE_WIDTH = 90;

    private PhysicsUI()
    {}

    public static UIElement labelRow(IKey label, UIElement element)
    {
        return labelRow(label, VALUE_WIDTH, element);
    }

    public static UIElement labelRow(IKey label, int controlWidth, UIElement element)
    {
        return UI.row(UIConstants.MARGIN, 0, UIConstants.CONTROL_HEIGHT,
            UI.label(label, UIConstants.CONTROL_HEIGHT).labelAnchor(0F, 0.5F),
            element.w(controlWidth));
    }

    /**
     * The same row with something other than a name on the left — a toggle that names itself, say.
     * The control keeps its width, so such a row still lines up with the plain ones around it.
     */
    public static UIElement labelRow(UIElement label, UIElement element)
    {
        return UI.row(UIConstants.MARGIN, 0, UIConstants.CONTROL_HEIGHT, label, element.w(VALUE_WIDTH));
    }
}
