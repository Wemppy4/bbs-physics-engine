package mchorse.bbs_physics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.UIForms;
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

    /**
     * @param editor the form editor this viewport belongs to, or null when there is none (the model
     *               editor and the texture preview draw through here too). It decides <em>whose</em>
     *               markup is drawn — see {@link #selection}
     */
    public static void render(Form form, IEntity entity, Area area, UIContext context, UIFormEditor editor)
    {
        if (area == null || context == null)
        {
            return;
        }

        MatrixStack stack = context.batcher.getContext().getMatrices();
        float transition = context.getTransition();
        String selection = selection(form, editor);

        UIUtils.viewportArea(area);

        try
        {
            CollisionPreview.render(form, entity, stack, transition, selection);
            RagdollPreview.render(form, entity, stack, transition, selection);
        }
        finally
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            RenderSystem.viewport(0, 0, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        }
    }

    /**
     * The path in the form tree whose markup the overlay should draw — the body part the author has
     * selected in the editor's list, and everything nested inside it.
     *
     * <p>Drawing the whole tree was the first version's choice and it does not survive contact with
     * a real rig: a character is a model plus a dozen body parts, so marking up one of them meant
     * placing a capsule inside a thicket of everybody else's shapes, with no way to tell which
     * outline answers to the panel on the left. Now the overlay shows exactly what the panel edits.
     * Selecting the root still shows everything, which is the overview an author gets by clicking
     * the top of the list.</p>
     *
     * @return the path prefix to keep, or null to keep everything
     */
    private static String selection(Form form, UIFormEditor editor)
    {
        if (editor == null || editor.formsList == null)
        {
            return null;
        }

        UIForms.FormEntry current = editor.formsList.getCurrentFirst();
        Form selected = current == null ? null : current.getForm();

        if (selected == null || selected == form)
        {
            return null;
        }

        /* Relative to the tree's root, which is the form being drawn — the same convention the
         * collector names its pieces by. */
        String path = FormUtils.getPath(selected);

        return path.isEmpty() ? null : path;
    }
}
