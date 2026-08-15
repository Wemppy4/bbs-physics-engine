package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.cubic.render.ModelPivotFrames;
import mchorse.bbs_physics.client.ragdoll.RagdollPoseApplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets the chain physics see a fallen skeleton.
 *
 * <p>The chains read their anchor frames through a pivot-frame walk that skips every bone's
 * {@code offset} by default — the right rule for the IK stretch it was written for, and the wrong
 * one for a ragdoll, whose whole fall is carried in those offsets: a character lying on the floor
 * would have its hair anchored at standing height. While the form being rendered has a ragdoll in
 * charge (the flag the pose applier raises), the walk is asked to fold offsets in — which the
 * walk's own contract endorses for ancestor offsets, and ancestors are the only place a ragdoll
 * writes them.</p>
 */
@Mixin(ModelPivotFrames.class)
public class ModelPivotFramesMixin
{
    @ModifyArg(
        method = "collect(Lmchorse/bbs_mod/cubic/IModel;Ljava/util/Set;Ljava/util/Map;Lorg/joml/Matrix4f;)V",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/cubic/render/ModelPivotFrames;collect(Lmchorse/bbs_mod/cubic/IModel;Ljava/util/Set;Ljava/util/Map;Lorg/joml/Matrix4f;Z)V"
        ),
        index = 4
    )
    private static boolean bbs_physics$foldRagdollOffsets(boolean applyStretch)
    {
        return applyStretch || RagdollPoseApplier.isChainStretch();
    }
}
