package wemppy.bbs_physics.client.clips;

import wemppy.bbs_physics.client.collision.WireframeLayers;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.IUITreeEventListener;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.collision.CollisionWireframe;
import wemppy.bbs_physics.collision.CollisionKind;

/** The three radius rings are handles themselves, independent of the move gizmo. */
public final class ImpulseRadiusHandle extends UIElement implements IUITreeEventListener
{
    private static final int SEGMENTS = 24; // Same polygon as CollisionWireframe's sphere.
    private static ImpulseRadiusHandle active;
    private final UIFilmController controller;
    private final GizmoDrag projection = new GizmoDrag();
    private final Vector3d picked = new Vector3d();
    private final Vector3d start = new Vector3d();
    private final Vector3f axis = new Vector3f();
    private final Vector3f normal = new Vector3f();
    private ImpulseActionClip dragging;
    private float original;
    private float previewRadius;
    private boolean hovered;

    public ImpulseRadiusHandle(UIFilmController controller)
    {
        this.controller = controller;
        this.setVisible(false);
    }

    @Override
    public void onAddedToTree(UIElement element)
    {}

    @Override
    public void onRemovedFromTree(UIElement element)
    {
        this.finish(false);
    }

    private ImpulseActionClip selected()
    {
        return this.controller.canControl() || this.controller.panel.isFlying()
            || this.controller.panel.isRunning() || this.controller.getEditTarget().isNone()
            ? null : ImpulseGizmo.selected(this.controller.panel);
    }

    private boolean overMoveHandle()
    {
        var stencil = this.controller.getGizmoStencil();
        return stencil.hasPicked() && stencil.getIndex() >= Gizmo.STENCIL_X
            && stencil.getIndex() <= Gizmo.STENCIL_MAX;
    }

    public boolean click(UIContext context)
    {
        if (this.dragging != null)
        {
            if (context.mouseButton == 1) this.finish(false);
            return true;
        }

        ImpulseActionClip clip = this.selected();
        if (context.mouseButton != 0 || clip == null || this.overMoveHandle() || !this.pick(context, clip)) return false;

        Point center = clip.point.get();
        this.axis.set((float) (this.picked.x - center.x), (float) (this.picked.y - center.y), (float) (this.picked.z - center.z)).normalize();
        /* A plane containing the radius, oriented towards the camera, also works in orthographic view. */
        this.projection.setup(this.controller.panel.getCamera(), this.controller.getGizmoArea(), this.picked);
        this.projection.rayDirection(context.mouseX, context.mouseY, this.normal);
        this.normal.fma(-this.normal.dot(this.axis), this.axis);
        if (this.normal.lengthSquared() < 0.01F) return false;
        this.normal.normalize();
        if (!this.projection.intersectPlane(context.mouseX, context.mouseY, this.normal, this.start)) return false;

        this.dragging = clip;
        this.original = clip.radius.get();
        this.previewRadius = this.original;
        active = this;
        return true;
    }

    public boolean release(UIContext context)
    {
        if (this.dragging == null || context.mouseButton != 0) return false;
        if (this.selected() != this.dragging)
        {
            this.finish(false);
            return true;
        }
        this.move(context);
        this.finish(true);
        return true;
    }

    public boolean key(UIContext context)
    {
        if (this.dragging == null) return false;
        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE)) this.finish(false);
        return true;
    }

    public void update(UIContext context)
    {
        ImpulseActionClip clip = this.selected();
        if (this.dragging != null)
        {
            if (clip != this.dragging || !MinecraftClient.getInstance().isWindowFocused()) this.finish(false);
            else if (GLFW.glfwGetMouseButton(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE) this.finish(true);
            else this.move(context);
        }
        this.hovered = clip != null && (this.dragging != null || !this.overMoveHandle() && this.pick(context, clip));
    }

    private void move(UIContext context)
    {
        Vector3d current = new Vector3d();
        if (!this.projection.intersectPlane(context.mouseX, context.mouseY, this.normal, current)) return;
        current.sub(this.start);
        float radius = Math.max(0F, this.original + (float) (current.x * this.axis.x + current.y * this.axis.y + current.z * this.axis.z));
        if (!Float.isFinite(radius)) return;
        /* Preview without invalidating physics or adding undo entries on every mouse movement. */
        this.previewRadius = radius;
        for (UIImpulseActionClip editor : this.controller.panel.getChildren(UIImpulseActionClip.class))
        {
            editor.radius.setValue(radius);
        }
    }

    /** Preview geometry never changes the value read by simulation or saved to the film. */
    public static float displayedRadius(ImpulseActionClip clip)
    {
        return active != null && active.dragging == clip ? active.previewRadius : clip.radius.get();
    }

    public void finish(boolean accept)
    {
        if (this.dragging == null) return;
        ImpulseActionClip clip = this.dragging;
        float radius = this.previewRadius;
        this.dragging = null;
        if (active == this) active = null;
        this.hovered = false;
        if (accept && radius != this.original)
        {
            var undo = this.controller.panel.getUndoHandler();
            undo.submitUndo(true);
            undo.getUndoManager().markLastUndoNoMerging();
            clip.radius.set(radius);
            undo.submitUndo(true);
            undo.getUndoManager().markLastUndoNoMerging();
        }
        this.controller.panel.actionEditor.fillData();
    }

    private boolean pick(UIContext context, ImpulseActionClip clip)
    {
        Area viewport = this.controller.getGizmoArea();
        if (!viewport.isInside(context.mouseX, context.mouseY)) return false;
        Point center = clip.point.get();
        this.projection.setup(this.controller.panel.getCamera(), viewport, center.x, center.y, center.z);
        this.projection.projection.set(this.controller.getGizmoProjection());
        double radius = Math.max(0.05F, clip.radius.get());
        Vector3d a = new Vector3d();
        Vector3d b = new Vector3d();
        Vector2f sa = new Vector2f();
        Vector2f sb = new Vector2f();
        float best = 36F; // Six UI pixels, independent of distance and zoom.
        boolean hit = false;
        for (int ring = 0; ring < 3; ring++)
        {
            for (int i = 0; i < SEGMENTS; i++)
            {
                point(center, radius, ring, i, a);
                point(center, radius, ring, i + 1, b);
                if (!this.projection.projectToScreen(a, sa) || !this.projection.projectToScreen(b, sb)) continue;
                float dx = sb.x - sa.x, dy = sb.y - sa.y;
                float length = dx * dx + dy * dy;
                if (length < 0.001F) continue;
                float t = Math.max(0F, Math.min(1F, ((context.mouseX - sa.x) * dx + (context.mouseY - sa.y) * dy) / length));
                float x = sa.x + t * dx - context.mouseX, y = sa.y + t * dy - context.mouseY;
                float distance = x * x + y * y;
                if (distance < best)
                {
                    best = distance;
                    this.picked.set(a).lerp(b, t);
                    hit = true;
                }
            }
        }
        return hit;
    }

    private static void point(Point center, double radius, int ring, int index, Vector3d out)
    {
        double angle = Math.PI * 2D * index / SEGMENTS;
        double u = Math.cos(angle) * radius, v = Math.sin(angle) * radius;
        out.set(center.x + (ring == 0 ? 0D : u), center.y + (ring == 0 ? u : ring == 1 ? 0D : v), center.z + (ring == 2 ? 0D : v));
    }

    public void render(WorldRenderContext context)
    {
        ImpulseActionClip clip = this.selected();
        if (clip == null || context.matrices() == null) return;
        Point point = clip.point.get();
        var stack = context.matrices();
        var camera = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        stack.push();
        stack.translate(point.x - camera.x, point.y - camera.y, point.z - camera.z);
        /* Selected editing handles stay visible, matching their screen-space picking. */
        boolean depthTest = WireframeLayers.setDepthTest(false);
        try
        {
            CollisionWireframe.draw(stack, CollisionKind.SPHERE, new Vector3f(Math.max(0.05F, displayedRadius(clip))),
                1F, this.hovered ? 0.95F : 0.58F, this.hovered ? 0.55F : 0F, 1F);
        }
        finally
        {
            WireframeLayers.setDepthTest(depthTest);
            stack.pop();
        }
    }
}
