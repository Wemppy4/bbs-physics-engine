package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_physics.client.EditorPreview;
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
 * <p>Injected at the tail, which is after the stencil pass — and that pass leaves the GL viewport
 * pointing at the whole window rather than at the preview. {@link EditorPreview} puts it back; the
 * reason it has to is written out there.</p>
 */
@Mixin(UIPickableFormRenderer.class)
public class UIPickableFormRendererMixin
{
    @Inject(method = "renderUserModel", at = @At("TAIL"))
    private void bbs_physics$drawCollision(UIContext context, CallbackInfo info)
    {
        UIPickableFormRenderer renderer = (UIPickableFormRenderer) (Object) this;

        /* The target entity, not the renderer's own stub: when the editor was opened from a film,
         * the preview is posed by the actor it belongs to, and the markup has to be measured
         * against the same pose the model is drawn in. */
        EditorPreview.render(renderer.form, renderer.getTargetEntity(), renderer.area, context);
    }
}
