package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_physics.client.ragdoll.RagdollPoseApplier;
import mchorse.bbs_physics.forms.PhysicsBodyState;
import mchorse.bbs_physics.forms.PhysicsForms;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a form wherever the simulation has put it, for <em>any</em> form that has been given the
 * rigid body modifier.
 *
 * <p>This is what replaced the wrapper form (Р7). A physics body used to be a form of its own with
 * a renderer of its own, and the substitution lived there; now the modifier can be on a block, an
 * item, a model or a group, so the substitution has to happen where every form's transform is
 * applied — the base renderer. Two overloads of {@code applyTransforms} cover both paths BBS
 * draws through, and only {@code ModelFormRenderer} overrides either one, calling {@code super}
 * both times, so nothing slips past.</p>
 *
 * <p>While the animation still owns the body, or on a frame the recording has not reached (Р8.1),
 * this falls straight through and the form is drawn from its keyframes as usual. <b>Scale is never
 * substituted</b>: physics has an opinion about where a thing is and which way up, not about how
 * big it is drawn — and it is read through {@code createTransform} rather than off the form, so
 * that states and overlays still count.</p>
 *
 * <p><b>By the handle's weight, not as a switch.</b> Between the two ends the drawn frame is the
 * animated one blended with the simulated one, the way a ragdoll's bones have always been drawn
 * ({@code PhysicsBodyState.getWeight}). It used to be all or nothing here, and the fade was left to
 * the pull in the solver — which holds a body tight almost to the bottom of the handle, so a
 * release drawn over ten frames looked held for nine of them and thrown on the tenth, and the pull
 * spent those nine frames rubbing off the speed the throw was made of.</p>
 *
 * <p><b>Never during the simulation's own pose walk.</b> That walk exists to ask where the
 * <em>animation</em> has this form, because that is the target the body is pulled towards; a walk
 * that answered with the body's own last position would be handing the pull the body's own answer,
 * and the pull would have nothing to close but the gap between a thing and itself. What it did
 * instead was rub the body's speed away by the handle every tick and hold it nowhere at all: a
 * half-released crate stopped dead in the air rather than trailing its keyframes. The ragdoll pose
 * applier is flagged off for the same walk and for the same reason — this is that flag, read from
 * the other side of the substitution.</p>
 */
@Mixin(FormRenderer.class)
public abstract class FormRendererMixin
{
    @Shadow
    protected Form form;

    @Shadow
    protected abstract Transform createTransform();

    /**
     * Whether the simulation has anything to say about this form right now. It has not in the form
     * editor's preview (no scene has claimed the form), not on a frame the recording has not
     * reached, and not inside the simulation's own pose walk.
     */
    private PhysicsBodyState bbs_physics$simulated()
    {
        PhysicsBodyState state = PhysicsForms.getState(this.form);

        if (state == null || !state.isSimulated() || RagdollPoseApplier.isEvaluating())
        {
            return null;
        }

        return state;
    }

    @Inject(method = "applyTransforms(Lnet/minecraft/client/util/math/MatrixStack;ZF)V", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$applyToStack(MatrixStack stack, boolean origin, float transition, CallbackInfo info)
    {
        PhysicsBodyState state = this.bbs_physics$simulated();

        if (state == null)
        {
            return;
        }

        float weight = state.getWeight(transition);

        if (weight <= 0F)
        {
            /* The animation owns the form outright — the ordinary path, and the cheap one. */
            return;
        }

        Transform transform = this.createTransform();
        Vector3f position = state.getPosition(transition, new Vector3f());

        if (weight < 1F)
        {
            transform.translate.lerp(position, weight, position);
        }

        stack.translate(position.x, position.y, position.z);

        if (!origin)
        {
            Quaternionf rotation = state.getRotation(transition, new Quaternionf());

            if (weight < 1F)
            {
                transform.createRotation().slerp(rotation, weight, rotation);
            }

            stack.multiply(rotation);
            stack.scale(transform.scale.x, transform.scale.y, transform.scale.z);
        }

        info.cancel();
    }

    @Inject(method = "applyTransforms(Lorg/joml/Matrix4f;F)V", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$applyToMatrix(Matrix4f matrix, float transition, CallbackInfo info)
    {
        PhysicsBodyState state = this.bbs_physics$simulated();

        if (state == null)
        {
            return;
        }

        float weight = state.getWeight(transition);

        if (weight <= 0F)
        {
            return;
        }

        Transform transform = this.createTransform();
        Vector3f position = state.getPosition(transition, new Vector3f());
        Quaternionf rotation = state.getRotation(transition, new Quaternionf());

        if (weight < 1F)
        {
            transform.translate.lerp(position, weight, position);
            transform.createRotation().slerp(rotation, weight, rotation);
        }

        matrix.translate(position);
        matrix.rotate(rotation);
        matrix.scale(transform.scale.x, transform.scale.y, transform.scale.z);

        info.cancel();
    }

    /**
     * Hands the scene the frame this form's transform is applied in — everything on the stack above
     * the form: for a nested body the parent forms, the bone it hangs on and the part transform;
     * for a root form the identity.
     *
     * <p>The simulation needs it to translate its world answer into the local transform this
     * renderer substitutes, and the walk is the only place that chain exists.</p>
     */
    @Inject(
        method = "collectMatrices(Lmchorse/bbs_mod/forms/entities/IEntity;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/forms/renderers/utils/MatrixCache;Ljava/lang/String;F)V",
        at = @At("HEAD")
    )
    private void bbs_physics$captureParentFrame(IEntity entity, MatrixStack stack, MatrixCache matrices, String prefix, float transition, CallbackInfo info)
    {
        PhysicsBodyState state = PhysicsForms.getState(this.form);

        if (state != null)
        {
            state.captureWalkParentFrame(stack.peek().getPositionMatrix());
        }
    }
}
