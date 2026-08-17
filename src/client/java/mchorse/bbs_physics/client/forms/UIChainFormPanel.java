package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.forms.UINestedEdit;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_physics.chain.ChainForm;
import mchorse.bbs_physics.forms.PhysicsForms;

/**
 * The chain form's own tab: what the strand is, what it is like, and where its ends are held —
 * the cloth panel's arrangement, because the forms are siblings. The bottom end's anchor is not
 * here: it is a keyframable track on the timeline, edited where every other anchor is.
 */
public class UIChainFormPanel extends UIFormPanel<ChainForm>
{
    public UINestedEdit link;

    public UITrackpad length;
    public UITrackpad segments;
    public UITrackpad radius;

    public UITrackpad mass;
    public UITrackpad stiffness;
    public UITrackpad damping;
    public UITrackpad friction;
    public UITrackpad gravity;

    public UIToggle heldStart;
    public final UITrackpad authority = PhysicsFields.authority((value) -> PhysicsForms.setAuthority(this.form, value));

    public UIChainFormPanel(UIForm editor)
    {
        super(editor);

        this.link = new UINestedEdit((editing) ->
        {
            UIFormPalette.open(this, editing, this.form.link.get(), (form) ->
            {
                this.form.link.set(form == null ? null : FormUtils.copy(form));
                this.link.setForm(form);
            });
        });
        this.link.tooltip(PhysicsKeys.CHAIN_LINK);

        this.length = new UITrackpad((value) -> this.form.length.set(value.floatValue()));
        this.length.limit(0.2D, 32D).tooltip(PhysicsKeys.CHAIN_LENGTH);
        this.segments = new UITrackpad((value) -> this.form.segments.set(value.intValue()));
        this.segments.limit(1D, 64D).integer().tooltip(PhysicsKeys.CHAIN_SEGMENTS);
        this.radius = new UITrackpad((value) -> this.form.radius.set(value.floatValue()));
        this.radius.limit(0.01D, 0.5D).tooltip(PhysicsKeys.CHAIN_RADIUS);

        this.mass = new UITrackpad((value) -> this.form.mass.set(value.floatValue()));
        this.mass.limit(0.01D, 1000D).tooltip(PhysicsKeys.CHAIN_MASS);
        this.stiffness = new UITrackpad((value) -> this.form.stiffness.set(value.floatValue()));
        this.stiffness.limit(0D, 1D).tooltip(PhysicsKeys.CHAIN_STIFFNESS);
        this.damping = new UITrackpad((value) -> this.form.damping.set(value.floatValue()));
        this.damping.limit(0D, 1D).tooltip(PhysicsKeys.CHAIN_DAMPING);
        this.friction = new UITrackpad((value) -> this.form.friction.set(value.floatValue()));
        this.friction.limit(0D, 1D).tooltip(PhysicsKeys.FRICTION);
        this.gravity = new UITrackpad((value) -> this.form.gravity.set(value.floatValue()));
        this.gravity.limit(-2D, 2D).tooltip(PhysicsKeys.BALLOON_GRAVITY);

        this.heldStart = new UIToggle(PhysicsKeys.CHAIN_HELD_START, false, (b) -> this.form.heldStart.set(b.getValue()));
        this.heldStart.tooltip(PhysicsKeys.CHAIN_HELD_START_TOOLTIP);


        this.options.add(UI.label(PhysicsKeys.CHAIN_LINK_LABEL), this.link);
        this.options.add(UI.label(PhysicsKeys.CHAIN_STRAND).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.length, UI.row(this.segments, this.radius));
        this.options.add(UI.label(PhysicsKeys.CHAIN_FEEL).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.mass, this.stiffness, this.damping);
        this.options.add(UI.row(this.friction, this.gravity));
        this.options.add(UI.label(PhysicsKeys.CHAIN_ENDS).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.heldStart);
        this.options.add(UI.label(PhysicsKeys.CHAIN_ATTACH_HINT).marginTop(UIConstants.MARGIN));
        this.options.add(UI.label(PhysicsKeys.AUTHORITY).marginTop(UIConstants.SECTION_GAP), this.authority);
    }

    @Override
    public void startEdit(ChainForm form)
    {
        super.startEdit(form);

        this.link.setForm(form.link.get());
        this.length.setValue(form.length.get());
        this.segments.setValue(form.segments.get());
        this.radius.setValue(form.radius.get());

        this.mass.setValue(form.mass.get());
        this.stiffness.setValue(form.stiffness.get());
        this.damping.setValue(form.damping.get());
        this.friction.setValue(form.friction.get());
        this.gravity.setValue(form.gravity.get());

        this.heldStart.setValue(form.heldStart.get());
        this.authority.setValue(PhysicsForms.getAuthority(form));
    }
}
