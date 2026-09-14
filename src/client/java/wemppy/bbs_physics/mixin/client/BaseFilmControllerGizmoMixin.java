package wemppy.bbs_physics.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.gizmo.TransformOrientation;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.clips.ImpulseGizmo;

/**
 * Draws the impulse gizmo at the clip's world point instead of on a bone of the actor.
 *
 * <p>{@code renderAxes} runs inside the actor's own transform, so the stack it gets is of no use
 * for a point in the world. The frame the actor was entered with — the world view — is kept at the
 * start of {@code renderEntity} and the gizmo is placed from that. Both the visible capture and the
 * pick stencil go through here.</p>
 */
@Mixin(BaseFilmController.class)
public class BaseFilmControllerGizmoMixin
{
    @Unique
    private static final Matrix4f bbs_physics$worldView = new Matrix4f();

    @Unique
    private static final Vector3d bbs_physics$cameraPos = new Vector3d();

    @Inject(method = "renderEntity(Lmchorse/bbs_mod/film/FilmControllerContext;)V", at = @At("HEAD"))
    private static void bbs_physics$captureWorldView(FilmControllerContext context, CallbackInfo info)
    {
        if (!ImpulseGizmo.isTarget(context.bone) || context.stack == null || context.camera == null)
        {
            return;
        }

        Vec3d pos = context.camera.getPos();

        bbs_physics$worldView.set(context.stack.peek().getPositionMatrix());
        bbs_physics$cameraPos.set(pos.x, pos.y, pos.z);
    }

    @Inject(method = "renderAxes", at = @At("HEAD"), cancellable = true)
    private static void bbs_physics$renderImpulse(String bone, TransformOrientation space, StencilMap stencilMap, Form form, IEntity entity, float transition, MatrixStack stack, CallbackInfo info)
    {
        if (!ImpulseGizmo.isTarget(bone))
        {
            return;
        }

        info.cancel();

        ImpulseActionClip clip = ImpulseGizmo.current();

        if (clip == null)
        {
            return;
        }

        Point point = clip.point.get();

        stack.push();
        stack.peek().getPositionMatrix().set(bbs_physics$worldView);
        stack.translate(point.x - bbs_physics$cameraPos.x, point.y - bbs_physics$cameraPos.y, point.z - bbs_physics$cameraPos.z);
        Gizmo.INSTANCE.setActiveOrientation(TransformOrientation.GLOBAL);

        if (stencilMap == null)
        {
            Gizmo.INSTANCE.captureVisual(stack);
        }
        else
        {
            Gizmo.Mode previous = ImpulseGizmo.forceTranslate();

            Gizmo.INSTANCE.renderStencil(stack, stencilMap);
            ImpulseGizmo.restoreMode(previous);
        }

        RenderSystem.enableDepthTest();
        stack.pop();
    }
}
