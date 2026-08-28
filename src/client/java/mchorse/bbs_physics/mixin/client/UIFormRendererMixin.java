package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.utils.UIFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_physics.client.EditorPreview;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the collision markup over the model in a plain form viewport — the model editor's preview
 * and the texture panel's.
 *
 * <p>Hooked here rather than inside the model renderer, where BBS draws its own IK and physics
 * overlays, because a form viewport hands over a stack and a form directly, with no fishing for
 * locals in a method that is free to change.</p>
 *
 * <p>The <em>form editor</em> is not this class — it uses {@code UIPickableFormRenderer}, which
 * replaces {@code renderUserModel} instead of extending it, so it needs a hook of its own; see
 * {@link UIPickableFormRendererMixin}.</p>
 */
@Mixin(UIFormRenderer.class)
public abstract class UIFormRendererMixin
{
    /**
     * The stack the viewport draws its model with — camera view baked in, because the preview is a
     * framebuffer with the global model view left identity. Inherited from {@code UIModelRenderer};
     * the overlay has to be built against the same one or it lands somewhere else entirely.
     */
    @Shadow
    protected abstract MatrixStack createCameraStack();

    @Inject(method = "renderUserModel", at = @At("TAIL"))
    private void bbs_physics$drawCollision(UIContext context, CallbackInfo info)
    {
        UIFormRenderer renderer = (UIFormRenderer) (Object) this;

        EditorPreview.render(renderer.form, renderer.getEntity(), this.createCameraStack(), context, null);
    }
}
