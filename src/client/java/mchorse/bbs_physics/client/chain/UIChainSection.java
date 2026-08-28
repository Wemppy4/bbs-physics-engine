package mchorse.bbs_physics.client.chain;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.chain.FormChain;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.client.collision.ChainBones;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.client.forms.UIBoneSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The chain modifier's body: which bones hang, and what the strands are like.
 *
 * <p>Ticks in a bone list, deliberately the same gesture the ragdoll uses — an author who has ticked
 * "these bones fall" already knows how to tick "these bones hang". Unlike the ragdoll, an unmarked
 * bone can be ticked: a strand with no shape still hangs and swings, it simply meets nothing. Select
 * a run with Shift and one tick takes the whole run in, which is how a fringe of twenty hair bones
 * stops being twenty clicks.</p>
 *
 * <p>The knobs below describe the <em>modifier</em>, not a bone — one stiffness for all the strands
 * it owns — so unlike the ragdoll's they do not change with the selection. Hair that needs two
 * stiffnesses is two forms, the way it already is in BBS's own chain physics.</p>
 *
 * <p>🔴 <b>This modifier describes no shapes at all</b> — the Collision tab does. It briefly did the
 * opposite: a "thickness" knob here built a capsule per bone, which read as physics inventing
 * colliders on a model nobody had marked up (against Р6) and, worse, held the hair off the shoulders
 * it was meant to lie on, because the capsule was fatter than the strand it stood for.</p>
 *
 * <p><b>"Take the chains from the model" is an import, not a link.</b> BBS's own chain physics
 * already lists the strands of a model — from bone X down to bone Y — and that list is the obvious
 * starting point. It is copied in rather than followed live, because from the moment a bone is
 * ticked here the old solver is silenced on it ({@link ChainMute}): its own stiffness and gravity
 * would still be sitting in the model, doing nothing, and an author turning those knobs would be
 * turning knobs that no longer reach anything. Copied in, ownership is plain — the bones are ours,
 * the knobs are the ones on this panel.</p>
 */
public class UIChainSection extends UIBoneSection
{
    public UIButton takeFromModel;
    public UIButton clear;

    public UITrackpad stiffness;
    public UITrackpad damping;
    public UITrackpad mass;
    public UITrackpad gravity;
    public UITrackpad falloff;
    public UITrackpad bend;
    public UIToggle selfCollision;

    /** Says where a strand's shape comes from — see the class note on why it comes from there. */
    private final UIText shapeHint;

    private FormChain chain = FormChain.EMPTY;

    public UIChainSection(Runnable relayout)
    {
        super(relayout);

        this.takeFromModel = new UIButton(PhysicsKeys.CHAIN_TAKE_FROM_MODEL, (b) -> this.takeFromModel());
        this.takeFromModel.tooltip(PhysicsKeys.CHAIN_TAKE_FROM_MODEL_TOOLTIP);

        this.clear = new UIButton(PhysicsKeys.CHAIN_CLEAR, (b) -> this.editComposition((chain) -> chain.withBones(Set.of())));

        this.stiffness = this.knob(0D, 1D, PhysicsKeys.CHAIN_STIFFNESS, (v) -> this.edit((chain) -> chain.withStiffness(v)));
        this.damping = this.knob(0D, 1D, PhysicsKeys.CHAIN_DAMPING, (v) -> this.edit((chain) -> chain.withDamping(v)));
        this.mass = this.knob(0.01D, 100D, PhysicsKeys.CHAIN_MASS, (v) -> this.edit((chain) -> chain.withMass(v)));
        this.gravity = this.knob(-2D, 2D, PhysicsKeys.BALLOON_GRAVITY, (v) -> this.edit((chain) -> chain.withGravity(v)));
        this.falloff = this.knob(0D, 1D, PhysicsKeys.CHAIN_FALLOFF, (v) -> this.edit((chain) -> chain.withFalloff(v)));

        /* Degrees, so it steps in fives like the ragdoll's angles do. */
        this.bend = new UITrackpad((v) ->
        {
            if (!this.syncing)
            {
                this.edit((chain) -> chain.withBend(v.floatValue()));
            }
        });
        this.bend.limit(5D, 180D).increment(5D).values(1D, 0.5D, 5D);
        this.bend.tooltip(PhysicsKeys.CHAIN_BEND);

        this.selfCollision = new UIToggle(PhysicsKeys.CHAIN_SELF_COLLISION, false, (b) ->
        {
            if (!this.syncing)
            {
                this.edit((chain) -> chain.withSelfCollision(b.getValue()));
            }
        });
        this.selfCollision.tooltip(PhysicsKeys.CHAIN_SELF_COLLISION_TOOLTIP);

        this.shapeHint = new UIText(PhysicsKeys.CHAIN_SHAPE_HINT).color(Colors.LIGHTER_GRAY, true).padding(0, 2);
    }

    /* Editing */

    @Override
    protected void setTicked(List<String> bones, boolean ticked)
    {
        this.editComposition((chain) ->
        {
            FormChain edited = chain;

            for (String bone : bones)
            {
                edited = edited.withBone(bone, ticked);
            }

            return edited;
        });
    }

    @Override
    protected boolean isTicked(String bone)
    {
        return this.chain.bones().contains(bone);
    }

    /**
     * Copies the strands BBS's own chain physics lists for this model — every bone from each chain's
     * start down to its end, which {@link ChainBones} already walks for the collision pass. Added to
     * what is ticked rather than replacing it, so an author can import and then keep ticking.
     */
    private void takeFromModel()
    {
        if (this.form == null || this.model == null || !(this.model.model instanceof Model cubic))
        {
            return;
        }

        Set<String> bones = new LinkedHashSet<>(this.chain.bones());

        bones.addAll(ChainBones.of(this.form, cubic));

        this.editComposition((chain) -> chain.withBones(bones));
    }

    /** Writes one edit and nothing else — see {@link UIBoneSection} on why it must not relayout. */
    private void edit(UnaryOperator<FormChain> edit)
    {
        if (this.form == null)
        {
            return;
        }

        this.chain = edit.apply(this.chain);

        FormChains.set(this.form, this.chain);
    }

    /**
     * An edit that can change which rows exist — ticking a bone, importing, clearing. Safe to
     * rebuild from: every caller is a click, and clicks are not the render pass.
     */
    private void editComposition(UnaryOperator<FormChain> edit)
    {
        boolean had = !this.chain.bones().isEmpty();

        this.edit(edit);

        if (had != !this.chain.bones().isEmpty())
        {
            this.updateLabels();
        }
        else
        {
            this.syncKnobs();
        }
    }

    /* Syncing the UI */

    public void setForm(Form form)
    {
        this.chain = FormChains.get(form);

        super.setForm(form, false);
        this.updateLabels();
    }

    @Override
    protected void onBonePicked()
    {}

    private void updateLabels()
    {
        this.syncKnobs();
        this.rebuild();
    }

    /** The values only — never the layout. See {@link #edit} for why that separation is load-bearing. */
    private void syncKnobs()
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
            this.mass.setValue(this.chain.mass());
            this.gravity.setValue(this.chain.gravity());
            this.falloff.setValue(this.chain.falloff());
            this.bend.setValue(this.chain.bend());
            this.selfCollision.setValue(this.chain.selfCollision());
        }
        finally
        {
            this.syncing = false;
        }
    }

    /**
     * The knobs only appear once there is a strand for them to describe.
     *
     * <p>Only ever called from a click or from {@link #setForm} — never from a value callback, and
     * never from the render pass; see {@link #edit}.</p>
     */
    private void rebuild()
    {
        this.removeAll();
        this.add(this.bonesSearch, UI.row(this.takeFromModel, this.clear));

        if (!this.chain.bones().isEmpty())
        {
            this.add(this.stiffness, this.damping);
            this.add(UI.row(this.falloff, this.bend));
            this.add(UI.row(this.mass, this.gravity));
            this.add(this.selfCollision, this.shapeHint);
        }

        this.relayout();
    }
}
