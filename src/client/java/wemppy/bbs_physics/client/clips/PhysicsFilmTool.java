package wemppy.bbs_physics.client.clips;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.api.client.editor.FilmEditorTool;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.util.math.MatrixStack;
import wemppy.bbs_physics.actions.ImpulseActionClip;

/** Move and radius tools share the film controller's lifecycle and undo context. */
public final class PhysicsFilmTool extends FilmEditorTool
{
    private final UIFilmController controller;
    private final ImpulseGizmo gizmo;
    private final DeathGizmo deathGizmo;
    private final ImpulseRadiusHandle radius;

    public PhysicsFilmTool(UIFilmController controller)
    {
        this.controller = controller;
        this.gizmo = new ImpulseGizmo(controller.panel);
        this.deathGizmo = new DeathGizmo(controller.panel);
        this.radius = new ImpulseRadiusHandle(controller);
        this.add(this.gizmo, this.deathGizmo, this.radius);
        this.setVisible(false);
    }

    @Override public FilmTarget target(FilmTarget original)
    {
        var death = DeathGizmo.selected(this.controller.panel);
        if (death != null) return this.deathGizmo.select(death);
        ImpulseActionClip clip = ImpulseGizmo.selected(this.controller.panel);
        return clip == null ? null : this.gizmo.select(clip);
    }

    @Override public Boolean startGizmo(UIContext context, int stencilIndex)
    {
        var death = DeathGizmo.selected(this.controller.panel);
        if (death != null)
        {
            this.deathGizmo.select(death);
            GizmoDrag drag = GizmoDrag.fromRenderedGizmo(this.controller.panel.getCamera(), this.controller.panel.preview.getViewport());
            return !this.controller.panel.isFlying() && drag != null
                && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, this.deathGizmo, drag) && this.deathGizmo.isEditing();
        }
        ImpulseActionClip clip = ImpulseGizmo.selected(this.controller.panel);
        if (clip == null) return null;
        this.gizmo.select(clip);
        GizmoDrag drag = GizmoDrag.fromRenderedGizmo(this.controller.panel.getCamera(), this.controller.panel.preview.getViewport());
        return !this.controller.panel.isFlying() && drag != null
            && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, this.gizmo, drag) && this.gizmo.isEditing();
    }

    @Override public boolean click(UIContext context) { return this.radius.click(context); }
    @Override public boolean release(UIContext context) { return this.radius.release(context); }
    @Override public boolean key(UIContext context) { return this.radius.key(context); }
    @Override public void updateTool(UIContext context) { this.radius.update(context); }
    @Override public void renderTool(WorldRenderContext context) { this.radius.render(context); }
    @Override public void stopTool() { this.radius.finish(false); }

    public static boolean drawGizmo(FilmControllerContext context, StencilMap stencil, MatrixStack stack)
    {
        String target = context.gizmoTarget.bone();
        if (target != null && target.startsWith(DeathGizmo.TARGET_PREFIX) && context.replay != null)
        {
            for (var clip : context.replay.actions.getClips(wemppy.bbs_physics.actions.DeathActionClip.class))
            {
                if (!target.equals(DeathGizmo.TARGET_PREFIX + clip.getId()) || !clip.enabled.get() || clip.baked.get()) continue;
                var point = clip.point.get();
                stack.push();
                try
                {
                    stack.translate(point.x - context.camera.getPos().x, point.y - context.camera.getPos().y, point.z - context.camera.getPos().z);
                    if (stencil == null)
                    {
                        Gizmo.INSTANCE.captureVisual(stack, DeathGizmo.MASK);
                        var d = clip.direction.get();
                        var arrow = new org.joml.Vector3f((float) d.x, (float) d.y, (float) d.z);
                        if ((wemppy.bbs_physics.BBSPhysicsSettings.debug == null || !wemppy.bbs_physics.BBSPhysicsSettings.debug.get())
                            && arrow.isFinite() && arrow.lengthSquared() > 1e-12F)
                            wemppy.bbs_physics.client.scene.DeathImpactRenderer.arrow(stack, arrow.normalize().mul(0.8F), 1F);
                    }
                    else Gizmo.INSTANCE.renderStencil(stack, DeathGizmo.MASK);
                }
                finally { stack.pop(); RenderSystem.enableDepthTest(); }
                return true;
            }
            return true;
        }
        if (target == null || !target.startsWith(ImpulseGizmo.TARGET_PREFIX) || context.replay == null) return false;
        for (ImpulseActionClip clip : context.replay.actions.getClips(ImpulseActionClip.class))
        {
            if (!target.equals(ImpulseGizmo.TARGET_PREFIX + clip.getId())) continue;
            var point = clip.point.get();
            stack.push();
            try
            {
                stack.translate(point.x - context.camera.getPos().x, point.y - context.camera.getPos().y, point.z - context.camera.getPos().z);
                if (stencil == null) Gizmo.INSTANCE.captureVisual(stack, ImpulseGizmo.MASK);
                else Gizmo.INSTANCE.renderStencil(stack, ImpulseGizmo.MASK);
            }
            finally { stack.pop(); }
            RenderSystem.enableDepthTest();
            return true;
        }
        return true;
    }
}
