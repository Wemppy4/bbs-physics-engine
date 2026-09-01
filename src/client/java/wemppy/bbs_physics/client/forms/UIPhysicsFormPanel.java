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
import wemppy.bbs_physics.client.scene.PhysicsBake;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import wemppy.bbs_physics.chain.FormChain;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.client.chain.UIChainSection;
import wemppy.bbs_physics.client.ragdoll.UIRagdollSection;
import wemppy.bbs_physics.collision.FormCollisions;
import wemppy.bbs_physics.forms.FormBody;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.ragdoll.FormRagdoll;
import wemppy.bbs_physics.ragdoll.FormRagdolls;

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

    /** Blender's "Bake to Keyframes": the recording becomes ordinary keys, see {@link PhysicsBake}. */
    private final UIButton bake;

    private final UIModifierSection bodySection;
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

    private final UIModifierSection ragdollSection;
    private final UIRagdollSection ragdollBones;

    private final UIModifierSection chainSection;
    private final UIChainSection chainBones;
    private final UITrackpad chainAuthority;
    private final UIElement chainAuthorityRow;

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
        this.bake = new UIButton(PhysicsKeys.BAKE, (b) -> this.confirmBake());
        this.bake.tooltip(PhysicsKeys.BAKE_TOOLTIP);

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
        this.gravityRow = UI.labelRow(PhysicsKeys.BODY_GRAVITY, this.gravity);

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

        this.lockMoveRow = UI.labelRow(PhysicsKeys.BODY_LOCK_MOVE, UI.row(this.lockMove[0], this.lockMove[1], this.lockMove[2]));
        this.lockMoveRow.tooltip(PhysicsKeys.BODY_LOCK_TOOLTIP);
        this.lockSpinRow = UI.labelRow(PhysicsKeys.BODY_LOCK_SPIN, UI.row(this.lockSpin[0], this.lockSpin[1], this.lockSpin[2]));
        this.lockSpinRow.tooltip(PhysicsKeys.BODY_LOCK_TOOLTIP);

        this.ragdollMass = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withMass(v.floatValue())));
        this.ragdollMass.limit(0D, 10000D).increment(1D);
        this.ragdollMass.tooltip(PhysicsKeys.RAGDOLL_MASS_TOOLTIP);
        this.ragdollMassRow = UI.labelRow(PhysicsKeys.RAGDOLL_MASS, this.ragdollMass);

        this.ragdollDamping = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withDamping(v.floatValue())));
        this.ragdollDamping.limit(0D, 1D).increment(0.05D);
        this.ragdollDamping.tooltip(PhysicsKeys.RAGDOLL_DAMPING_TOOLTIP);
        this.ragdollDampingRow = UI.labelRow(PhysicsKeys.RAGDOLL_DAMPING, this.ragdollDamping);

        this.ragdollFriction = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withFriction(v.floatValue())));
        this.ragdollFriction.limit(0D, 100D).increment(0.5D);
        this.ragdollFriction.tooltip(PhysicsKeys.RAGDOLL_FRICTION_TOOLTIP);
        this.ragdollFrictionRow = UI.labelRow(PhysicsKeys.RAGDOLL_FRICTION, this.ragdollFriction);

        this.ragdollGravity = new UITrackpad((v) -> this.editRagdoll((ragdoll) -> ragdoll.withGravity(v.floatValue())));
        this.ragdollGravity.limit(-2D, 2D).increment(0.05D);
        this.ragdollGravity.tooltip(PhysicsKeys.BODY_GRAVITY_TOOLTIP);
        this.ragdollGravityRow = UI.labelRow(PhysicsKeys.BODY_GRAVITY, this.ragdollGravity);

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
        this.bodyAuthorityRow = UI.labelRow(PhysicsKeys.AUTHORITY, this.bodyAuthority);
        this.ragdollAuthority = PhysicsFields.authority(this::setAuthority);
        this.ragdollAuthorityRow = UI.labelRow(PhysicsKeys.AUTHORITY, this.ragdollAuthority);

        this.bodySection = new UIModifierSection(PhysicsKeys.BODY_TITLE, "physics.body", () -> this.toggleBody(false));
        this.bodySection.fields.add(this.typeRow, this.massRow, this.frictionRow, this.restitutionRow, this.dampingRow, this.gravityRow, this.lockMoveRow, this.lockSpinRow, this.asleep, this.bodyAuthorityRow);

        this.ragdollBones = new UIRagdollSection(() -> this.options.resize());
        this.ragdollSection = new UIModifierSection(PhysicsKeys.RAGDOLL_TITLE, "physics.ragdoll", () -> this.toggleRagdoll(false));
        this.ragdollSection.fields.add(this.ragdollAuthorityRow, this.ragdollMassRow, this.ragdollDampingRow, this.ragdollFrictionRow, this.ragdollGravityRow, this.musclesRow, this.ragdollSelfCollide, this.ragdollBones);

        this.chainAuthority = PhysicsFields.authority(this::setAuthority);
        this.chainAuthorityRow = UI.labelRow(PhysicsKeys.AUTHORITY, this.chainAuthority);
        this.chainBones = new UIChainSection(() -> this.options.resize());
        this.chainSection = new UIModifierSection(PhysicsKeys.CHAIN_MODIFIER_TITLE, "physics.chain", () -> this.toggleChain(false));
        this.chainSection.fields.add(this.chainAuthorityRow, this.chainBones);

        /* Wrapped rather than a one-line label: the column is narrow, and the sentence that has to
         * be read here is the one a single line cuts in half. */
        this.unmarked = new UIText(PhysicsKeys.PHYSICS_UNMARKED).color(Colors.LIGHTER_GRAY, true).padding(0, 2);
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
                menu.action(Icons.CURVES, PhysicsKeys.PHYSICS_ADD_CHAIN, () -> this.toggleChain(true));
            }

            /* Two things are deliberately not on this menu. Cloth became a form of its own (Р12),
             * picked from the palette's Physics section like any other form. And there is no
             * "obstacle": a form the animation moves is already solid the moment it is marked up in
             * the Collision tab — its markup becomes kinematic bodies whether or not it carries a
             * modifier — so a button for it would only promise what is already the case. */
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

    /**
     * Adds or removes the chain modifier.
     *
     * <p>Adding one also drops the handle to 0, and that is not a liberty: hair is not a thing an
     * author "releases" the way a crate is — it hangs, always — so a strand left at the resting 1
     * would be a modifier that visibly does nothing until the author finds an unrelated slider.
     * The chain form does the same on the palette entry it is copied from, for the same reason.
     * Only when nothing else on the form uses the handle, though: the ragdoll shares it (§4), and
     * dropping it there would floor the character on the spot.</p>
     */
    private void toggleChain(boolean add)
    {
        if (!(this.form instanceof ModelForm))
        {
            return;
        }

        FormChains.set(this.form, add ? FormChain.added() : FormChain.EMPTY);

        if (add && !FormRagdolls.isEnabled(this.form) && !PhysicsForms.getBody(this.form).enabled())
        {
            PhysicsForms.setAuthority(this.form, 0F);
        }

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
    @Override
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

    /**
     * Puts the tab together out of the modifiers this form actually has.
     *
     * <p>Rebuilt rather than hidden, and that is not a style choice: {@code setVisible} in BBS stops
     * an element from drawing but leaves it holding its place in the column, so a tab that hid the
     * halves it did not need was a tab full of unexplained gaps.</p>
     */
    private void rebuild(boolean body, boolean ragdoll, boolean chain)
    {
        this.marked = FormCollisions.has(this.form);

        this.options.removeAll();

        /* First, above the modifier it blocks: a modifier with nothing to collide as does nothing
         * at all, and since Р11 nothing marks the form up on the author's behalf. Read before the
         * settings, it is an instruction; read under them, it is an epitaph.
         *
         * The chain modifier is deliberately not in this test: a strand brings a shape of its own
         * (a capsule sized by the thickness knob), so hair works on a model nobody marked up —
         * which is the common case, since nobody marks up a scalp. */
        if ((body || ragdoll) && !this.marked)
        {
            this.options.add(this.unmarked);
        }

        /* A form is a body or a ragdoll, never both: welding a model into one falling lump and
         * jointing its bones are two answers to the same question. The chain modifier is not in
         * that quarrel — it claims bones neither of them touches — so it may sit alongside either,
         * and the add button stays while it is the only one still available. */
        if (!body && !ragdoll || !chain && this.form instanceof ModelForm)
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

        if (chain)
        {
            this.options.add(this.chainSection);
        }

        /* Under every modifier, because it takes all of them at once: the form's recording is one
         * thing, whatever combination of body, ragdoll and hair produced it. */
        if (body || ragdoll || chain)
        {
            this.options.add(this.bake);
        }

        this.options.resize();
    }

    /* Baking */

    /**
     * Asks first. The bake is one step of the film's undo history, so it is not the point of no
     * return it is in Blender — but it overwrites every key on the tracks it touches, and an author
     * who clicked the wrong form would rather hear that before than after.
     */
    private void confirmBake()
    {
        if (this.form == null)
        {
            return;
        }

        UIOverlay.addOverlay(this.getContext(), new UIConfirmOverlayPanel(PhysicsKeys.BAKE_TITLE, PhysicsKeys.BAKE_CONFIRM, (confirmed) ->
        {
            if (confirmed)
            {
                this.bake();
            }
        }));
    }

    /**
     * Bakes this form's physics into the replay it is played from.
     *
     * <p>The form on this panel is the editor's working copy, not the replay's form and not the
     * actor the scene simulates, so the bake is addressed by the one thing all three share — the
     * form's path in the tree — and the replay is the one the film editor has selected, which is
     * the replay this editor was opened for. The keys go straight into the film; the handle on
     * this copy is set to 1 alongside, so that what the author sees here and what the film holds
     * agree, and so that finishing the edit keeps it.</p>
     */
    private void bake()
    {
        UIFilmPanel panel = this.getParent(UIFilmPanel.class);

        if (panel == null)
        {
            UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

            panel = dashboard == null ? null : dashboard.getPanel(UIFilmPanel.class);
        }

        Replay replay = panel == null || panel.replayEditor == null ? null : panel.replayEditor.getReplay();
        FilmScene scene = panel == null || panel.getController() == null ? null : FilmScenes.get(panel.getController().editorController);

        if (replay == null || scene == null)
        {
            this.message(PhysicsKeys.BAKE_NO_SCENE);

            return;
        }

        PhysicsBake.Result result;

        try
        {
            result = scene.bake(replay, FormUtils.getPath(this.form));
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.error("Baking the physics of '{}' into keyframes failed.", this.form.getDisplayName(), e);

            this.message(PhysicsKeys.BAKE_FAILED);

            return;
        }

        if (result == null)
        {
            this.message(PhysicsKeys.BAKE_NO_ACTOR);

            return;
        }

        PhysicsForms.setAuthority(this.form, 1F);
        this.sync();

        this.message(PhysicsKeys.BAKE_DONE.format(result.keys(), result.channels(), result.ticks()));
    }

    private void message(IKey message)
    {
        UIOverlay.addOverlay(this.getContext(), new UIMessageOverlayPanel(PhysicsKeys.BAKE_TITLE, message));
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
            this.rebuild(PhysicsForms.getBody(this.form).enabled(), FormRagdolls.isEnabled(this.form), FormChains.isEnabled(this.form));
        }

        super.render(context);
    }

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }
}
