package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_physics.forms.PhysicsBodyForm;

/** The physics body's settings: how much the animation owns it, how big it is and how it behaves. */
public class UIPhysicsBodyFormPanel extends UIFormPanel<PhysicsBodyForm>
{
    public UITrackpad authority;
    public UITrackpad sizeX;
    public UITrackpad sizeY;
    public UITrackpad sizeZ;
    public UITrackpad mass;
    public UITrackpad friction;
    public UITrackpad restitution;

    public UIPhysicsBodyFormPanel(UIForm editor)
    {
        super(editor);

        this.authority = new UITrackpad((v) -> this.form.authority.set(v.floatValue()));
        this.authority.limit(0D, 1D).increment(0.1D);

        this.sizeX = new UITrackpad((v) -> this.form.sizeX.set(v.floatValue()));
        this.sizeX.limit(0.05D, 64D);
        this.sizeY = new UITrackpad((v) -> this.form.sizeY.set(v.floatValue()));
        this.sizeY.limit(0.05D, 64D);
        this.sizeZ = new UITrackpad((v) -> this.form.sizeZ.set(v.floatValue()));
        this.sizeZ.limit(0.05D, 64D);

        this.mass = new UITrackpad((v) -> this.form.mass.set(v.floatValue()));
        this.mass.limit(0.01D, 10000D);

        this.friction = new UITrackpad((v) -> this.form.friction.set(v.floatValue()));
        this.friction.limit(0D, 1D).increment(0.1D);

        this.restitution = new UITrackpad((v) -> this.form.restitution.set(v.floatValue()));
        this.restitution.limit(0D, 1D).increment(0.1D);

        this.options.add(
            UI.labelRow(PhysicsKeys.AUTHORITY, this.authority),
            UI.label(PhysicsKeys.SIZE),
            UI.row(this.sizeX, this.sizeY, this.sizeZ),
            UI.labelRow(PhysicsKeys.MASS, this.mass),
            UI.labelRow(PhysicsKeys.FRICTION, this.friction),
            UI.labelRow(PhysicsKeys.RESTITUTION, this.restitution)
        );
    }

    @Override
    public void startEdit(PhysicsBodyForm form)
    {
        super.startEdit(form);

        this.authority.setValue(form.authority.get());
        this.sizeX.setValue(form.sizeX.get());
        this.sizeY.setValue(form.sizeY.get());
        this.sizeZ.setValue(form.sizeZ.get());
        this.mass.setValue(form.mass.get());
        this.friction.setValue(form.friction.get());
        this.restitution.setValue(form.restitution.get());
    }
}
