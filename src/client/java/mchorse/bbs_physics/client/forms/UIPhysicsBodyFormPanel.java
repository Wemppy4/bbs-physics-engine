package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.forms.PhysicsBodyForm;

/** The physics body's settings: how much the animation owns it, and how it behaves when it lands. */
public class UIPhysicsBodyFormPanel extends UIFormPanel<PhysicsBodyForm>
{
    public UITrackpad authority;
    public UITrackpad mass;
    public UITrackpad friction;
    public UITrackpad restitution;

    /** What this body will collide as — or a warning that it will collide as nothing. */
    public UILabel shape;

    public UIPhysicsBodyFormPanel(UIForm editor)
    {
        super(editor);

        this.authority = new UITrackpad((v) -> this.form.authority.set(v.floatValue()));
        this.authority.limit(0D, 1D).increment(0.1D);
        this.authority.tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        this.mass = new UITrackpad((v) -> this.form.mass.set(v.floatValue()));
        this.mass.limit(0.01D, 10000D);

        this.friction = new UITrackpad((v) -> this.form.friction.set(v.floatValue()));
        this.friction.limit(0D, 1D).increment(0.1D);

        this.restitution = new UITrackpad((v) -> this.form.restitution.set(v.floatValue()));
        this.restitution.limit(0D, 1D).increment(0.1D);

        this.shape = UI.label(IKey.EMPTY, UIConstants.LIST_ITEM_HEIGHT);
        this.shape.labelAnchor(0F, 0.5F);

        this.options.add(
            UI.labelRow(PhysicsKeys.AUTHORITY, this.authority),
            UI.labelRow(PhysicsKeys.MASS, this.mass),
            UI.labelRow(PhysicsKeys.FRICTION, this.friction),
            UI.labelRow(PhysicsKeys.RESTITUTION, this.restitution),
            this.shape
        );
    }

    @Override
    public void startEdit(PhysicsBodyForm form)
    {
        super.startEdit(form);

        this.authority.setValue(form.authority.get());
        this.mass.setValue(form.mass.get());
        this.friction.setValue(form.friction.get());
        this.restitution.setValue(form.restitution.get());

        /* A body has no shape of its own: it collides as whatever is inside it, and only where
         * that has been marked up on the Collision tab. Saying so here — with the count, so the
         * answer is not just "some" — is the difference between "the crate falls through the
         * floor because physics is broken" and "because nothing in it was marked up". */
        int marked = CollisionCollector.collectBody(form, "", null).size();

        this.shape.label = marked == 0 ? PhysicsKeys.SHAPE_NONE : PhysicsKeys.SHAPE_FROM.format(marked);
        this.shape.color(marked == 0 ? (Colors.A100 | Colors.NEGATIVE) : Colors.LIGHTER_GRAY);
    }
}
