package wemppy.bbs_physics.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.gizmo.TransformOrientation;
import mchorse.bbs_mod.utils.Pair;
import org.joml.Matrix4f;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.clips.ImpulseGizmo;
import wemppy.bbs_physics.client.scene.FilmScenes;
import wemppy.bbs_physics.client.scene.SceneStatus;
import wemppy.bbs_physics.client.scene.SceneStatusHUD;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the scene's numbers over the film editor's viewport, and hosts the impulse gizmo.
 *
 * <p>{@code renderHUD} is where BBS writes its own overlays into the viewport — the recording dot,
 * the tick counter — so it is both the right place and one that already receives the viewport's
 * area, which a readout pinned to a corner needs. Drawing from the world pass instead would mean
 * billboarding text in three dimensions to say something that is not about any place in the scene.
 * </p>
 *
 * <p>The gizmo is drawn for whatever {@code getBone} names, so a selected impulse clip answers it
 * with {@link ImpulseGizmo#TARGET}; the recording and viewport-size gates stay CML's.</p>
 */
@Mixin(UIFilmController.class)
public class UIFilmControllerMixin
{
    @Inject(method = "renderHUD", at = @At("TAIL"))
    private void bbs_physics$onRenderHUD(UIContext context, Area area, CallbackInfo info)
    {
        UIFilmController self = (UIFilmController) (Object) this;

        /* The impulse gizmo is never mounted, so nothing else would advance its drag. */
        if (ImpulseGizmo.isDragging())
        {
            ImpulseGizmo.get(self.panel).tickGizmoDrag(context);
        }

        if (BBSPhysicsSettings.debug == null || !BBSPhysicsSettings.debug.get())
        {
            return;
        }

        SceneStatus status = FilmScenes.getStatus(self.editorController);

        if (status != null)
        {
            SceneStatusHUD.render(context, area, status);
        }
    }

    @Inject(method = "getBone", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$impulseTarget(CallbackInfoReturnable<Pair<String, TransformOrientation>> info)
    {
        UIFilmController self = (UIFilmController) (Object) this;
        ImpulseActionClip clip = ImpulseGizmo.selected(self.panel);

        if (clip != null)
        {
            info.setReturnValue(ImpulseGizmo.get(self.panel).select(clip));
        }
    }

    @WrapOperation(
        method = "renderHUD",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/utils/Gizmo;renderInterface(Lmchorse/bbs_mod/ui/framework/UIContext;Lorg/joml/Matrix4f;Lmchorse/bbs_mod/ui/utils/Area;)V")
    )
    private void bbs_physics$impulseHandles(Gizmo gizmo, UIContext context, Matrix4f projection, Area area, Operation<Void> original)
    {
        if (ImpulseGizmo.selected(((UIFilmController) (Object) this).panel) == null)
        {
            original.call(gizmo, context, projection, area);

            return;
        }

        Gizmo.Mode previous = ImpulseGizmo.forceTranslate();

        try
        {
            original.call(gizmo, context, projection, area);
        }
        finally
        {
            ImpulseGizmo.restoreMode(previous);
        }
    }
}
