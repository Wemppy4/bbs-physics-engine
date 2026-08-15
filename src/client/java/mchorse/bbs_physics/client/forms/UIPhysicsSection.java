package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.utils.UIConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * A block of physics controls living inside the Physics tab, rather than a tab of its own.
 *
 * <p>Collision markup and ragdoll joints used to be two tabs. That was the mistake Р7 named: they
 * are one screen written twice (the same bone list, the same search, the same preset menu), and
 * half the ragdoll tab's rows were greyed out explaining that the author had to go to the other tab
 * first. <b>A tab that teaches you to visit another tab is a sign there is one tab.</b> So the
 * controls became components, and the Physics tab shows whichever of them the form's modifiers call
 * for.</p>
 *
 * <p>What this replaces from {@code UIFormPanel} is small and named here: a column layout, the form
 * being edited, folding sections that remember their state, and the viewport's bone picking. The
 * options column, the drag handle and the tab plumbing belong to the panel that hosts these.</p>
 */
public abstract class UIPhysicsSection extends UIElement
{
    /**
     * Fold state per section id, for the session. The editor is rebuilt from scratch on things as
     * small as a viewport bone click, so a section built at its default every time would keep
     * re-folding under the author.
     */
    private static final Map<String, Boolean> FOLDS = new HashMap<>();

    protected Form form;

    public UIPhysicsSection()
    {
        this.column(UIConstants.MARGIN).vertical().stretch();
    }

    /** A collapsible section whose fold state outlives the editor being rebuilt. */
    protected UISection section(IKey title, String id, boolean defaultExpanded)
    {
        UISection section = new UISection(title);

        section.setExpanded(FOLDS.getOrDefault(id, defaultExpanded));
        section.onToggle((s) -> FOLDS.put(id, s.isExpanded()));

        return section;
    }

    /** The form being edited changed, or the editor was opened. */
    public void setForm(Form form)
    {
        this.form = form;
    }

    /**
     * Select {@code bone} in this component's own bone list, if it has one. Keeps a viewport click
     * on a body part from bouncing the author back to the pose editor.
     *
     * @return whether the bone was found and selected
     */
    public boolean pickBoneInList(String bone)
    {
        return false;
    }
}
