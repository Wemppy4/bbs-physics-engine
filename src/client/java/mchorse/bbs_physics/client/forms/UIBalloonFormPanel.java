package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
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
    /** What the skin looks like — the picture form's own six controls, shared (§ PhysicsFields). */
    public final PhysicsFields.Texture skin = new PhysicsFields.Texture(() -> this.form, this::getContext);

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
    public final UITrackpad authority = PhysicsFields.authority((value) -> PhysicsForms.setAuthority(this.form, value));

    public UIBalloonFormPanel(UIForm editor)
    {
        super(editor);


        this.radius = new UITrackpad((value) -> this.form.radius.set(value.floatValue()));
        this.radius.limit(0.1D, 8D).tooltip(PhysicsKeys.BALLOON_RADIUS);
        this.segments = new UITrackpad((value) ->
        {
            this.form.segments.set(value.intValue());
            this.syncRings();
        });
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


        this.skin.addTo(this.options);
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

        this.skin.sync(form);

        this.radius.setValue(form.radius.get());
        this.segments.setValue(form.segments.get());
        this.syncRings();

        this.inflation.setValue(form.inflation.get());
        this.stiffness.setValue(form.stiffness.get());
        this.mass.setValue(form.mass.get());
        this.gravity.setValue(form.gravity.get());
        this.friction.setValue(form.friction.get());
        this.restitution.setValue(form.restitution.get());
        this.damping.setValue(form.damping.get());
        this.authority.setValue(PhysicsForms.getAuthority(form));
    }

    /**
     * Holds the ring slider at the fewest rows this many meridians can hold together, and shows
     * the number the ball is really built with.
     *
     * <p>The two numbers are not independent — cells much taller than they are wide take the
     * solver apart (see {@link BalloonForm#minimumRings}) — and the form widens the mesh whatever
     * the slider says. Left showing the authored number, the panel would read "2 rings" beside a
     * ball plainly built from seven, which is the interface disagreeing with itself. So the floor
     * moves with the meridians and the slider shows the built figure. It only ever bites past
     * sixteen meridians, which is where the lopsided end begins.</p>
     *
     * <p>Shown, not written. Merely opening a form editor has no business editing the form, and
     * the stored value is harmless where it is — nothing reads it without going through
     * {@code getRings}. Turning the meridians back down brings the author's own number back.</p>
     */
    private void syncRings()
    {
        this.rings.limit(BalloonForm.minimumRings(this.form.segments.get()), 24D).integer();
        this.rings.setValue(this.form.getRings());
    }
}
