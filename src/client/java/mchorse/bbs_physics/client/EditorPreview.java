package mchorse.bbs_physics.client;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.UIForms;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_physics.client.collision.CollisionPreview;
import mchorse.bbs_physics.client.ragdoll.RagdollPreview;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Both overlays — collision shapes and ragdoll joints — drawn into a form viewport.
 *
 * <p><b>The stack it draws into.</b> A form viewport is rendered off-screen on this branch: BBS
 * binds a framebuffer the size of the preview, sets the perspective projection on it and leaves the
 * global model view identity, so the camera lives in the vertices instead. Everything drawn here
 * therefore has to be built against the same camera stack the model was — the one
 * {@code UIModelRenderer.createCameraStack()} makes — which is what the two mixins hand over.</p>
 *
 * <p>The viewport juggling this class used to do went with it. It existed because the gizmo's pick
 * stencil handed the screen back with the viewport set to the whole window, stretching everything
 * drawn after it; there is no such hand-back now — the preview is a framebuffer of exactly its own
 * size, and {@code RenderSystem.viewport} does not exist to be put back.</p>
 */
public final class EditorPreview
{
    private EditorPreview()
    {}

    /**
     * @param stack  the viewport's camera stack — the one the model itself was drawn against
     * @param editor the form editor this viewport belongs to, or null when there is none (the model
     *               editor and the texture preview draw through here too). It decides <em>whose</em>
     *               markup is drawn — see {@link #selection}
     */
    public static void render(Form form, IEntity entity, MatrixStack stack, UIContext context, UIFormEditor editor)
    {
        if (stack == null || context == null)
        {
            return;
        }

        float transition = context.getTransition();
        String selection = selection(form, editor);

        CollisionPreview.render(form, entity, stack, transition, selection);
        RagdollPreview.render(form, entity, stack, transition, selection);
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
