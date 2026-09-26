package wemppy.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import wemppy.bbs_physics.client.collision.ChainBones;
import wemppy.bbs_physics.client.collision.CollisionShapes;
import wemppy.bbs_physics.collision.*;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsType;
import wemppy.bbs_physics.ragdoll.*;

import java.util.function.Supplier;

/** One-click preparation on the actor's own form; authored collision and joints survive. */
public final class DeathSetup
{
    private static final java.util.Map<UIFilmPanel, java.util.Map<String, Boolean>> FOLDS = new java.util.WeakHashMap<>();

    public static UIElement section(UIFilmPanel panel, Supplier<Replay> selected)
    {
        var section = new mchorse.bbs_mod.ui.framework.elements.UISection(L10n.lang("bbs_physics.death.section"))
            .remember(FOLDS.computeIfAbsent(panel, key -> new java.util.HashMap<>()), "physics", false);
        section.fields.add(controls(panel, selected), wemppy.bbs_physics.client.scene.FilmBake.button(panel, selected));
        return section;
    }

    public static UIElement controls(UIFilmPanel panel, Supplier<Replay> selected)
    {
        UIElement controls = new UIElement();
        controls.column(5).vertical().stretch();
        UIToggle toggle = new UIToggle(L10n.lang("bbs_physics.death.enabled"), button ->
        {
            Replay replay = selected.get();
            if (!(replay instanceof DeathReplay settings)) return;
            Form copy = replay.form.get() == null ? null : mchorse.bbs_mod.forms.FormUtils.copy(replay.form.get());
            if (button.getValue() && !prepare(copy))
            {
                button.setValue(false);
                UIOverlay.addOverlay(button.getContext(), new UIMessageOverlayPanel(
                    L10n.lang("bbs_physics.death.enabled"), L10n.lang("bbs_physics.death.unsupported")));
                return;
            }
            BaseValue.edit(replay, value ->
            {
                if (button.getValue())
                {
                    replay.form.set(copy);
                    replay.actor.set(true);
                }
                settings.bbs_physics$deathEnabled().set(button.getValue());
            });
        });
        toggle.tooltip(L10n.lang("bbs_physics.death.enabled_tooltip"));
        UITrackpad strength = new UITrackpad(value ->
        {
            Replay active = selected.get();
            if (!(active instanceof DeathReplay)) return;
            var targets = new java.util.ArrayList<>(panel.replayEditor.replaysList.replays.getSelectedReplays());
            if (targets.isEmpty()) targets.add(active);
            float multiplier = value.floatValue();
            if (targets.size() == 1)
            {
                if (targets.get(0) instanceof DeathReplay settings)
                    BaseValue.edit(settings.bbs_physics$deathStrength(), v -> v.set(multiplier));
            }
            else if (panel.getData() != null)
            {
                // One parent notification captures every selected replay in the same undo edit.
                BaseValue.edit(panel.getData().replays, replays ->
                {
                    for (Replay replay : targets)
                        if (replay instanceof DeathReplay settings)
                            settings.bbs_physics$deathStrength().set(multiplier);
                });
            }
        });
        strength.limit(0).increment(0.1);
        strength.tooltip(L10n.lang("bbs_physics.death.multiplier_tooltip"));
        controls.add(toggle, UI.labelRow(L10n.lang("bbs_physics.death.multiplier"), strength));
        controls.valueBinding(() ->
        {
            boolean valid = selected.get() instanceof DeathReplay;
            toggle.setEnabled(valid);
            strength.setEnabled(valid);
            if (selected.get() instanceof DeathReplay settings)
            {
                toggle.setValue(settings.bbs_physics$deathEnabled().get());
                strength.setValue(settings.bbs_physics$deathStrength().get());
            }
        });
        return controls;
    }

    private static boolean prepare(Form form)
    {
        if (!(form instanceof ModelForm model)) return false;
        var instance = ModelFormRenderer.getModel(model);
        if (instance == null || !(instance.model instanceof Model cubic)) return false;
        FormCollision collision = FormCollisions.get(model);
        if (collision.isEmpty())
        {
            // Known player models get the same geometry-based markup as the collision editor.
            if (!model.model.get().startsWith("player/")) return false;
            var chains = ChainBones.of(model, cubic);
            for (String bone : cubic.getAllGroupKeys())
            {
                if (!chains.contains(bone) && CollisionShapes.boneSize(cubic, bone, instance.getScale()) > 0.1F)
                    collision = collision.with(bone, CollisionSlot.AUTO);
            }
            if (collision.isEmpty()) return false;
            FormCollisions.set(model, collision);
            FormRagdoll ragdoll = FormRagdolls.get(model);
            if (ragdoll.equals(FormRagdoll.EMPTY))
            {
                ragdoll = ragdoll.withPart("headwear", false);
                for (String bone : new String[] {"head", "left_arm", "right_arm", "left_leg", "right_leg"})
                {
                    if (collision.slots().containsKey(bone) && collision.slots().containsKey("torso"))
                        ragdoll = ragdoll.with(bone, RagdollJoint.DEFAULT.withAttachTo("torso"));
                }
                FormRagdolls.set(model, ragdoll);
            }
        }
        FormRagdoll configured = FormRagdolls.get(model);
        boolean hasBone = false;
        for (String bone : collision.slots().keySet())
        {
            if (!bone.isEmpty() && configured.isPart(bone) && cubic.getAllGroupKeys().contains(bone)
                && !collision.get(bone).isEmpty()
                && (collision.get(bone).mode() == CollisionMode.SHAPES || CollisionShapes.boneSize(cubic, bone, instance.getScale()) > 0F))
            {
                hasBone = true;
                break;
            }
        }
        if (!hasBone) return false;
        PhysicsForms.setType(model, PhysicsType.RAGDOLL);
        return true;
    }

    private DeathSetup() {}
}
