package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.utils.Gizmo;
import wemppy.bbs_physics.client.collision.UICollisionFormPanel;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the viewport gizmo to the collision tab, so a collider is dragged rather than typed.
 *
 * <p><b>Almost none of this is new machinery.</b> The editor already draws a gizmo, already stands
 * it in the frame of whatever is being edited, and already works out how a screen drag turns into
 * numbers. All four hooks here answer the same three questions it was already asking — what is being
 * dragged, where does it stand, in which space — with the selected collision primitive instead of
 * the form's own transform.</p>
 *
 * <p>Without the {@code startGizmo} hook the tab is actively hostile: the editor's answer is
 * {@code getEditableTransform()}, which <em>switches the panel to General</em> — so grabbing a
 * handle while marking up collision threw the author out of the tab they were working in.</p>
 *
 * <p>Every hook stands down when the states editor is open or nothing is selected, leaving BBS's own
 * answer untouched. The addon is a guest here.</p>
 */
@Mixin(UIFormEditor.class)
public class UIFormEditorMixin
{
    @Inject(method = "startGizmo", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$startGizmo(UIContext context, int stencilIndex, CallbackInfoReturnable<Boolean> info)
    {
        UIPropTransform transform = this.bbs_physics$transform();

        if (transform != null)
        {
            UIFormEditor editor = (UIFormEditor) (Object) this;

            info.setReturnValue(Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, editor.buildHotkeyDrag(transform)));
        }
    }

    @Inject(method = "getOrigin", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$getOrigin(float transition, CallbackInfoReturnable<Matrix4f> info)
    {
        UIPropTransform transform = this.bbs_physics$transform();

        if (transform != null)
        {
            this.bbs_physics$origin(transition, transform.isLocal(), info);
        }
    }

    /**
     * The rotation-bearing variant. BBS samples this one while deriving rotation axes, and a matrix
     * stripped of rotation would collapse those axes to identity — so it ignores the LOCAL/GLOBAL
     * switch on purpose, exactly as the method it is standing in for does.
     */
    @Inject(method = "getOriginMatrix", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$getOriginMatrix(float transition, CallbackInfoReturnable<Matrix4f> info)
    {
        if (this.bbs_physics$transform() != null)
        {
            this.bbs_physics$origin(transition, true, info);
        }
    }

    @Inject(method = "getGizmoSpace", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$getGizmoSpace(CallbackInfoReturnable<TransformSpace> info)
    {
        UIPropTransform transform = this.bbs_physics$transform();

        if (transform != null)
        {
            info.setReturnValue(transform.getSpace());
        }
    }

    private void bbs_physics$origin(float transition, boolean local, CallbackInfoReturnable<Matrix4f> info)
    {
        UIFormEditor editor = (UIFormEditor) (Object) this;
        UICollisionFormPanel panel = this.bbs_physics$panel();
        Matrix4f origin = panel == null ? null : panel.gizmoOrigin(editor.renderer.getTargetEntity(), transition, local);

        /* A frame the matrix cache has nothing for — a model still loading — falls through to BBS
         * rather than parking the gizmo at the origin of the world. */
        if (origin != null)
        {
            info.setReturnValue(origin);
        }
    }

    private UIPropTransform bbs_physics$transform()
    {
        UICollisionFormPanel panel = this.bbs_physics$panel();

        return panel == null ? null : panel.getGizmoTransform();
    }

    private UICollisionFormPanel bbs_physics$panel()
    {
        UIFormEditor editor = (UIFormEditor) (Object) this;

        if (editor.statesEditor != null && editor.statesEditor.isVisible())
        {
            return null;
        }

        return editor.editor != null && editor.editor.view instanceof UICollisionFormPanel panel ? panel : null;
    }
}
