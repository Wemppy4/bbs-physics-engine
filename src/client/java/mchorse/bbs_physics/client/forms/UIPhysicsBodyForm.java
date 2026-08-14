package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.forms.PhysicsBodyForm;

/** The form editor's entry for a physics body. */
public class UIPhysicsBodyForm extends UIForm<PhysicsBodyForm>
{
    public UIPhysicsBodyForm()
    {
        super();

        this.defaultPanel = new UIPhysicsBodyFormPanel(this);

        this.registerPanel(this.defaultPanel, PhysicsKeys.BODY_TITLE, Icons.PHYSICS);
        this.registerDefaultPanels();
    }
}
