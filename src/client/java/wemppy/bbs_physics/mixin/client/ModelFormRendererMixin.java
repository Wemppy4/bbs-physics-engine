package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the ragdoll's simulated pose to the model in both places a pose leaves the renderer.
 *
 * <p><b>The draw path:</b> at the head of the constraint stage's physics step — after IK, before
 * the chain solvers — which is the exact slot in the pipeline BBS reserves for things that
 * overrule the animated pose. Everything after it composes on the substituted bones: the hair
 * chains anchor to fallen limbs (§3.1's "hair rides the ragdoll for free"), the render draws
 * them, items and armor follow through the captured matrices.</p>
 *
 * <p><b>The matrix walk:</b> the same substitution just before the bones are captured, so anchors,
 * gizmos and trackers see the simulated pose too — a crate anchored to a ragdolled hand rides the
 * fall. The film scene's own evaluation of this very walk is excused through a flag: the drive
 * targets must stay pure animation, or the ragdoll would chase its own tail.</p>
 */
@Mixin(ModelFormRenderer.class)
public abstract class ModelFormRendererMixin
{
    @Inject(method = "applyPhysicsOnce", at = @At("HEAD"))
    private void bbs_physics$applyRagdollPose(IEntity target, ModelInstance model, float transition, Matrix4f baseTransform, CallbackInfo info)
    {
        ModelFormRenderer renderer = (ModelFormRenderer) (Object) this;

        RagdollPoseApplier.apply(renderer.getForm(), model, transition);
    }

    @Inject(
        method = "collectMatrices(Lmchorse/bbs_mod/forms/entities/IEntity;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/forms/renderers/utils/MatrixCache;Ljava/lang/String;F)V",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/renderers/ModelFormRenderer;captureMatrices(Lmchorse/bbs_mod/cubic/ModelInstance;)V")
    )
    private void bbs_physics$applyRagdollToWalk(IEntity entity, MatrixStack stack, MatrixCache matrices, String prefix, float transition, CallbackInfo info)
    {
        ModelFormRenderer renderer = (ModelFormRenderer) (Object) this;

        RagdollPoseApplier.apply(renderer.getForm(), renderer.getModel(), transition);
    }
}
