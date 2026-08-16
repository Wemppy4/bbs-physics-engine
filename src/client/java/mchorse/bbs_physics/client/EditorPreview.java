package mchorse.bbs_physics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_physics.client.collision.CollisionPreview;
import mchorse.bbs_physics.client.ragdoll.RagdollPreview;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Both overlays — collision shapes and ragdoll joints — drawn into a form viewport.
 *
 * <p><b>Why this is not just two calls in the mixin.</b> The overlays are drawn at the tail of
 * {@code renderUserModel}, and in the form editor that is <em>after</em> the gizmo's pick stencil
 * has run. That pass binds a framebuffer of its own — which sets the GL viewport to it — and hands
 * the screen back with {@code getFramebuffer().beginWrite(true)}, whose {@code true} means "and set
 * the viewport to the whole window". The projection is still the viewport's, so from that point on
 * a perspective drawing is stretched across the entire window instead of the preview rectangle:
 * the overlay lands next to the model rather than on it, and by a distance that grows the further
 * the shape sits from the middle of the screen. That is what "it slides off when the camera moves
 * away from the centre" was.</p>
 *
 * <p>BBS never hit this because everything it draws in perspective — the grid, the model, the gizmo
 * axes — is drawn before that pass. Rather than squeeze in ahead of it, the viewport is simply put
 * back the way {@link mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer} set it and
 * restored afterwards, which is the same dance BBS itself does in {@code Gizmo.renderInterface}.
 * Re-applying a viewport that is already right costs nothing, so the plain form viewport — which
 * has no stencil pass and was never broken — goes down the same path.</p>
 */
public final class EditorPreview
{
    private EditorPreview()
    {}

    public static void render(Form form, IEntity entity, Area area, UIContext context)
    {
        if (area == null || context == null)
        {
            return;
        }

        MatrixStack stack = context.batcher.getContext().getMatrices();
        float transition = context.getTransition();

        UIUtils.viewportArea(area);

        try
        {
            CollisionPreview.render(form, entity, stack, transition);
            RagdollPreview.render(form, entity, stack, transition);
        }
        finally
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            RenderSystem.viewport(0, 0, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        }
    }
}
