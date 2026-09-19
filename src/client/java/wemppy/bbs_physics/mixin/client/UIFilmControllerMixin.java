package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.clips.ImpulseGizmo;
import wemppy.bbs_physics.client.clips.ImpulseRadiusHandle;
import mchorse.bbs_mod.ui.film.PreviewHud;
import mchorse.bbs_mod.ui.utils.Area;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

@Mixin(UIFilmController.class)
public abstract class UIFilmControllerMixin
{
    @Shadow @Final public UIFilmPanel panel;
    @Unique private ImpulseGizmo bbs_physics$impulseGizmo;
    @Unique private ImpulseRadiusHandle bbs_physics$radius;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbs_physics$attachImpulseGizmo(UIFilmPanel panel, CallbackInfo info)
    {
        this.bbs_physics$impulseGizmo = new ImpulseGizmo(panel);
        this.bbs_physics$radius = new ImpulseRadiusHandle((UIFilmController) (Object) this);
        /* Like replayTransform, the hidden editor needs a parent to resolve the UI context
         * that TransformGesture uses to start the drag and install its accept/reject overlay. */
        ((UIFilmController) (Object) this).add(this.bbs_physics$impulseGizmo);
        ((UIFilmController) (Object) this).add(this.bbs_physics$radius);
    }

    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$radiusClick(UIContext context, CallbackInfoReturnable<Boolean> info)
    {
        if (this.bbs_physics$radius.click(context)) info.setReturnValue(true);
    }

    @Inject(method = "subMouseReleased", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$radiusRelease(UIContext context, CallbackInfoReturnable<Boolean> info)
    {
        if (this.bbs_physics$radius.release(context)) info.setReturnValue(true);
    }

    @Inject(method = "subKeyPressed", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$radiusKey(UIContext context, CallbackInfoReturnable<Boolean> info)
    {
        if (this.bbs_physics$radius.key(context)) info.setReturnValue(true);
    }

    @Inject(method = "renderHUD", at = @At("HEAD"))
    private void bbs_physics$radiusUpdate(UIContext context, PreviewHud hud, Area navBlock, CallbackInfo info)
    {
        this.bbs_physics$radius.update(context);
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void bbs_physics$radiusRender(WorldRenderContext context, CallbackInfo info)
    {
        this.bbs_physics$radius.render(context);
    }

    @Inject(method = "stopGizmoInteraction", at = @At("HEAD"))
    private void bbs_physics$radiusStop(CallbackInfo info)
    {
        this.bbs_physics$radius.finish(false);
    }

    @Inject(method = "getEditTarget", at = @At("RETURN"), cancellable = true)
    private void bbs_physics$impulseTarget(CallbackInfoReturnable<FilmTarget> info)
    {
        ImpulseActionClip clip = ImpulseGizmo.selected(this.panel);

        /* Preserve the controller's recording, covered-editor and missing-actor gates. */
        if (clip != null && !info.getReturnValue().isNone())
        {
            info.setReturnValue(this.bbs_physics$impulseGizmo.select(clip));
        }
    }

    @Inject(method = "startGizmo", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$startImpulse(UIContext context, int stencilIndex, CallbackInfoReturnable<Boolean> info)
    {
        ImpulseActionClip clip = ImpulseGizmo.selected(this.panel);

        if (clip != null && this.bbs_physics$impulseGizmo != null)
        {
            this.bbs_physics$impulseGizmo.select(clip);
            GizmoDrag drag = GizmoDrag.fromRenderedGizmo(this.panel.getCamera(), this.panel.preview.getViewport());
            info.setReturnValue(!this.panel.isFlying() && drag != null
                && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, this.bbs_physics$impulseGizmo, drag)
                && this.bbs_physics$impulseGizmo.isEditing());
        }
    }
}
