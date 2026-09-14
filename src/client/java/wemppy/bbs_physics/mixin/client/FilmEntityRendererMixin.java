package wemppy.bbs_physics.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Gizmo;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.clips.ImpulseGizmo;

@Mixin(FilmEntityRenderer.class)
public abstract class FilmEntityRendererMixin
{
    /** Both the visible placement and the pick stencil use the impulse's world point. */
    @Inject(method = "renderReplayGizmo", at = @At("HEAD"), cancellable = true)
    private static void bbs_physics$renderImpulse(IEntity entity, double cx, double cy, double cz,
        float transition, TransformSpace space, Matrix4f view, StencilMap stencil, MatrixStack stack, CallbackInfo info)
    {
        FilmControllerContext context = FilmControllerContext.instance;
        String target = context.gizmoTarget.bone();

        if (target == null || !target.startsWith(ImpulseGizmo.TARGET_PREFIX) || context.replay == null)
        {
            return;
        }

        info.cancel();

        for (ImpulseActionClip clip : context.replay.actions.getClips(ImpulseActionClip.class))
        {
            if (!target.equals(ImpulseGizmo.TARGET_PREFIX + clip.getId())) continue;

            Point point = clip.point.get();
            stack.push();
            stack.translate(point.x - context.camera.getPos().x,
                point.y - context.camera.getPos().y, point.z - context.camera.getPos().z);

            if (stencil == null) Gizmo.INSTANCE.captureVisual(stack, ImpulseGizmo.MASK);
            else Gizmo.INSTANCE.renderStencil(stack, ImpulseGizmo.MASK);

            RenderSystem.enableDepthTest();
            stack.pop();
            return;
        }
    }
}
