package wemppy.bbs_physics.client.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.film.replays.Replay;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;
import wemppy.bbs_physics.client.collision.CollisionWireframe;
import wemppy.bbs_physics.client.collision.JointWireframe;
import wemppy.bbs_physics.ragdoll.DeathReplay;

/** Hit markers from the film and frozen collider poses captured when an impact really fired. */
public final class DeathImpactRenderer
{
    private DeathImpactRenderer() {}

    static void draw(FilmScene scene, MatrixStack stack, Camera camera)
    {
        if (scene.getFilm() == null) return;
        for (Replay replay : scene.getFilm().replays.getList())
        {
            if (!replay.enabled.get()) continue;
            var clip = DeathReplay.at(replay, Integer.MAX_VALUE);
            if (clip == null || clip.baked.get()) continue;
            var point = clip.point.get();
            var direction = clip.direction.get();
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) continue;
            Vector3f velocity = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);
            float strength = clip.strength.get() * (replay instanceof DeathReplay settings
                ? settings.bbs_physics$deathStrength().get() : 1F);
            if (!velocity.isFinite() || velocity.lengthSquared() < 1e-12F || !Float.isFinite(strength)) continue;
            velocity.normalize().mul(strength);
            int elapsed = replay.getTick(scene.getFilmTick()) - clip.tick.get();
            float alpha = elapsed >= 0 && elapsed <= 20 ? 1F : 0.3F;
            int color = ((int) (255 * alpha) << 24) | 0xffdd35;
            stack.push();
            stack.translate(point.x - camera.getPos().x, point.y - camera.getPos().y, point.z - camera.getPos().z);
            JointWireframe.draw(stack, new Vector3f(), null, color);
            // Length represents the common velocity kick over 0.25 seconds, not the bullet path.
            arrow(stack, new Vector3f(velocity).mul(0.25F), alpha);
            var impact = scene.getDeathImpact(clip);
            if (impact != null)
            {
                Vector3f center = new Vector3f(impact.center()).add(
                    (float) (scene.getOriginX() - point.x),
                    (float) (scene.getOriginY() - point.y),
                    (float) (scene.getOriginZ() - point.z));
                JointWireframe.draw(stack, center, new Vector3f(), 0xffeeeeee);
            }
            stack.pop();

            if (impact == null) continue;
            // This is the part's pose AT CONTACT, not the solver's current (possibly future) pose.
            for (SceneBody body : scene.getBodies())
            {
                if (body.id != impact.bodyId()) continue;
                stack.push();
                stack.translate(scene.getOriginX() + impact.position().x - camera.getPos().x,
                    scene.getOriginY() + impact.position().y - camera.getPos().y,
                    scene.getOriginZ() + impact.position().z - camera.getPos().z);
                stack.multiply(impact.rotation());
                for (SceneBody.Shape shape : body.getShapes())
                {
                    stack.push();
                    stack.translate(shape.offset().x, shape.offset().y, shape.offset().z);
                    stack.multiply(shape.rotation());
                    CollisionWireframe.draw(stack, shape.kind(), shape.half(), shape.surface(), 0.1F, 1F, 0.9F, 1F);
                    stack.pop();
                }
                stack.pop();
                break;
            }
        }
    }

    public static void arrow(MatrixStack stack, Vector3f tip, float alpha)
    {
        float length = tip.length();
        if (!tip.isFinite() || !Float.isFinite(length) || length < 1e-6F) return;
        Vector3f direction = new Vector3f(tip).div(length);
        Vector3f side = new Vector3f(direction).cross(Math.abs(direction.y) < 0.9F
            ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
        Vector3f up = new Vector3f(direction).cross(side);
        float head = Math.min(0.2F, length * 0.3F);
        Vector3f base = new Vector3f(tip).sub(new Vector3f(direction).mul(head));
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        line(buffer, stack, new Vector3f(), tip, alpha);
        for (Vector3f axis : new Vector3f[] {side, up})
        {
            line(buffer, stack, tip, new Vector3f(base).add(new Vector3f(axis).mul(head * 0.5F)), alpha);
            line(buffer, stack, tip, new Vector3f(base).sub(new Vector3f(axis).mul(head * 0.5F)), alpha);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private static void line(BufferBuilder buffer, MatrixStack stack, Vector3f a, Vector3f b, float alpha)
    {
        CollisionWireframe.line(buffer, stack, a.x, a.y, a.z, b.x, b.y, b.z, 1F, 0.4F, 0.1F, alpha);
    }
}
