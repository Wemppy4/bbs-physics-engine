package wemppy.bbs_physics.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.gizmo.GizmoController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.clips.ImpulseGizmo;

/**
 * Starts an impulse gizmo drag from a viewport click.
 *
 * <p>The viewport's click goes to the replay editor, which drags the transform of the selected
 * replay keyframe. While an impulse clip is selected the impulse gizmo is handed in instead, and the
 * rest of CML's start path runs unchanged.</p>
 */
@Mixin(UIReplaysEditor.class)
public abstract class UIReplaysEditorMixin
{
    @Shadow
    private UIFilmPanel filmPanel;

    @WrapOperation(
        method = "clickViewport",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/film/replays/UIReplaysEditorUtils;getEditableTransform(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor;)Lmchorse/bbs_mod/ui/framework/elements/input/UIPropTransform;")
    )
    private UIPropTransform bbs_physics$impulseTransform(UIKeyframeEditor editor, Operation<UIPropTransform> original)
    {
        ImpulseActionClip clip = ImpulseGizmo.selected(this.filmPanel);

        if (clip == null)
        {
            return original.call(editor);
        }

        ImpulseGizmo gizmo = ImpulseGizmo.get(this.filmPanel);

        gizmo.select(clip);

        return gizmo;
    }

    @WrapOperation(
        method = "clickViewport",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/utils/gizmo/GizmoController;tryStartHandleDrag(Lmchorse/bbs_mod/ui/framework/UIContext;Lmchorse/bbs_mod/ui/framework/elements/input/UIPropTransform;)Z")
    )
    private boolean bbs_physics$startImpulse(GizmoController controller, UIContext context, UIPropTransform transform, Operation<Boolean> original)
    {
        if (!(transform instanceof ImpulseGizmo))
        {
            return original.call(controller, context, transform);
        }

        Gizmo.Mode previous = ImpulseGizmo.forceTranslate();

        try
        {
            return original.call(controller, context, transform);
        }
        finally
        {
            ImpulseGizmo.restoreMode(previous);
        }
    }

    /** CML focuses the replay timeline after a gizmo grab, and that drops the clip selection. */
    @WrapOperation(
        method = "clickViewport",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/film/UIFilmPanel;focusLinkedPropertiesTab(Ljava/lang/String;)V")
    )
    private void bbs_physics$keepClipSelected(UIFilmPanel panel, String panelId, Operation<Void> original)
    {
        original.call(panel, ImpulseGizmo.isDragging() ? "actionTimeline" : panelId);
    }

    @Inject(method = "prepareGizmoDrag", at = @At("TAIL"))
    private void bbs_physics$tuneImpulse(UIPropTransform transform, CallbackInfo info)
    {
        if (transform instanceof ImpulseGizmo gizmo)
        {
            gizmo.tune();
        }
    }
}
