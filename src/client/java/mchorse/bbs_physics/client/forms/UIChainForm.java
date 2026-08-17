package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.chain.ChainForm;

/** The form editor's entry for a chain. */
public class UIChainForm extends UIForm<ChainForm>
{
    public UIChainForm()
    {
        super();

        this.defaultPanel = new UIChainFormPanel(this);

        this.registerPanel(this.defaultPanel, PhysicsKeys.CHAIN_TITLE, Icons.CURVES);
        this.registerDefaultPanels();
    }
}
