package mchorse.bbs_physics.client.scene;

import mchorse.bbs_physics.client.collision.CollisionWireframe;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws a scene's collision shapes as wireframe boxes, so what the engine thinks is there can be
 * compared against what the model looks like. This is how a physics bug is diagnosed — a collider
 * in the wrong place is obvious the moment it is drawn and invisible otherwise.
 *
 * <p>Drawn with the world's depth buffer intact, so a body behind a wall is behind the wall: the
 * overlay is meant to show where things are, and one that floated over the scene would be lying
 * about depth.</p>
 */
public final class SceneDebugRenderer
{
    private static final Vector3f POSITION = new Vector3f();
    private static final Quaternionf ROTATION = new Quaternionf();

    private SceneDebugRenderer()
    {}

    public static void render(FilmScene scene, WorldRenderContext context)
    {
        Camera camera = context.camera();

        if (camera == null || context.matrixStack() == null)
        {
            return;
        }

        MatrixStack stack = context.matrixStack();
        float transition = context.tickDelta();

        /* Everything is drawn relative to the camera, as the world pass expects: the scene's
         * origin brings simulation coordinates back into the world, the camera position takes them
         * into the render's frame. */
        double x = scene.getOriginX() - camera.getPos().x;
        double y = scene.getOriginY() - camera.getPos().y;
        double z = scene.getOriginZ() - camera.getPos().z;

        for (SceneBody body : scene.getBodies())
        {
            body.getPosition(transition, POSITION);
            body.getRotation(transition, ROTATION);

            stack.push();
            stack.translate(x + POSITION.x, y + POSITION.y, z + POSITION.z);
            stack.multiply(ROTATION);

            /* Each shape sits at its own place inside the body's frame — a compound collider is
             * several of them, a plain body is one centred shape. */
            for (SceneBody.Shape shape : body.getShapes())
            {
                Vector3f offset = shape.offset();

                stack.push();
                stack.translate(offset.x, offset.y, offset.z);
                stack.multiply(shape.rotation());

                CollisionWireframe.draw(stack, shape.kind(), shape.half(), body.red, body.green, body.blue, 1F);

                stack.pop();
            }

            stack.pop();
        }
    }
}
