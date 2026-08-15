package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.collision.CollisionAuto;
import mchorse.bbs_physics.client.collision.FormBounds;
import mchorse.bbs_physics.client.collision.UICollisionSection;
import mchorse.bbs_physics.client.ragdoll.UIRagdollSection;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionShape;
import mchorse.bbs_physics.collision.CollisionSlot;
import mchorse.bbs_physics.collision.FormCollision;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.forms.FormBody;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdolls;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The Physics tab: Blender's physics tab, in BBS.
 *
 * <p>A form has no physics until a button here is pressed, and the buttons are visible before they
 * are used — including the ones that do not work yet — so the empty tab says what this grows into
 * (§7.1). Pressing one turns it into a panel with a cross, which is how Blender's Force Field /
 * Collision / Cloth / Rigid Body row behaves.</p>
 *
 * <p><b>Shape is one line, not a screen.</b> That was the observation the whole turn started from:
 * "I don't remember having to set up collision in Blender". A crate picks Auto or Box from a
 * dropdown and is done; the bone tree, the primitives and the nine trackpads live in a folded
 * "By hand" section for the times the draft was wrong. A model cannot answer in one line — it has
 * dozens of bones and Blender has no ragdoll to copy — so it follows Blender's ragdoll addon
 * instead (§13): a summary of what was marked up, a button to redo it, and the detail folded away.
 * </p>
 *
 * <p><b>Adding a modifier runs the markup pass</b> (Р8.4). Nobody starts from empty. What that
 * leaves for the author is correcting a draft, not answering "what shape is this?" before anything
 * has ever moved.</p>
 */
public class UIPhysicsFormPanel extends UIFormPanel<Form>
{
    /** The dropdown's order: none, automatic, then the primitives, widest first. */
    private static final CollisionKind[] KINDS = {CollisionKind.BOX, CollisionKind.SPHERE, CollisionKind.CAPSULE, CollisionKind.CYLINDER};

    private final UIButton addBody;
    private final UIButton addRagdoll;
    private final UIButton obstacle;
    private final UIButton cloth;
    private final UIElement modifiers;

    private final UISection bodySection;
    private final UICirculate type;
    private final UIElement typeRow;
    private final UICirculate shape;
    private final UIElement shapeRow;
    private final UIElement massRow;
    private final UIElement frictionRow;
    private final UIElement restitutionRow;
    private final UITrackpad mass;
    private final UICirculate material;
    private final UITrackpad friction;
    private final UITrackpad restitution;
    private final UITrackpad authority;
    private final UIElement authorityRow;

    private final UISection ragdollSection;

    /** What the automatic pass produced, for both modifiers — the "12 bones" line and its button. */
    private final UILabel markup;
    private final UIButton remark;
    private final UIElement markupRow;

    private final UISection manual;
    private final UISection joints;
    private final UICollisionSection collision;
    private final UIRagdollSection ragdollJoints;

    private final UILabel next;

    private boolean syncing;

    public UIPhysicsFormPanel(UIForm editor)
    {
        super(editor);

        /* The row of modifiers. Obstacle and cloth are shown greyed rather than hidden: a row that
         * hides what is coming teaches the author this tab is only about crates. */
        this.addBody = new UIButton(PhysicsKeys.PHYSICS_ADD_BODY, (b) -> this.toggleBody(true));
        this.addBody.tooltip(PhysicsKeys.PHYSICS_ADD_BODY_TOOLTIP);

        this.addRagdoll = new UIButton(PhysicsKeys.PHYSICS_ADD_RAGDOLL, (b) -> this.toggleRagdoll(true));
        this.addRagdoll.tooltip(PhysicsKeys.PHYSICS_ADD_RAGDOLL_TOOLTIP);

        this.obstacle = new UIButton(PhysicsKeys.PHYSICS_ADD_OBSTACLE, (b) -> {});
        this.obstacle.tooltip(PhysicsKeys.PHYSICS_LATER);
        this.obstacle.setEnabled(false);

        this.cloth = new UIButton(PhysicsKeys.PHYSICS_ADD_CLOTH, (b) -> {});
        this.cloth.tooltip(PhysicsKeys.PHYSICS_LATER);
        this.cloth.setEnabled(false);

        this.modifiers = UI.column(UI.row(this.addBody, this.addRagdoll), UI.row(this.obstacle, this.cloth));

        /* Type, as three-way as it needs to be: Blender's Active/Passive, no more. */
        this.type = new UICirculate((b) -> this.editBody((body) -> body.withPassive(b.getValue() == 1)));
        this.type.addLabel(PhysicsKeys.BODY_TYPE_ACTIVE);
        this.type.addLabel(PhysicsKeys.BODY_TYPE_PASSIVE);
        this.type.tooltip(PhysicsKeys.BODY_TYPE_TOOLTIP);

        this.shape = new UICirculate((b) -> this.setShape(b.getValue()));
        this.shape.addLabel(PhysicsKeys.SHAPE_NOTHING);
        this.shape.addLabel(PhysicsKeys.SHAPE_AUTO);

        for (CollisionKind kind : KINDS)
        {
            this.shape.addLabel(PhysicsKeys.kind(kind));
        }

        this.shape.tooltip(PhysicsKeys.SHAPE_TOOLTIP);
        this.shapeRow = UI.labelRow(PhysicsKeys.SHAPE, this.shape);
        this.typeRow = UI.labelRow(PhysicsKeys.BODY_TYPE, this.type);

        this.mass = new UITrackpad((v) -> this.editBody((body) -> body.withMass(v.floatValue())));
        this.mass.limit(0.01D, 10000D).increment(1D);
        this.mass.tooltip(PhysicsKeys.BODY_MASS_TOOLTIP);

        this.material = new UICirculate((b) -> this.applyMaterial(b.getValue()));
        this.material.addLabel(PhysicsKeys.MATERIAL_PICK);

        for (BodyMaterials.Material m : BodyMaterials.ALL)
        {
            this.material.addLabel(m.label());
        }

        this.material.tooltip(PhysicsKeys.MATERIAL_TOOLTIP);

        this.friction = new UITrackpad((v) -> this.editBody((body) -> body.withFriction(v.floatValue())));
        this.friction.limit(0D, 1D).increment(0.05D);

        this.restitution = new UITrackpad((v) -> this.editBody((body) -> body.withRestitution(v.floatValue())));
        this.restitution.limit(0D, 1D).increment(0.05D);

        this.massRow = UI.labelRow(PhysicsKeys.MASS, this.mass);
        this.frictionRow = UI.labelRow(PhysicsKeys.FRICTION, this.friction);
        this.restitutionRow = UI.labelRow(PhysicsKeys.RESTITUTION, this.restitution);

        /* Labelled by its ends, which is the fix for the handle reading backwards: an author looking
         * for "switch physics on" expects 1 to mean physics, and here 1 means animation (§7.7). */
        this.authority = new UITrackpad((v) -> this.setAuthority(v.floatValue()));
        this.authority.limit(0D, 1D).increment(0.1D);
        this.authority.tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        this.markup = UI.label(PhysicsKeys.PHYSICS_MARKUP_NONE);
        this.remark = new UIButton(PhysicsKeys.PHYSICS_REMARK, (b) -> this.remark());
        this.remark.tooltip(PhysicsKeys.PHYSICS_REMARK_TOOLTIP);
        this.markupRow = UI.column(this.markup, this.remark);

        this.bodySection = this.section(PhysicsKeys.BODY_TITLE, "physics.body", true);

        this.close(this.bodySection, () -> this.toggleBody(false));

        this.ragdollSection = this.section(PhysicsKeys.RAGDOLL_TITLE, "physics.ragdoll", true);
        this.ragdollSection.fields.add(UI.label(PhysicsKeys.PHYSICS_RAGDOLL_HINT));

        this.close(this.ragdollSection, () -> this.toggleRagdoll(false));

        /* One handle for both modifiers (§4), so it lives outside their sections rather than being
         * duplicated into each — the same element cannot hang under two parents anyway. */
        this.authorityRow = UI.column(UI.label(PhysicsKeys.AUTHORITY_ENDS), this.authority);

        /* Everything an author only reaches for when the draft was wrong. Folded, and below the
         * things they reach for every time (§7.2). */
        this.collision = new UICollisionSection();
        this.ragdollJoints = new UIRagdollSection();

        this.joints = this.section(PhysicsKeys.PHYSICS_BY_BONES, "physics.bones", false);
        this.joints.fields.add(this.ragdollJoints);

        this.manual = this.section(PhysicsKeys.PHYSICS_MANUAL, "physics.manual", false);
        this.manual.fields.add(this.collision);

        this.next = UI.label(PhysicsKeys.PHYSICS_NEXT_NONE).background(Colors.A50);

        this.options.add(this.modifiers);
    }

    /** A cross in the section's header, where Blender puts the one that removes a modifier. */
    private void close(UISection section, Runnable action)
    {
        UIIcon cross = new UIIcon(Icons.CLOSE, (b) -> action.run());

        cross.tooltip(PhysicsKeys.PHYSICS_REMOVE);
        cross.relative(section.title).x(1F, -4).y(0.5F).wh(12, 12).anchor(1F, 0.5F);

        /* Into the header bar rather than into the section's body: added to the section itself it
         * became one more row in the column, floating under the last field. */
        section.title.add(cross);
    }

    /* Editing */

    private void toggleBody(boolean add)
    {
        if (this.form == null)
        {
            return;
        }

        PhysicsForms.setBody(this.form, add ? FormBody.added() : FormBody.EMPTY);

        if (add)
        {
            this.markUp(false);
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
            this.markUp(false);
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
     *
     * <p>Only when <em>nothing</em> is left: a model that still has a ragdoll keeps its bones.</p>
     */
    private void dropMarkup()
    {
        if (!PhysicsForms.isSimulated(this.form))
        {
            FormCollisions.set(this.form, FormCollision.EMPTY);
            this.collision.setForm(this.form);
        }
    }

    /**
     * The Р8.4 pass. On adding a modifier it only runs over a form nobody has marked up yet —
     * re-running it on the author's own work would be the button quietly undoing it — but the
     * "mark up again" button says out loud that this is what it does.
     */
    private void markUp(boolean force)
    {
        FormCollision collision = FormCollisions.get(this.form);

        if (!force && !CollisionAuto.isBlank(collision))
        {
            return;
        }

        FormCollisions.set(this.form, CollisionAuto.mark(this.form, force ? FormCollision.EMPTY : collision, CollisionAuto.DEFAULT_THRESHOLD));
    }

    private void remark()
    {
        if (this.form == null)
        {
            return;
        }

        this.markUp(true);
        this.collision.setForm(this.form);
        this.sync();
    }

    /**
     * The one-line shape, for a form that has one shape to give: none, the automatic draft, or a
     * single primitive fitted to what the form draws.
     */
    private void setShape(int index)
    {
        if (this.syncing || this.form == null)
        {
            return;
        }

        FormCollision collision = FormCollisions.get(this.form);

        if (index == 0)
        {
            collision = collision.with(FormCollision.SELF, CollisionSlot.NONE);
        }
        else if (index == 1)
        {
            collision = collision.with(FormCollision.SELF, CollisionSlot.AUTO);
        }
        else
        {
            CollisionShape bounds = FormBounds.of(this.form);
            CollisionShape fitted = new CollisionShape(KINDS[index - 2], bounds.offset(), bounds.rotation(), bounds.size());

            collision = collision.with(FormCollision.SELF, new CollisionSlot(CollisionMode.SHAPES, List.of(fitted)));
        }

        FormCollisions.set(this.form, collision);

        this.collision.setForm(this.form);
        this.sync();
    }

    private void applyMaterial(int index)
    {
        if (this.syncing || this.form == null || index == 0)
        {
            return;
        }

        float estimated = BodyMaterials.estimate(this.form, BodyMaterials.ALL.get(index - 1));

        if (estimated > 0F)
        {
            this.editBody((body) -> body.withMass(estimated));
            this.mass.setValue(estimated);
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

        this.collision.setForm(form);
        this.ragdollJoints.setForm(form);

        this.sync();
    }

    /**
     * A body part clicked in the viewport should land in whichever bone list is on screen, instead
     * of bouncing the author into the pose editor.
     */
    @Override
    public boolean pickBoneInList(String bone)
    {
        if (this.form != null && FormRagdolls.isEnabled(this.form) && this.ragdollJoints.pickBoneInList(bone))
        {
            return true;
        }

        return this.collision.pickBoneInList(bone);
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
        boolean model = this.form instanceof ModelForm;
        boolean any = body.enabled() || ragdoll;

        this.type.setValue(body.passive() ? 1 : 0);
        this.shape.setValue(this.shapeIndex());
        this.material.setValue(0);
        this.mass.setValue(body.mass());
        this.friction.setValue(body.friction());
        this.restitution.setValue(body.restitution());
        this.authority.setValue(PhysicsForms.getAuthority(this.form));

        int marked = markedCount(FormCollisions.get(this.form));

        this.markup.label = marked == 0 ? PhysicsKeys.PHYSICS_MARKUP_NONE : PhysicsKeys.PHYSICS_MARKUP.format(marked);
        this.next.label = this.nextStep(any, marked);

        this.rebuild(body, ragdoll, model, any);

        this.syncing = false;

        this.options.resize();
    }

    /**
     * Puts the tab together out of the parts this form actually has.
     *
     * <p>Rebuilt rather than hidden, and that is not a style choice: {@code setVisible} in BBS stops
     * an element from drawing but leaves it holding its place in the column, so a tab that hid the
     * halves it did not need was a tab full of unexplained gaps.</p>
     */
    private void rebuild(FormBody body, boolean ragdoll, boolean model, boolean any)
    {
        this.modifiers.removeAll();

        /* A form is a body or a ragdoll, never both: welding a model into one falling lump and
         * jointing its bones are two answers to the same question. */
        if (!any)
        {
            this.modifiers.add(model ? UI.row(this.addBody, this.addRagdoll) : UI.row(this.addBody));
        }

        this.modifiers.add(UI.row(this.obstacle, this.cloth));

        this.bodySection.fields.removeAll();
        this.bodySection.fields.add(this.typeRow);

        /* One line for a form with one shape to give. A model has dozens of bones and cannot answer
         * in a line, so it gets the summary and the button instead. */
        if (!model)
        {
            this.bodySection.fields.add(this.shapeRow);
        }

        this.bodySection.fields.add(this.massRow, this.material, this.frictionRow, this.restitutionRow);

        this.options.removeAll();
        this.options.add(this.modifiers);

        if (body.enabled())
        {
            this.options.add(this.bodySection);
        }

        if (ragdoll)
        {
            this.options.add(this.ragdollSection);
        }

        if (model && any)
        {
            this.options.add(this.markupRow);
        }

        if (any)
        {
            this.options.add(this.authorityRow);
        }

        if (ragdoll)
        {
            this.options.add(this.joints);
        }

        if (any)
        {
            this.options.add(this.manual);
        }

        this.options.add(this.next);
    }

    /** Where the form's own slot sits in the dropdown — see {@link #setShape(int)}. */
    private int shapeIndex()
    {
        CollisionSlot slot = FormCollisions.get(this.form).get(FormCollision.SELF);

        if (slot.mode() == CollisionMode.NONE)
        {
            return 0;
        }

        if (slot.mode() == CollisionMode.AUTO || slot.shapes().isEmpty())
        {
            return 1;
        }

        CollisionKind kind = slot.shapes().get(0).kind();

        for (int i = 0; i < KINDS.length; i++)
        {
            if (KINDS[i] == kind)
            {
                return i + 2;
            }
        }

        return 1;
    }

    private static int markedCount(FormCollision collision)
    {
        int count = 0;

        for (CollisionSlot slot : collision.slots().values())
        {
            if (slot.mode() != CollisionMode.NONE)
            {
                count += 1;
            }
        }

        return count;
    }

    /**
     * The one line that tells the author what to do next. Every branch of it is a question the tab
     * used to leave unanswered, and each one reads as physics being broken when it is not.
     */
    private IKey nextStep(boolean any, int marked)
    {
        if (!any)
        {
            return PhysicsKeys.PHYSICS_NEXT_NONE;
        }

        if (marked == 0)
        {
            return PhysicsKeys.PHYSICS_NEXT_UNMARKED;
        }

        return PhysicsKeys.PHYSICS_NEXT_READY.format(marked);
    }

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }
}
