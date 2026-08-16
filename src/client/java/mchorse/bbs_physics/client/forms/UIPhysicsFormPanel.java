package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.ragdoll.UIRagdollSection;
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
 * <p><b>A modifier does not touch the markup at all</b> (Р11). Adding one used to run the automatic
 * pass and removing the last one used to wipe the result, which made shape a thing the physics tab
 * quietly owned — and an author who had marked a model up by hand in the other tab watched it
 * vanish under a click here. Collision is now set up on its own, once, and physics only reads it.
 * The cost of that is a form that can carry a modifier and no shape, which is exactly the state the
 * notice at the top of the tab is for.</p>
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

    /** Shown when a modifier is on and there is no shape for it to work with — see {@link #marked}. */
    private final UIText unmarked;

    /** What the tab was last built against, so an edit made in the collision tab is noticed. */
    private boolean marked;

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

        /* Wrapped rather than a one-line label: the column is narrow, and the sentence that has to
         * be read here is the one a single line cuts in half. */
        this.unmarked = new UIText(PhysicsKeys.PHYSICS_UNMARKED).color(Colors.LIGHTER_GRAY, true).padding(0, 2);
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
        this.sync();
    }

    private void toggleRagdoll(boolean add)
    {
        if (!(this.form instanceof ModelForm))
        {
            return;
        }

        FormRagdolls.set(this.form, FormRagdolls.get(this.form).withEnabled(add));
        this.sync();
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
        this.marked = FormCollisions.has(this.form);

        this.options.removeAll();

        /* First, above the modifier it blocks: a modifier with nothing to collide as does nothing
         * at all, and since Р11 nothing marks the form up on the author's behalf. Read before the
         * settings, it is an instruction; read under them, it is an epitaph. */
        if ((body || ragdoll) && !this.marked)
        {
            this.options.add(this.unmarked);
        }

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

        this.options.resize();
    }

    /**
     * The markup is edited in the <em>other</em> tab, and switching tabs does not rebuild this one —
     * so without this the notice would still be sitting there after the author had gone and answered
     * it. {@link FormCollisions#has} is the cheap form of the question (empty slots are never
     * written, so "has anything stored" and "has anything that collides" are the same question), and
     * the rebuild only fires on the frame the answer changes.
     */
    @Override
    public void render(UIContext context)
    {
        if (this.form != null && FormCollisions.has(this.form) != this.marked)
        {
            this.rebuild(PhysicsForms.getBody(this.form).enabled(), FormRagdolls.isEnabled(this.form));
        }

        super.render(context);
    }

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }
}
