package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import wemppy.bbs_physics.client.EditorPreview;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the collision markup over the model in the <em>form editor's</em> viewport.
 *
 * <p>A separate mixin from {@link UIFormRendererMixin} for one reason: the form editor does not use
 * the plain form renderer. It uses this one — the pickable flavour, with the gizmo and the stencil
 * pass — and it overrides {@code renderUserModel} outright rather than extending it, so an
 * injection into the parent never runs here. Since it never calls its parent either, the two
 * mixins can never both fire for the same viewport.</p>
 *
 * <p>Injected at the tail, which is after the gizmo and its picking pass — both of which now go
 * to framebuffers of their own and leave nothing behind for the overlay to undo.</p>
 */
@Mixin(UIPickableFormRenderer.class)
public class UIPickableFormRendererMixin
{
    @Inject(method = "renderUserModel", at = @At("TAIL"))
    private void bbs_physics$drawCollision(UIContext context, CallbackInfo info)
    {
        UIPickableFormRenderer renderer = (UIPickableFormRenderer) (Object) this;
        MatrixStack stack = ((UIModelRendererInvoker) this).bbs_physics$createCameraStack();

        /* The target entity, not the renderer's own stub: when the editor was opened from a film,
         * the preview is posed by the actor it belongs to, and the markup has to be measured
         * against the same pose the model is drawn in.
         *
         * The editor comes along so the overlay can draw the selected body part alone — found by
         * walking up the UI tree, because this viewport is a child of it and nothing else knows
         * which entry the author is standing on. */
        EditorPreview.render(renderer.form, renderer.getTargetEntity(), stack, context, renderer.getParent(UIFormEditor.class));
    }
}
