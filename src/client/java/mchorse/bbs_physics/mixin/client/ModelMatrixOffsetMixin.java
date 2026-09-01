package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicMatrixRenderer;
import mchorse.bbs_mod.cubic.render.ICubicRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets everything that hangs off a bone follow a bone that has <em>moved</em>.
 *
 * <p>Body parts, armour, anchors, trackers and gizmos are all placed by the matrices this renderer
 * collects — a silent second walk of the skeleton, done with no buffers, purely to write down where
 * each bone ended up. Its walk has to repeat the drawing walk step for step, and on CML it misses
 * the first step: {@code ICubicRenderer.applyGroupTransformations} leads with the bone's
 * {@code offset} and this one does not. The offset is the whole of a bone's <b>displacement</b> —
 * IK stretch writes it, and so does our ragdoll — so an attached form was handed a matrix carrying
 * the bone's turn and none of its travel: a helmet spinning with a fallen head while hovering where
 * the head used to stand.</p>
 *
 * <p>One line, at the head, before the translate — exactly where the drawing walk has it, and
 * exactly what BBS itself does (its own copy of this class carries a comment demanding the two stay
 * in lockstep, for these very consumers). The rest of the walk is left alone, including CML's
 * second snapshot of {@code origins}: that is a difference of theirs, not a defect of theirs.</p>
 */
@Mixin(CubicMatrixRenderer.class)
public class ModelMatrixOffsetMixin
{
    @Inject(
        method = "applyGroupTransformations(Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/data/model/ModelGroup;)V",
        at = @At("HEAD")
    )
    private void bbs_physics$leadWithOffset(MatrixStack stack, ModelGroup group, CallbackInfo info)
    {
        ICubicRenderer.offsetGroup(stack, group);
    }
}
