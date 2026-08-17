package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.balloon.BalloonForm;

/** The form editor's entry for an inflated ball. */
public class UIBalloonForm extends UIForm<BalloonForm>
{
    public UIBalloonForm()
    {
        super();

        this.defaultPanel = new UIBalloonFormPanel(this);

        this.registerPanel(this.defaultPanel, PhysicsKeys.BALLOON_TITLE, Icons.SPHERE);
        this.registerDefaultPanels();
    }
}
