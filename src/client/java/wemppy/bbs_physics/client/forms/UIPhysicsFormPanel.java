package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.client.scene.FilmScene;
import wemppy.bbs_physics.client.scene.FilmScenes;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.client.chain.UIChainSection;
import wemppy.bbs_physics.client.ragdoll.UIRagdollSection;
import wemppy.bbs_physics.forms.FormBody;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsType;
import wemppy.bbs_physics.ragdoll.FormRagdoll;
import wemppy.bbs_physics.ragdoll.FormRagdolls;

import java.util.function.UnaryOperator;

/** Selects one physics type and edits its settings without changing collision markup. */
public class UIPhysicsFormPanel extends UIFormPanel<Form>
{
    private final UIButton physicsType;
    private final UIElement physicsTypeRow;


    private final UICirculate type;
    private final UIElement typeRow;
    private final UITrackpad mass;
    private final UIElement massRow;
    private final UITrackpad friction;
    private final UIElement frictionRow;
    private final UITrackpad restitution;
    private final UIElement restitutionRow;

    public UITrackpad linearDamping;
    public UITrackpad angularDamping;
    private final UIElement dampingRow;
    public UITrackpad gravity;
    private final UIElement gravityRow;
    public UIToggle asleep;
    private final UIToggle[] lockMove = new UIToggle[3];
    private final UIToggle[] lockSpin = new UIToggle[3];
    private final UIElement lockMoveRow;
    private final UIElement lockSpinRow;

    public UITrackpad ragdollMass;
    private final UIElement ragdollMassRow;
    public UITrackpad ragdollDamping;
    private final UIElement ragdollDampingRow;
    public UITrackpad ragdollFriction;
    private final UIElement ragdollFrictionRow;
    public UITrackpad ragdollGravity;
    private final UIElement ragdollGravityRow;
    public UITrackpad muscles;
    public UITrackpad muscleDamping;
    private final UIElement musclesRow;
    public UIToggle ragdollSelfCollide;

    private final UIRagdollSection ragdollBones;

    private final UIChainSection chainBones;
    private final UITrackpad chainAuthority;
    private final UIElement chainAuthorityRow;

    /** One per type, because the same element cannot hang under two parents. */
    private final UITrackpad bodyAuthority;
    private final UIElement bodyAuthorityRow;
    private final UITrackpad ragdollAuthority;
    private final UIElement ragdollAuthorityRow;

    private boolean syncing;

    public UIPhysicsFormPanel(UIForm editor)
    {
        super(editor);

        this.physicsType = new UIButton(PhysicsKeys.PHYSICS_NONE, (b) -> this.openTypeMenu());
        this.physicsTypeRow = PhysicsUI.labelRow(PhysicsKeys.PHYSICS_TYPE, this.physicsType);

        /* Type, as three-way as it needs to be: Blender's Active/Passive, no more. */
        this.type = new UICirculate((b) -> this.editBody((body) -> body.withPassive(b.getValue() == 1)));
        this.type.addLabel(PhysicsKeys.BODY_TYPE_ACTIVE);
        this.type.addLabel(PhysicsKeys.BODY_TYPE_PASSIVE);
        this.type.tooltip(PhysicsKeys.BODY_TYPE_TOOLTIP);
        this.typeRow = PhysicsUI.labelRow(PhysicsKeys.BODY_TYPE, this.type);

        this.mass = new UITrackpad((v) -> this.editBody((body) -> body.withMass(v.floatValue())));
        this.mass.limit(0.01D, 10000D).increment(1D);
        this.mass.tooltip(PhysicsKeys.BODY_MASS_TOOLTIP);

        /* The material estimate is a one-off calculation rather than a property, so it belongs on
         * the number it fills in — not on a row of its own reading like a sentence. */
        UIIcon material = new UIIcon(Icons.MORE, (b) -> this.openMaterialMenu());

        material.tooltip(PhysicsKeys.MATERIAL_TOOLTIP);

        UIElement massGroup = new UIElement();

        massGroup.h(UIConstants.CONTROL_HEIGHT);
        this.mass.relative(massGroup).w(1F, -16 - UIConstants.MARGIN).h(UIConstants.CONTROL_HEIGHT);
        material.relative(massGroup).x(1F).y(0.5F).wh(16, 16).anchor(1F, 0.5F);
        massGroup.add(this.mass, material);

        this.massRow = PhysicsUI.labelRow(PhysicsKeys.MASS, PhysicsUI.VALUE_WIDTH, massGroup);

        this.friction = new UITrackpad((v) -> this.editBody((body) -> body.withFriction(v.floatValue())));
        this.friction.limit(0D, 1D).increment(0.05D);
        this.frictionRow = PhysicsUI.labelRow(PhysicsKeys.FRICTION, this.friction);

        this.restitution = new UITrackpad((v) -> this.editBody((body) -> body.withRestitution(v.floatValue())));
        this.restitution.limit(0D, 1D).increment(0.05D);
        this.restitutionRow = PhysicsUI.labelRow(PhysicsKeys.RESTITUTION, this.restitution);

        this.linearDamping = new UITrackpad((v) -> this.editBody((body) -> body.withLinearDamping(v.floatValue())));
        this.linearDamping.limit(0D, 1D).increment(0.01D).values(0.01D, 0.001D, 0.05D);
        this.linearDamping.tooltip(PhysicsKeys.BODY_LINEAR_DAMPING_TOOLTIP);
        this.angularDamping = new UITrackpad((v) -> this.editBody((body) -> body.withAngularDamping(v.floatValue())));
        this.angularDamping.limit(0D, 1D).increment(0.01D).values(0.01D, 0.001D, 0.05D);
        this.angularDamping.tooltip(PhysicsKeys.BODY_ANGULAR_DAMPING_TOOLTIP);
        this.dampingRow = UI.column(UIConstants.MARGIN, 0, UI.label(PhysicsKeys.BODY_DAMPING), UI.row(this.linearDamping, this.angularDamping));

        this.gravity = new UITrackpad((v) -> this.editBody((body) -> body.withGravity(v.floatValue())));
        this.gravity.limit(-2D, 2D).increment(0.05D);
        this.gravity.tooltip(PhysicsKeys.BODY_GRAVITY_TOOLTIP);
        this.gravityRow = PhysicsUI.labelRow(PhysicsKeys.BODY_GRAVITY, this.gravity);

        this.asleep = new UIToggle(PhysicsKeys.BODY_ASLEEP, (b) -> this.editBody((body) -> body.withAsleep(b.getValue())));
        this.asleep.tooltip(PhysicsKeys.BODY_ASLEEP_TOOLTIP);

        /* One tick per axis, two rows: what the body may not do. A frozen axis is a door, a
         * wheel, a swing — without a joint. */
        IKey[] axes = {IKey.constant("X"), IKey.constant("Y"), IKey.constant("Z")};
        int[] bits = {FormBody.AXIS_X, FormBody.AXIS_Y, FormBody.AXIS_Z};

        for (int i = 0; i < 3; i++)
        {
            int bit = bits[i];

            this.lockMove[i] = new UIToggle(axes[i], (b) -> this.editBody((body) -> body.withLockMove(FormBody.toggle(body.lockMove(), bit, b.getValue()))));
            this.lockSpin[i] = new UIToggle(axes[i], (b) -> this.editBody((body) -> body.withLockSpin(FormBody.toggle(body.lockSpin(), bit, b.getValue()))));
        }

        this.lockMoveRow = PhysicsUI.labelRow(PhysicsKeys.BODY_LOCK_MOVE, UI.row(this.lockMove[0], this.lockMove[1], this.lockMove[2]));
        this.lockMoveRow.tooltip(PhysicsKeys.BODY_LOCK_TOOLTIP);
        this.lockSpinRow = PhysicsUI.labelRow(PhysicsKeys.BODY_LOCK_SPIN, UI.row(this.lockSpin[0], this.lockSpin[1], this.lockSpin[2]));
        this.lockSpinRow.tooltip(PhysicsKeys.BODY_LOCK_TOOLTIP);

        this.ragdollMass = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withMass(v.floatValue())));
        this.ragdollMass.limit(0D, 10000D).increment(1D);
        this.ragdollMass.tooltip(PhysicsKeys.RAGDOLL_MASS_TOOLTIP);
        this.ragdollMassRow = PhysicsUI.labelRow(PhysicsKeys.RAGDOLL_MASS, this.ragdollMass);

        this.ragdollDamping = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withDamping(v.floatValue())));
        this.ragdollDamping.limit(0D, 1D).increment(0.05D);
        this.ragdollDamping.tooltip(PhysicsKeys.RAGDOLL_DAMPING_TOOLTIP);
        this.ragdollDampingRow = PhysicsUI.labelRow(PhysicsKeys.RAGDOLL_DAMPING, this.ragdollDamping);

        this.ragdollFriction = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withFriction(v.floatValue())));
        this.ragdollFriction.limit(0D, 100D).increment(0.5D);
        this.ragdollFriction.tooltip(PhysicsKeys.RAGDOLL_FRICTION_TOOLTIP);
        this.ragdollFrictionRow = PhysicsUI.labelRow(PhysicsKeys.RAGDOLL_FRICTION, this.ragdollFriction);

        this.ragdollGravity = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withGravity(v.floatValue())));
        this.ragdollGravity.limit(-2D, 2D).increment(0.05D);
        this.ragdollGravity.tooltip(PhysicsKeys.BODY_GRAVITY_TOOLTIP);
        this.ragdollGravityRow = PhysicsUI.labelRow(PhysicsKeys.BODY_GRAVITY, this.ragdollGravity);

        this.muscles = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withMuscles(v.floatValue())));
        this.muscles.limit(0D, 1D).increment(0.05D);
        this.muscles.tooltip(PhysicsKeys.RAGDOLL_MUSCLES_TOOLTIP);
        this.muscleDamping = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withMuscleDamping(v.floatValue())));
        this.muscleDamping.limit(0D, 1D).increment(0.05D);
        this.muscleDamping.tooltip(PhysicsKeys.RAGDOLL_MUSCLE_DAMPING_TOOLTIP);
        this.musclesRow = UI.column(UIConstants.MARGIN, 0, UI.label(PhysicsKeys.RAGDOLL_MUSCLES), UI.row(this.muscles, this.muscleDamping));

        this.ragdollSelfCollide = new UIToggle(PhysicsKeys.RAGDOLL_SELF_COLLIDE, (b) -> this.editRagdoll((ragdoll) -> ragdoll.withSelfCollide(b.getValue())));
        this.ragdollSelfCollide.tooltip(PhysicsKeys.RAGDOLL_SELF_COLLIDE_TOOLTIP);

        this.bodyAuthority = PhysicsFields.authority(this::setAuthority);
        this.bodyAuthorityRow = PhysicsUI.labelRow(PhysicsKeys.AUTHORITY, this.bodyAuthority);
        this.ragdollAuthority = PhysicsFields.authority(this::setAuthority);
        this.ragdollAuthorityRow = PhysicsUI.labelRow(PhysicsKeys.AUTHORITY, this.ragdollAuthority);

        this.ragdollBones = new UIRagdollSection(() -> this.options.resize());

        this.chainAuthority = PhysicsFields.authority(this::setAuthority);
        this.chainAuthorityRow = PhysicsUI.labelRow(PhysicsKeys.AUTHORITY, this.chainAuthority);
        this.chainBones = new UIChainSection(() -> this.options.resize());

    }

    /* Editing */

    private void openTypeMenu()
    {
        if (this.form == null)
        {
            return;
        }

        this.getContext().replaceContextMenu((menu) ->
        {
            menu.action(Icons.CLOSE, PhysicsKeys.PHYSICS_NONE, () -> this.selectType(PhysicsType.NONE));
            menu.action(Icons.BLOCK, PhysicsKeys.BODY_TITLE, () -> this.selectType(PhysicsType.BODY));


            if (this.form instanceof ModelForm)
            {
                menu.action(Icons.LIMB, PhysicsKeys.RAGDOLL_TITLE, () -> this.selectType(PhysicsType.RAGDOLL));
                menu.action(Icons.CURVES, PhysicsKeys.PHYSICS_CHAINS, () -> this.selectType(PhysicsType.CHAIN));
            }
        });
    }

    private void selectType(PhysicsType type)
    {
        PhysicsForms.setType(this.form, type);
        this.sync();
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

    private void editRagdoll(UnaryOperator<FormRagdoll> edit)
    {
        if (this.syncing || this.form == null)
        {
            return;
        }

        FormRagdolls.set(this.form, edit.apply(FormRagdolls.get(this.form)));
    }

    /* Syncing the UI */

    @Override
    public void startEdit(Form form)
    {
        super.startEdit(form);

        this.ragdollBones.setForm(form);
        this.chainBones.setForm(form);
        this.sync();
    }

    /**
     * A body part clicked in the viewport should land in the bone list on screen, instead of
     * bouncing the author into the pose editor.
     */
    public boolean pickBoneInList(String bone)
    {
        if (this.form == null)
        {
            return false;
        }

        if (FormRagdolls.isEnabled(this.form) && this.ragdollBones.pickBoneInList(bone))
        {
            return true;
        }

        return FormChains.isEnabled(this.form) && this.chainBones.pickBoneInList(bone);
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
        boolean chain = FormChains.isEnabled(this.form);
        float authority = PhysicsForms.getAuthority(this.form);

        this.physicsType.label = switch (PhysicsForms.getType(this.form))
        {
            case NONE -> PhysicsKeys.PHYSICS_NONE;
            case BODY -> PhysicsKeys.BODY_TITLE;
            case RAGDOLL -> PhysicsKeys.RAGDOLL_TITLE;
            case CHAIN -> PhysicsKeys.PHYSICS_CHAINS;
        };
        this.type.setValue(body.passive() ? 1 : 0);
        this.mass.setValue(body.mass());
        this.friction.setValue(body.friction());
        this.restitution.setValue(body.restitution());
        this.linearDamping.setValue(body.linearDamping());
        this.angularDamping.setValue(body.angularDamping());
        this.gravity.setValue(body.gravity());
        this.asleep.setValue(body.asleep());

        int[] bits = {FormBody.AXIS_X, FormBody.AXIS_Y, FormBody.AXIS_Z};

        for (int i = 0; i < 3; i++)
        {
            this.lockMove[i].setValue((body.lockMove() & bits[i]) != 0);
            this.lockSpin[i].setValue((body.lockSpin() & bits[i]) != 0);
        }

        FormRagdoll ragdollConfig = FormRagdolls.get(this.form);

        this.ragdollMass.setValue(ragdollConfig.mass());
        this.ragdollDamping.setValue(ragdollConfig.damping());
        this.ragdollFriction.setValue(ragdollConfig.friction());
        this.ragdollGravity.setValue(ragdollConfig.gravity());
        this.muscles.setValue(ragdollConfig.muscles());
        this.muscleDamping.setValue(ragdollConfig.muscleDamping());
        this.ragdollSelfCollide.setValue(ragdollConfig.selfCollide());
        this.bodyAuthority.setValue(authority);
        this.ragdollAuthority.setValue(authority);
        this.chainAuthority.setValue(authority);

        if (ragdoll)
        {
            this.ragdollBones.setForm(this.form);
        }

        if (chain)
        {
            this.chainBones.setForm(this.form);
        }

        this.rebuild(body.enabled(), ragdoll, chain);

        this.syncing = false;
    }

    private void rebuild(boolean body, boolean ragdoll, boolean chain)
    {
        this.options.removeAll();
        this.options.add(this.physicsTypeRow);


        if (body)
        {
            this.options.add(PhysicsFields.section(PhysicsKeys.SECTION_MOTION, "physics.body.motion",
                this.typeRow, this.massRow, this.gravityRow, this.dampingRow, this.asleep, this.bodyAuthorityRow));
            this.options.add(this.frictionRow, this.restitutionRow);
            this.options.add(PhysicsFields.section(PhysicsKeys.SECTION_LIMITS, "physics.body.limits",
                this.lockMoveRow, this.lockSpinRow));
        }
        else if (ragdoll)
        {
            this.options.add(PhysicsFields.section(PhysicsKeys.SECTION_MOTION, "physics.ragdoll.motion",
                this.ragdollAuthorityRow, this.ragdollMassRow, this.ragdollDampingRow,
                this.ragdollGravityRow, this.musclesRow));
            this.options.add(this.ragdollFrictionRow, this.ragdollSelfCollide);
            this.options.add(this.ragdollBones);
        }
        else if (chain)
        {
            this.options.add(PhysicsFields.section(PhysicsKeys.SECTION_MOTION, "physics.chain.motion", this.chainAuthorityRow), this.chainBones);
        }


        this.options.resize();
    }

    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }
}
