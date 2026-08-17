package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.util.function.Function;

/**
 * The form editor for a form that <em>is</em> its physics — cloth, the balloon, a rope.
 *
 * <p>One class for all of them, because the editor itself never differed: a single tab holding the
 * form's own panel, then BBS's usual ones. What differs is the panel, the tab's name and its icon,
 * which is exactly what a constructor is for.</p>
 *
 * <p>It also gives the shared Physics and Collision tabs something to recognize (see
 * {@code UIFormMixin}): those two tabs describe adding physics to a form that did not have any, and
 * a soft form has no modifier to add and no collision shape to describe. Asking "is this editor a
 * soft form's" beats listing the three classes by name, which is a list that would have to be found
 * and extended every time another one is added.</p>
 */
public class UISoftForm<T extends Form> extends UIForm<T>
{
    public UISoftForm(Function<UIForm<T>, UIFormPanel<T>> panel, IKey title, Icon icon)
    {
        super();

        this.defaultPanel = panel.apply(this);

        this.registerPanel(this.defaultPanel, title, icon);
        this.registerDefaultPanels();
    }
}
