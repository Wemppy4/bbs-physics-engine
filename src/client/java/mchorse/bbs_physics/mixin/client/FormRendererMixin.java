package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.pose.Transform;
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
 */
@Mixin(FormRenderer.class)
public abstract class FormRendererMixin
{
    @Shadow
    protected Form form;

    @Shadow
    protected abstract Transform createTransform();

    /**
     * Whether the simulation is in charge of this form right now. It is not in the form editor's
     * preview (no scene has claimed the form), not while the animation owns it, and not on an
     * unrecorded frame.
     */
    private PhysicsBodyState bbs_physics$simulated()
    {
        PhysicsBodyState state = PhysicsForms.getState(this.form);

        if (state == null || !state.isSimulated() || PhysicsForms.isKinematic(this.form))
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

        Vector3f position = state.getPosition(transition, new Vector3f());

        stack.translate(position.x, position.y, position.z);

        if (!origin)
        {
            stack.multiply(state.getRotation(transition, new Quaternionf()));

            Vector3f scale = this.createTransform().scale;

            stack.scale(scale.x, scale.y, scale.z);
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

        Vector3f scale = this.createTransform().scale;

        matrix.translate(state.getPosition(transition, new Vector3f()));
        matrix.rotate(state.getRotation(transition, new Quaternionf()));
        matrix.scale(scale.x, scale.y, scale.z);

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
