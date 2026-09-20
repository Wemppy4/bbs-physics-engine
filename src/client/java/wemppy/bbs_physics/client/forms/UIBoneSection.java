package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;
import wemppy.bbs_physics.collision.CollisionMode;
import wemppy.bbs_physics.collision.FormCollision;
import wemppy.bbs_physics.collision.FormCollisions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared bone selection and properties for ragdolls and chains.
 * The list only selects bones; the enable property edits the selected group.
 * Numeric callbacks must not rebuild the layout during rendering.
 */
public abstract class UIBoneSection extends UIElement
{
    public final PhysicsBoneList bones;
    public final UISearchList<String> bonesSearch;
    public final UIToggle enabled;

    /**
     * Told when the rows change, so the column outside can lay itself out again. This block grows
     * and shrinks with what is selected, and the scroll view above it has no way of noticing.
     */
    private final Runnable relayout;

    protected Form form;
    protected ModelInstance model;

    /**
     * The first of the selected bones — the one whose values the knobs show — or empty when there
     * is no model to stand on one. What an edit is <em>written</em> to is {@link #targets}.
     */
    protected String bone = "";

    /**
     * The form's collision markup as of this frame — see the class note on why it is not read per
     * row. Refreshed by {@link #render}, so an edit made in the Collision tab shows up here on the
     * next frame without anything having to tell this panel about it.
     */
    protected FormCollision collision = FormCollision.EMPTY;

    /**
     * True while the panel is writing values into its own widgets, so their callbacks know not to
     * write them back into the form.
     */
    protected boolean syncing;

    protected UIBoneSection(Runnable relayout)
    {
        this.relayout = relayout;

        this.column(UIConstants.MARGIN).vertical().stretch();

        this.bones = new UIPhysicsBoneList((l) ->
        {
            if (this.model != null)
            {
                /* Ctrl-clicking the last selected row leaves nothing selected, and that is an
                 * honest state to be in: the knobs go away rather than describing a bone the
                 * author cannot see highlighted. */
                this.bone = l.isEmpty() ? "" : l.get(0);

                PickedBone.set(this.bone);
            }

            this.onBonePicked();
        })
        {
            @Override
            public void renderListElement(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected)
            {
                super.renderListElement(context, element, i, x, y, hover, selected);

                UIBoneSection.this.renderMargin(context, element, y);
            }

        };
        this.bones.background();

        this.bonesSearch = new UISearchList<>(this.bones);
        this.bonesSearch.label(UIKeys.GENERAL_SEARCH);
        this.bonesSearch.h(20 + UIConstants.LIST_ITEM_HEIGHT * 8);

        this.enabled = new UIToggle(PhysicsKeys.BONES_ENABLED, (toggle) ->
        {
            if (this.syncing || this.form == null || this.model == null)
            {
                return;
            }

            List<String> targets = this.editableTargets();

            if (!targets.isEmpty())
            {
                this.setParticipation(targets, toggle.getValue());
            }

            this.syncEnabled();
        });
        this.enabled.tooltip(PhysicsKeys.BONES_ENABLED_TOOLTIP);
    }

    /* What a subclass fills in */

    /**
     * Takes every one of {@code bones} into the modifier, or leaves them all out. Handed the whole
     * run at once rather than one bone at a time, so a modifier writes itself back onto the form —
     * and lays its panel out again — once per click instead of once per bone.
     */
    protected abstract void setParticipation(List<String> bones, boolean ticked);

    /** Whether physics includes this bone. */
    protected abstract boolean isParticipating(String bone);

    /** Whether this bone can participate; ragdolls require collision markup. */
    protected boolean canParticipate(String bone)
    {
        return true;
    }

    /** The author moved to another bone in the list. */
    protected abstract void onBonePicked();

    /** Whether this section shows collision indicators, as the ragdoll does. */
    protected boolean showsMarkup()
    {
        return false;
    }

    /* Shared behaviour */

    /**
     * Points this section at a form: its modifier, its model, and its bones.
     *
     * <p>Subclasses read their own modifier off the form first and then call this.</p>
     *
     * @param pick whether to restore the selected bone or select the first available one
     */
    protected void setForm(Form form, boolean pick)
    {
        this.form = form;
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;
        this.collision = FormCollisions.get(form);

        if (this.model == null || this.model.model == null)
        {
            this.model = null;
            this.bone = "";

            return;
        }

        /* CML has no notion of a bone being disabled on the instance, so nothing is hidden. */
        this.bones.fillBones(this.model.model, null);
        this.bones.filter(this.bonesSearch.search.getText());

        /* Filling the list drops its selection, so whatever bone was showing is no longer standing
         * on anything — and a name left over from the previous model is a bone this one may not
         * even have. The pick below puts a real one back. */
        this.bone = "";

        if (pick && !this.pickBoneInList(PickedBone.get()) && !this.bones.getList().isEmpty())
        {
            this.bone = this.bones.getList().get(0);
            this.bones.setCurrentScroll(this.bone);
        }
    }

    /**
     * The bones an edit lands on: everything selected in the list, the one showing in the knobs
     * first. Snapshotted because {@code getCurrent} hands back a buffer it reuses — the same guard
     * BBS's own pose editor takes, and for the same reason: an edit can re-enter the list.
     */
    protected List<String> targets()
    {
        List<String> targets = new ArrayList<>(this.bones.getCurrent());

        /* Nothing highlighted, but a bone is showing — the panel was pointed at one from outside
         * (a click in the viewport, a form just opened). That bone is the edit. */
        if (targets.isEmpty() && !this.bone.isEmpty())
        {
            targets.add(this.bone);
        }

        return targets;
    }

    private List<String> editableTargets()
    {
        List<String> targets = this.targets();

        targets.removeIf((bone) -> !this.canParticipate(bone));

        return targets;
    }

    /** Mixed selections display their disagreement; the first click enables the whole group. */
    protected void syncEnabled()
    {
        if (this.enabled == null)
        {
            return;
        }

        List<String> targets = this.editableTargets();
        boolean any = targets.stream().anyMatch(this::isParticipating);
        boolean all = !targets.isEmpty() && targets.stream().allMatch(this::isParticipating);
        boolean previous = this.syncing;

        this.syncing = true;

        try
        {
            this.enabled.setValue(all);
            this.enabled.setEnabled(this.form != null && this.model != null && !targets.isEmpty());
            this.enabled.label = any && !all ? PhysicsKeys.BONES_ENABLED_MIXED : PhysicsKeys.BONES_ENABLED;
        }
        finally
        {
            this.syncing = previous;
        }
    }

    /**
     * A body part clicked in the viewport should land on the bone list on screen, instead of
     * bouncing the author into the pose editor.
     *
     * @return whether this section has that bone to show
     */
    public boolean pickBoneInList(String bone)
    {
        if (this.model == null || bone == null || bone.isEmpty() || !this.bones.getList().contains(bone))
        {
            return false;
        }

        this.bone = bone;

        PickedBone.set(bone);
        this.bones.setCurrentScroll(bone);
        this.onBonePicked();

        return true;
    }

    /** Whether the Collision tab gave {@code bone} a shape at all. */
    protected boolean isMarked(String bone)
    {
        return this.collision.get(bone).mode() != CollisionMode.NONE;
    }

    /** Lays the outside column out again — call after changing which rows exist. */
    protected void relayout()
    {
        this.relayout.run();
    }

    /**
     * A knob that writes one number into the modifier.
     *
     * <p>Writes and stops, deliberately — see the class note: a trackpad's callback arrives inside
     * the render pass, and changing the layout from there takes the game down.</p>
     */
    protected UITrackpad knob(double min, double max, IKey tooltip, Consumer<Float> edit)
    {
        UITrackpad pad = new UITrackpad((v) ->
        {
            if (!this.syncing)
            {
                edit.accept(v.floatValue());
            }
        });

        pad.limit(min, max).increment(0.05D);
        pad.tooltip(tooltip);

        return pad;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.form != null)
        {
            /* Once a frame rather than once a row — see the class note. */
            this.collision = FormCollisions.get(this.form);
        }

        this.syncEnabled();
        super.render(context);
    }

    /** Collision information is an indicator, never an inline enable button. */
    private void renderMargin(UIContext context, String element, int y)
    {
        if (this.model == null || this.form == null || !this.showsMarkup())
        {
            return;
        }

        CollisionMode mode = this.collision.get(element).mode();

        if (mode != CollisionMode.NONE)
        {
            int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2;
            int dot = this.bones.area.ex() - 8;

            context.batcher.box(dot, mid - 2, dot + 4, mid + 2, Colors.A100 | PhysicsColors.markup(mode));
        }
    }
}
