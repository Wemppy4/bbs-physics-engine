package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.collision.CollisionAuto;
import mchorse.bbs_physics.client.ragdoll.UIRagdollSection;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionSlot;
import mchorse.bbs_physics.collision.FormCollision;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.forms.FormBody;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdolls;

import java.util.function.UnaryOperator;

/**
 * The physics tab: Blender's physics tab, in BBS.
 *
 * <p>A form has no physics until a modifier is added, and a modifier is a panel with a cross — the
 * arrangement Blender's Force Field / Collision / Cloth / Rigid Body row has. What the tab does
 * <em>not</em> do any more is describe shape: that moved back out to the collision tab, which is a
 * job done once per model and forgotten, while this one is where an author returns every shot.</p>
 *
 * <p><b>Adding a modifier runs the markup pass</b> (Р8.4) over a form nobody has marked up yet.
 * Nobody starts from empty; what that leaves for the author is correcting a draft in the other tab,
 * not answering "what shape is this?" before anything has ever moved.</p>
 *
 * <p>One handle drives both modifiers (§4), so it sits inside whichever modifier is present rather
 * than being duplicated into each — a form is a body or a ragdoll, never both.</p>
 */
public class UIPhysicsFormPanel extends UIFormPanel<Form>
{
    private final UIButton addModifier;

    private final UIModifierSection bodySection;
    private final UICirculate type;
    private final UIElement typeRow;
    private final UITrackpad mass;
    private final UIElement massRow;
    private final UITrackpad friction;
    private final UIElement frictionRow;
    private final UITrackpad restitution;
    private final UIElement restitutionRow;

    private final UIModifierSection ragdollSection;
    private final UIRagdollSection ragdollBones;

    /** One per modifier, because the same element cannot hang under two parents. */
    private final UITrackpad bodyAuthority;
    private final UIElement bodyAuthorityRow;
    private final UITrackpad ragdollAuthority;
    private final UIElement ragdollAuthorityRow;

    /** Shown only when there is nothing marked up at all — the one state that reads as broken. */
    private final UILabel unmarked;

    private boolean syncing;

    public UIPhysicsFormPanel(UIForm editor)
    {
        super(editor);

        this.addModifier = new UIButton(PhysicsKeys.PHYSICS_ADD, (b) -> this.openModifierMenu());

        /* Type, as three-way as it needs to be: Blender's Active/Passive, no more. */
        this.type = new UICirculate((b) -> this.editBody((body) -> body.withPassive(b.getValue() == 1)));
        this.type.addLabel(PhysicsKeys.BODY_TYPE_ACTIVE);
        this.type.addLabel(PhysicsKeys.BODY_TYPE_PASSIVE);
        this.type.tooltip(PhysicsKeys.BODY_TYPE_TOOLTIP);
        this.typeRow = UI.labelRow(PhysicsKeys.BODY_TYPE, this.type);

        this.mass = new UITrackpad((v) -> this.editBody((body) -> body.withMass(v.floatValue())));
        this.mass.limit(0.01D, 10000D).increment(1D);
        this.mass.tooltip(PhysicsKeys.BODY_MASS_TOOLTIP);

        /* The material estimate is a one-off calculation rather than a property, so it belongs on
         * the number it fills in — not on a row of its own reading like a sentence. */
        UIIcon material = new UIIcon(Icons.MORE, (b) -> this.openMaterialMenu());

        material.tooltip(PhysicsKeys.MATERIAL_TOOLTIP);

        UIElement massGroup = new UIElement();

        massGroup.row(UIConstants.MARGIN).preferred(0).height(UIConstants.CONTROL_HEIGHT);
        massGroup.add(this.mass, material.w(16));

        this.massRow = UI.labelRow(PhysicsKeys.MASS, UIConstants.VALUE_WIDTH, massGroup);

        this.friction = new UITrackpad((v) -> this.editBody((body) -> body.withFriction(v.floatValue())));
        this.friction.limit(0D, 1D).increment(0.05D);
        this.frictionRow = UI.labelRow(PhysicsKeys.FRICTION, this.friction);

        this.restitution = new UITrackpad((v) -> this.editBody((body) -> body.withRestitution(v.floatValue())));
        this.restitution.limit(0D, 1D).increment(0.05D);
        this.restitutionRow = UI.labelRow(PhysicsKeys.RESTITUTION, this.restitution);

        this.bodyAuthority = this.authority();
        this.bodyAuthorityRow = UI.labelRow(PhysicsKeys.AUTHORITY, this.bodyAuthority);
        this.ragdollAuthority = this.authority();
        this.ragdollAuthorityRow = UI.labelRow(PhysicsKeys.AUTHORITY, this.ragdollAuthority);

        this.bodySection = new UIModifierSection(PhysicsKeys.BODY_TITLE, "physics.body", () -> this.toggleBody(false));
        this.bodySection.fields.add(this.typeRow, this.massRow, this.frictionRow, this.restitutionRow, this.bodyAuthorityRow);

        this.ragdollBones = new UIRagdollSection(() -> this.options.resize());
        this.ragdollSection = new UIModifierSection(PhysicsKeys.RAGDOLL_TITLE, "physics.ragdoll", () -> this.toggleRagdoll(false));
        this.ragdollSection.fields.add(this.ragdollAuthorityRow, this.ragdollBones);

        this.unmarked = UI.label(PhysicsKeys.PHYSICS_UNMARKED, UIConstants.LIST_ITEM_HEIGHT, Colors.LIGHTER_GRAY);
        this.unmarked.labelAnchor(0F, 0.5F);
    }

    private UITrackpad authority()
    {
        UITrackpad trackpad = new UITrackpad((v) -> this.setAuthority(v.floatValue()));

        trackpad.limit(0D, 1D).increment(0.1D);
        trackpad.tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        return trackpad;
    }

    /* Editing */

    /**
     * The modifiers on offer. The two that do not work yet are listed rather than hidden: a menu
     * that hides what is coming teaches the author this tab is only about crates.
     */
    private void openModifierMenu()
    {
        if (this.form == null)
        {
            return;
        }

        boolean model = this.form instanceof ModelForm;

        this.getContext().replaceContextMenu((menu) ->
        {
            menu.action(Icons.BLOCK, PhysicsKeys.PHYSICS_ADD_BODY, () -> this.toggleBody(true));

            if (model)
            {
                menu.action(Icons.LIMB, PhysicsKeys.PHYSICS_ADD_RAGDOLL, () -> this.toggleRagdoll(true));
            }

            menu.action(Icons.STRUCTURE, PhysicsKeys.PHYSICS_ADD_OBSTACLE, () -> {});
            menu.action(Icons.CURVES, PhysicsKeys.PHYSICS_ADD_CLOTH, () -> {});
        });
    }

    /** Fills the mass in from a material's density and the volume of what is marked up. */
    private void openMaterialMenu()
    {
        if (this.form == null)
        {
            return;
        }

        this.getContext().replaceContextMenu((menu) ->
        {
            for (BodyMaterials.Material material : BodyMaterials.ALL)
            {
                menu.action(Icons.MATERIAL, material.label(), () ->
                {
                    float estimated = BodyMaterials.estimate(this.form, material);

                    if (estimated > 0F)
                    {
                        this.editBody((body) -> body.withMass(estimated));
                        this.mass.setValue(estimated);
                    }
                });
            }
        });
    }

    private void toggleBody(boolean add)
    {
        if (this.form == null)
        {
            return;
        }

        PhysicsForms.setBody(this.form, add ? FormBody.added() : FormBody.EMPTY);

        if (add)
        {
            this.markUp();
        }
        else
        {
            this.dropMarkup();
        }

        this.sync();
    }

    private void toggleRagdoll(boolean add)
    {
        if (!(this.form instanceof ModelForm))
        {
            return;
        }

        FormRagdolls.set(this.form, FormRagdolls.get(this.form).withEnabled(add));

        if (add)
        {
            this.markUp();
        }
        else
        {
            this.dropMarkup();
        }

        this.sync();
    }

    /**
     * Removing the last modifier takes the collision markup with it.
     *
     * <p>The markup is stored separately on the form and read by everyone (Р6), which is right —
     * but leaving it behind meant a form the author had just stripped of physics still quietly
     * carried a set of shapes, and adding the modifier back would find them and skip the automatic
     * pass, handing back yesterday's draft instead of a fresh one. Blender behaves the same way: the
     * shape belongs to the modifier, and deleting the modifier deletes it. Undo brings it back if
     * the click was a mistake.</p>
     */
    private void dropMarkup()
    {
        if (!PhysicsForms.isSimulated(this.form))
        {
            FormCollisions.set(this.form, FormCollision.EMPTY);
        }
    }

    /**
     * The Р8.4 pass. It only runs over a form nobody has marked up yet — re-running it on the
     * author's own work would be the modifier quietly undoing it — and the collision tab's own
     * button is where running it again on purpose lives.
     */
    private void markUp()
    {
        FormCollision collision = FormCollisions.get(this.form);

        if (CollisionAuto.isBlank(collision))
        {
            FormCollisions.set(this.form, CollisionAuto.mark(this.form, collision, CollisionAuto.DEFAULT_THRESHOLD));
        }
    }

    private void setAuthority(float value)
    {
        if (!this.syncing && this.form != null)
        {
            PhysicsForms.setAuthority(this.form, value);
        }
    }

    private void editBody(UnaryOperator<FormBody> edit)
    {
        if (this.syncing || this.form == null)
        {
            return;
        }

        PhysicsForms.setBody(this.form, edit.apply(PhysicsForms.getBody(this.form)));
    }

    /* Syncing the UI */

    @Override
    public void startEdit(Form form)
    {
        super.startEdit(form);

        this.ragdollBones.setForm(form);
        this.sync();
    }

    /**
     * A body part clicked in the viewport should land in the bone list on screen, instead of
     * bouncing the author into the pose editor.
     */
    @Override
    public boolean pickBoneInList(String bone)
    {
        return this.form != null && FormRagdolls.isEnabled(this.form) && this.ragdollBones.pickBoneInList(bone);
    }

    private void sync()
    {
        if (this.form == null)
        {
            return;
        }

        this.syncing = true;

        FormBody body = PhysicsForms.getBody(this.form);
        boolean ragdoll = FormRagdolls.isEnabled(this.form);
        float authority = PhysicsForms.getAuthority(this.form);

        this.type.setValue(body.passive() ? 1 : 0);
        this.mass.setValue(body.mass());
        this.friction.setValue(body.friction());
        this.restitution.setValue(body.restitution());
        this.bodyAuthority.setValue(authority);
        this.ragdollAuthority.setValue(authority);

        if (ragdoll)
        {
            this.ragdollBones.setForm(this.form);
        }

        this.rebuild(body.enabled(), ragdoll);

        this.syncing = false;
    }

    /**
     * Puts the tab together out of the modifiers this form actually has.
     *
     * <p>Rebuilt rather than hidden, and that is not a style choice: {@code setVisible} in BBS stops
     * an element from drawing but leaves it holding its place in the column, so a tab that hid the
     * halves it did not need was a tab full of unexplained gaps.</p>
     */
    private void rebuild(boolean body, boolean ragdoll)
    {
        this.options.removeAll();

        /* A form is a body or a ragdoll, never both: welding a model into one falling lump and
         * jointing its bones are two answers to the same question. So the button that adds one is
         * only there while there is nothing to conflict with. */
        if (!body && !ragdoll)
        {
            this.options.add(this.addModifier);
        }

        if (body)
        {
            this.options.add(this.bodySection);
        }

        if (ragdoll)
        {
            this.options.add(this.ragdollSection);
        }

        if ((body || ragdoll) && !isMarked(FormCollisions.get(this.form)))
        {
            this.options.add(this.unmarked);
        }

        this.options.resize();
    }

    /** Whether anything at all would collide — the difference between "falls" and "falls forever". */
    private static boolean isMarked(FormCollision collision)
    {
        for (CollisionSlot slot : collision.slots().values())
        {
            if (slot.mode() != CollisionMode.NONE)
            {
                return true;
            }
        }

        return false;
    }

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }
}
