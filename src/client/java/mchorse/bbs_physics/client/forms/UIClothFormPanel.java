package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_physics.cloth.ClothEdge;
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.forms.PhysicsForms;

/**
 * The cloth form's own tab: what the sheet looks like, what it is, and what its fabric is like —
 * in that order, the quick pick first (§7.2).
 *
 * <p>Everything physical about the sheet lives here rather than in the shared Physics tab,
 * because a cloth form <em>is</em> its physics — there is no modifier to add or remove, and the
 * only thing the shared tab could offer is knobs that do not apply. The one shared thing, the
 * animation handle (§4), appears here under the same name it has everywhere.</p>
 */
public class UIClothFormPanel extends UIFormPanel<ClothForm>
{
    public UIButton pick;
    public UIColor color;
    public UIToggle linear;
    public UIToggle mipmap;
    public UIToggle shading;

    public UITrackpad width;
    public UITrackpad height;
    public UITrackpad segmentsX;
    public UITrackpad segmentsY;
    public UICirculate edge;

    public UITrackpad mass;
    public UITrackpad stiffness;
    public UITrackpad damping;
    public UITrackpad friction;
    public UITrackpad authority;

    public UIClothFormPanel(UIForm editor)
    {
        super(editor);

        this.pick = new UIButton(UIKeys.FORMS_EDITORS_BILLBOARD_PICK_TEXTURE, (b) ->
        {
            UITexturePicker.open(this.getContext(), this.form.texture.get(), (l) -> this.form.texture.set(l));
        });
        this.color = new UIColor((value) -> this.form.color.set(Color.rgba(value))).direction(Direction.LEFT).withAlpha();
        this.linear = new UIToggle(UIKeys.TEXTURES_LINEAR, false, (b) -> this.form.linear.set(b.getValue()));
        this.mipmap = new UIToggle(UIKeys.TEXTURES_MIPMAP, false, (b) -> this.form.mipmap.set(b.getValue()));
        this.shading = new UIToggle(UIKeys.FORMS_EDITORS_BILLBOARD_SHADING, false, (b) -> this.form.shading.set(b.getValue()));

        this.width = new UITrackpad((value) -> this.form.width.set(value.floatValue()));
        this.width.limit(0.1D, 16D).tooltip(PhysicsKeys.CLOTH_WIDTH);
        this.height = new UITrackpad((value) -> this.form.height.set(value.floatValue()));
        this.height.limit(0.1D, 16D).tooltip(PhysicsKeys.CLOTH_HEIGHT);

        this.segmentsX = new UITrackpad((value) -> this.form.segmentsX.set(value.intValue()));
        this.segmentsX.limit(1D, 32D).integer().tooltip(PhysicsKeys.CLOTH_SEGMENTS_X);
        this.segmentsY = new UITrackpad((value) -> this.form.segmentsY.set(value.intValue()));
        this.segmentsY.limit(1D, 32D).integer().tooltip(PhysicsKeys.CLOTH_SEGMENTS_Y);

        this.edge = new UICirculate((b) -> this.form.edge.set(ClothEdge.values()[b.getValue()].name()));
        this.edge.addLabel(PhysicsKeys.CLOTH_EDGE_TOP);
        this.edge.addLabel(PhysicsKeys.CLOTH_EDGE_LEFT);
        this.edge.addLabel(PhysicsKeys.CLOTH_EDGE_TOP_CORNERS);
        this.edge.addLabel(PhysicsKeys.CLOTH_EDGE_NONE);
        this.edge.tooltip(PhysicsKeys.CLOTH_EDGE_TOOLTIP);

        this.mass = new UITrackpad((value) -> this.form.mass.set(value.floatValue()));
        this.mass.limit(0.01D, 1000D).tooltip(PhysicsKeys.CLOTH_MASS);
        this.stiffness = new UITrackpad((value) -> this.form.stiffness.set(value.floatValue()));
        this.stiffness.limit(0D, 1D).tooltip(PhysicsKeys.CLOTH_STIFFNESS);
        this.damping = new UITrackpad((value) -> this.form.damping.set(value.floatValue()));
        this.damping.limit(0D, 1D).tooltip(PhysicsKeys.CLOTH_DAMPING);
        this.friction = new UITrackpad((value) -> this.form.friction.set(value.floatValue()));
        this.friction.limit(0D, 1D).tooltip(PhysicsKeys.FRICTION);

        this.authority = new UITrackpad((value) -> PhysicsForms.setAuthority(this.form, value.floatValue()));
        this.authority.limit(0D, 1D).tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        this.options.add(this.pick, this.color, this.linear, this.mipmap, this.shading);
        this.options.add(UI.label(PhysicsKeys.CLOTH_SHEET).marginTop(UIConstants.SECTION_GAP));
        this.options.add(UI.row(this.width, this.height), UI.row(this.segmentsX, this.segmentsY), this.edge);
        this.options.add(UI.label(PhysicsKeys.CLOTH_FABRIC).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.mass, this.stiffness, this.damping, this.friction);
        this.options.add(UI.label(PhysicsKeys.AUTHORITY).marginTop(UIConstants.SECTION_GAP), this.authority);
    }

    @Override
    public void startEdit(ClothForm form)
    {
        super.startEdit(form);

        this.color.setColor(form.color.get().getARGBColor());
        this.linear.setValue(form.linear.get());
        this.mipmap.setValue(form.mipmap.get());
        this.shading.setValue(form.shading.get());

        this.width.setValue(form.width.get());
        this.height.setValue(form.height.get());
        this.segmentsX.setValue(form.segmentsX.get());
        this.segmentsY.setValue(form.segmentsY.get());
        this.edge.setValue(form.getEdge().ordinal());

        this.mass.setValue(form.mass.get());
        this.stiffness.setValue(form.stiffness.get());
        this.damping.setValue(form.damping.get());
        this.friction.setValue(form.friction.get());
        this.authority.setValue(PhysicsForms.getAuthority(form));
    }
}
