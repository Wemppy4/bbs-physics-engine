package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.cloth.ClothForm;

/** The form editor's entry for a sheet of cloth. */
public class UIClothForm extends UIForm<ClothForm>
{
    public UIClothForm()
    {
        super();

        this.defaultPanel = new UIClothFormPanel(this);

        this.registerPanel(this.defaultPanel, PhysicsKeys.CLOTH_TITLE, Icons.MATERIAL);
        this.registerDefaultPanels();
    }
}
