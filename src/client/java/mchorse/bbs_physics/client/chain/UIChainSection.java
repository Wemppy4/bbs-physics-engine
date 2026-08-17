package mchorse.bbs_physics.client.chain;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.utils.PickedBone;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.chain.FormChain;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.client.collision.ChainBones;
import mchorse.bbs_physics.client.forms.PhysicsKeys;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The chain modifier's body: which bones hang, and what the strands are like.
 *
 * <p>Ticks in a bone list, deliberately the same gesture the ragdoll uses — an author who has
 * ticked "these bones fall" already knows how to tick "these bones hang". Unlike the ragdoll, a
 * bone needs no collision markup to be ticked: hair has no shape anyone marked up, and the strand
 * brings a capsule of its own sized by the thickness knob.</p>
 *
 * <p><b>"Take the chains from the model" is an import, not a link.</b> BBS's own chain physics
 * already lists the strands of a model — from bone X down to bone Y — and that list is the obvious
 * starting point. It is copied in rather than followed live, because from the moment a bone is
 * ticked here the old solver is silenced on it ({@link ChainMute}): its own stiffness and gravity
 * would still be sitting in the model, doing nothing, and an author turning those knobs would be
 * turning knobs that no longer reach anything. Copied in, ownership is plain — the bones are ours,
 * the knobs are the ones on this panel.</p>
 */
public class UIChainSection extends UIElement
{
    /** How far from the list's right edge the tick sits — clear of the scrollbar. */
    private static final int TICK_RIGHT = 20;
    private static final int TICK_SIZE = 7;

    public UIBoneTreeList bones;
    public UISearchList<String> bonesSearch;

    public UIButton takeFromModel;
    public UIButton clear;

    public UITrackpad stiffness;
    public UITrackpad damping;
    public UITrackpad radius;
    public UITrackpad mass;
    public UITrackpad gravity;
    public UIToggle selfCollision;

    private final Runnable relayout;

    private Form form;
    private FormChain chain = FormChain.EMPTY;
    private ModelInstance model;
    private boolean syncing;

    public UIChainSection(Runnable relayout)
    {
        this.relayout = relayout;

        this.column(UIConstants.MARGIN).vertical().stretch();

        this.bones = new UIBoneTreeList((l) ->
        {
            if (this.model != null && !l.isEmpty())
            {
                PickedBone.set(l.get(0));
            }
        })
        {
            @Override
            public void renderListElement(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected)
            {
                super.renderListElement(context, element, i, x, y, hover, selected);

                UIChainSection.this.renderTick(context, element, y);
            }

            @Override
            public boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context)
                    && context.mouseX >= this.area.ex() - TICK_RIGHT - TICK_SIZE
                    && context.mouseX <= this.area.ex() - TICK_RIGHT + TICK_SIZE)
                {
                    int index = this.getIndexAtCursor(context);

                    if (index >= 0 && index < this.getList().size())
                    {
                        UIChainSection.this.toggleBone(this.getList().get(index));

                        return true;
                    }
                }

                return super.subMouseClicked(context);
            }
        };
        this.bones.background();
        this.bonesSearch = new UISearchList<>(this.bones);
        this.bonesSearch.label(UIKeys.GENERAL_SEARCH);
        this.bonesSearch.h(20 + UIConstants.LIST_ITEM_HEIGHT * 8);

        this.takeFromModel = new UIButton(PhysicsKeys.CHAIN_TAKE_FROM_MODEL, (b) -> this.takeFromModel());
        this.takeFromModel.tooltip(PhysicsKeys.CHAIN_TAKE_FROM_MODEL_TOOLTIP);

        this.clear = new UIButton(PhysicsKeys.CHAIN_CLEAR, (b) -> this.edit((chain) -> chain.withBones(Set.of())));

        this.stiffness = this.knob(0D, 1D, PhysicsKeys.CHAIN_STIFFNESS, (chain, v) -> chain.withStiffness(v));
        this.damping = this.knob(0D, 1D, PhysicsKeys.CHAIN_DAMPING, (chain, v) -> chain.withDamping(v));
        this.radius = this.knob(0.01D, 0.5D, PhysicsKeys.CHAIN_BONE_RADIUS, (chain, v) -> chain.withRadius(v));
        this.mass = this.knob(0.01D, 100D, PhysicsKeys.CHAIN_MASS, (chain, v) -> chain.withMass(v));
        this.gravity = this.knob(-2D, 2D, PhysicsKeys.BALLOON_GRAVITY, (chain, v) -> chain.withGravity(v));

        this.selfCollision = new UIToggle(PhysicsKeys.CHAIN_SELF_COLLISION, false, (b) ->
        {
            if (!this.syncing)
            {
                this.edit((chain) -> chain.withSelfCollision(b.getValue()));
            }
        });
        this.selfCollision.tooltip(PhysicsKeys.CHAIN_SELF_COLLISION_TOOLTIP);
    }

    private UITrackpad knob(double min, double max, mchorse.bbs_mod.l10n.keys.IKey tooltip, ChainEdit edit)
    {
        UITrackpad pad = new UITrackpad((v) ->
        {
            if (!this.syncing)
            {
                this.edit((chain) -> edit.apply(chain, v.floatValue()));
            }
        });

        pad.limit(min, max).increment(0.05D);
        pad.tooltip(tooltip);

        return pad;
    }

    /* Editing */

    private void toggleBone(String bone)
    {
        this.edit((chain) -> chain.withBone(bone, !chain.bones().contains(bone)));
    }

    /**
     * Copies the strands BBS's own chain physics lists for this model — every bone from each
     * chain's start down to its end, which {@link ChainBones} already walks for the collision pass.
     * Added to what is ticked rather than replacing it, so an author can import and then keep
     * ticking.
     */
    private void takeFromModel()
    {
        if (this.form == null || this.model == null || !(this.model.model instanceof Model cubic))
        {
            return;
        }

        Set<String> bones = new LinkedHashSet<>(this.chain.bones());

        bones.addAll(ChainBones.of(this.form, cubic));

        this.edit((chain) -> chain.withBones(bones));
    }

    private void edit(UnaryOperator<FormChain> edit)
    {
        if (this.form == null)
        {
            return;
        }

        this.chain = edit.apply(this.chain);

        FormChains.set(this.form, this.chain);
        this.updateLabels();
    }

    /* Syncing the UI */

    public void setForm(Form form)
    {
        this.form = form;
        this.chain = FormChains.get(form);
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;

        if (this.model != null && this.model.model != null)
        {
            this.bones.fillBones(this.model.model, this.model.getDisabledBones());
            this.bones.filter(this.bonesSearch.search.getText());
        }
        else
        {
            this.model = null;
        }

        this.updateLabels();
    }

    public boolean pickBoneInList(String bone)
    {
        if (this.model == null || bone == null || bone.isEmpty() || !this.bones.getList().contains(bone))
        {
            return false;
        }

        PickedBone.set(bone);
        this.bones.setCurrentScroll(bone);

        return true;
    }

    private void updateLabels()
    {
        if (this.stiffness == null)
        {
            return;
        }

        this.syncing = true;

        try
        {
            this.stiffness.setValue(this.chain.stiffness());
            this.damping.setValue(this.chain.damping());
            this.radius.setValue(this.chain.radius());
            this.mass.setValue(this.chain.mass());
            this.gravity.setValue(this.chain.gravity());
            this.selfCollision.setValue(this.chain.selfCollision());
        }
        finally
        {
            this.syncing = false;
        }

        this.rebuild();
    }

    /** The knobs only appear once there is a strand for them to describe. */
    private void rebuild()
    {
        this.removeAll();
        this.add(this.bonesSearch, UI.row(this.takeFromModel, this.clear));

        if (!this.chain.bones().isEmpty())
        {
            this.add(this.stiffness, this.damping);
            this.add(UI.row(this.radius, this.mass));
            this.add(this.gravity, this.selfCollision);
        }

        this.relayout.run();
    }

    /** The tick that says this bone hangs. */
    private void renderTick(UIContext context, String element, int y)
    {
        if (this.model == null || this.form == null)
        {
            return;
        }

        int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2;
        int x = this.bones.area.ex() - TICK_RIGHT - TICK_SIZE / 2;
        int top = mid - TICK_SIZE / 2;

        context.batcher.box(x, top, x + TICK_SIZE, top + TICK_SIZE, Colors.A50);

        if (this.chain.bones().contains(element))
        {
            context.batcher.box(x + 1, top + 1, x + TICK_SIZE - 1, top + TICK_SIZE - 1, Colors.A100 | Colors.WHITE);
        }
    }

    /** One edit of the modifier by a single number. */
    private interface ChainEdit
    {
        FormChain apply(FormChain chain, float value);
    }
}
