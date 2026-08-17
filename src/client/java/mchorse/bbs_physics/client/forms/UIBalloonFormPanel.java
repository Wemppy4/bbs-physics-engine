package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.forms.editors.utils.UICropOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.forms.PhysicsForms;

/**
 * The balloon form's own tab: what the ball looks like, what it is, and what its skin is like —
 * the cloth panel's arrangement, because the two forms are siblings. Everything physical lives
 * here rather than in the shared Physics tab for the same reason cloth's does: a balloon <em>is</em>
 * its physics. The one shared thing, the animation handle (§4), appears under its usual name.
 */
public class UIBalloonFormPanel extends UIFormPanel<BalloonForm>
{
    public UIButton pick;
    public UIColor color;
    public UIToggle linear;
    public UIToggle mipmap;
    public UIToggle shading;
    public UIButton openCrop;

    public UITrackpad radius;
    public UITrackpad segments;
    public UITrackpad rings;

    public UITrackpad inflation;
    public UITrackpad stiffness;
    public UITrackpad mass;
    public UITrackpad gravity;
    public UITrackpad friction;
    public UITrackpad restitution;
    public UITrackpad damping;
    public UITrackpad authority;

    public UIBalloonFormPanel(UIForm editor)
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
        this.openCrop = new UIButton(UIKeys.FORMS_EDITORS_BILLBOARD_EDIT_CROP, (b) ->
        {
            UIOverlay.addOverlay(this.getContext(), new UICropOverlayPanel(this.form.texture.get(), this.form.crop.get()), 0.5F, 0.5F);
        });

        this.radius = new UITrackpad((value) -> this.form.radius.set(value.floatValue()));
        this.radius.limit(0.1D, 8D).tooltip(PhysicsKeys.BALLOON_RADIUS);
        this.segments = new UITrackpad((value) -> this.form.segments.set(value.intValue()));
        this.segments.limit(3D, 32D).integer().tooltip(PhysicsKeys.BALLOON_SEGMENTS);
        this.rings = new UITrackpad((value) -> this.form.rings.set(value.intValue()));
        this.rings.limit(2D, 24D).integer().tooltip(PhysicsKeys.BALLOON_RINGS);

        this.inflation = new UITrackpad((value) -> this.form.inflation.set(value.floatValue()));
        this.inflation.limit(0D, 1D).tooltip(PhysicsKeys.BALLOON_INFLATION);
        this.stiffness = new UITrackpad((value) -> this.form.stiffness.set(value.floatValue()));
        this.stiffness.limit(0D, 1D).tooltip(PhysicsKeys.BALLOON_STIFFNESS);
        this.mass = new UITrackpad((value) -> this.form.mass.set(value.floatValue()));
        this.mass.limit(0.01D, 1000D).tooltip(PhysicsKeys.BALLOON_MASS);
        this.gravity = new UITrackpad((value) -> this.form.gravity.set(value.floatValue()));
        this.gravity.limit(-2D, 2D).tooltip(PhysicsKeys.BALLOON_GRAVITY);

        this.friction = new UITrackpad((value) -> this.form.friction.set(value.floatValue()));
        this.friction.limit(0D, 1D).tooltip(PhysicsKeys.FRICTION);
        this.restitution = new UITrackpad((value) -> this.form.restitution.set(value.floatValue()));
        this.restitution.limit(0D, 1D).tooltip(PhysicsKeys.RESTITUTION);
        this.damping = new UITrackpad((value) -> this.form.damping.set(value.floatValue()));
        this.damping.limit(0D, 1D).tooltip(PhysicsKeys.BALLOON_DAMPING);

        this.authority = new UITrackpad((value) -> PhysicsForms.setAuthority(this.form, value.floatValue()));
        this.authority.limit(0D, 1D).tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        this.options.add(this.pick, this.color, this.linear, this.mipmap, this.shading, this.openCrop);
        this.options.add(UI.label(PhysicsKeys.BALLOON_BALL).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.radius, UI.row(this.segments, this.rings));
        this.options.add(UI.label(PhysicsKeys.BALLOON_SKIN).marginTop(UIConstants.SECTION_GAP));
        this.options.add(this.inflation, this.stiffness, this.mass, this.gravity);
        this.options.add(UI.row(this.friction, this.restitution), this.damping);
        this.options.add(UI.label(PhysicsKeys.AUTHORITY).marginTop(UIConstants.SECTION_GAP), this.authority);
    }

    @Override
    public void startEdit(BalloonForm form)
    {
        super.startEdit(form);

        this.color.setColor(form.color.get().getARGBColor());
        this.linear.setValue(form.linear.get());
        this.mipmap.setValue(form.mipmap.get());
        this.shading.setValue(form.shading.get());

        this.radius.setValue(form.radius.get());
        this.segments.setValue(form.segments.get());
        this.rings.setValue(form.rings.get());

        this.inflation.setValue(form.inflation.get());
        this.stiffness.setValue(form.stiffness.get());
        this.mass.setValue(form.mass.get());
        this.gravity.setValue(form.gravity.get());
        this.friction.setValue(form.friction.get());
        this.restitution.setValue(form.restitution.get());
        this.damping.setValue(form.damping.get());
        this.authority.setValue(PhysicsForms.getAuthority(form));
    }
}
