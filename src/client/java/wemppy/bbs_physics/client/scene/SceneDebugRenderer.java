package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.client.collision.CollisionWireframe;
import wemppy.bbs_physics.client.collision.JointWireframe;
import wemppy.bbs_physics.collision.CollisionKind;
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
 * <p>The film's impulse clips are marked too: a cross at the point, the ring of the radius, an
 * arrow for a directed push. The point is a number typed into a panel — world coordinates, which
 * the first live run proved nobody can aim by eye — and the overlay is the one place that can show
 * where the blast actually is before the author wonders why nothing moved. Dim while the film is
 * elsewhere, bright on the frames the clip covers.</p>
 *
 * <p>Drawn with the world's depth buffer intact, so a body behind a wall is behind the wall: the
 * overlay is meant to show where things are, and one that floated over the scene would be lying
 * about depth.</p>
 */
public final class SceneDebugRenderer
{
    private static final Vector3f POSITION = new Vector3f();
    private static final Quaternionf ROTATION = new Quaternionf();

    /** The impulse marks' colour — the clip's own timeline orange, dimmed when the cursor is away. */
    private static final int IMPULSE_NOW = 0xffff9500;
    private static final int IMPULSE_ELSEWHERE = 0x59ff9500;

    private static final Vector3f CENTER = new Vector3f();
    private static final Vector3f ARROW = new Vector3f();
    private static final Vector3f RADIUS = new Vector3f();

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
        float transition = context.tickCounter().getTickDelta(false);

        /* Everything is drawn relative to the camera, as the world pass expects: the scene's
         * origin brings simulation coordinates back into the world, the camera position takes them
         * into the render's frame. */
        double x = scene.getOriginX() - camera.getPos().x;
        double y = scene.getOriginY() - camera.getPos().y;
        double z = scene.getOriginZ() - camera.getPos().z;

        for (SceneBody body : scene.getBodies())
        {
            if (!body.isKnown())
            {
                /* The recording has not reached this frame, so there is nothing true to draw. A
                 * shape left over from an older tick would be the overlay telling a lie, which is
                 * worse than the overlay being empty — the frame is showing plain animation and the
                 * readout says so in words (Р8.1). */
                continue;
            }

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

                CollisionWireframe.draw(stack, shape.kind(), shape.half(), shape.surface(), body.red, body.green, body.blue, 1F);

                stack.pop();
            }

            stack.pop();
        }

        drawImpulses(scene, stack, camera);
    }

    /**
     * Marks every impulse clip of the film at its point: a cross, the ring of its reach, and the
     * shove's arrow when it is directed rather than radial. Drawn from the film's data directly —
     * the marks must follow the panel's numbers as they are typed, not the recording.
     */
    private static void drawImpulses(FilmScene scene, MatrixStack stack, Camera camera)
    {
        Film film = scene.getFilm();

        if (film == null)
        {
            return;
        }

        for (Replay replay : film.replays.getList())
        {
            if (!replay.enabled.get())
            {
                /* A switched-off replay gets no actor, so the scene never reads its clips and the
                 * push cannot happen. Marking it anyway would be the overlay promising a blast
                 * that is not coming — and the mark is here precisely because the point cannot be
                 * aimed by eye. */
                continue;
            }

            int local = replay.getTick(scene.getFilmTick());

            for (ImpulseActionClip clip : replay.actions.getClips(ImpulseActionClip.class))
            {
                if (!clip.enabled.get())
                {
                    continue;
                }

                Point point = clip.point.get();
                float radius = Math.max(clip.radius.get(), 0.05F);
                int color = clip.isInside(local) ? IMPULSE_NOW : IMPULSE_ELSEWHERE;

                stack.push();
                stack.translate(
                    point.x - camera.getPos().x,
                    point.y - camera.getPos().y,
                    point.z - camera.getPos().z);

                /* The cross at the point — and, for a directed push, the arrow of its shove, drawn
                 * out to the reach so the pair reads as "from here, this way, this far". */
                CENTER.zero();

                if (clip.radial.get())
                {
                    JointWireframe.draw(stack, CENTER, null, color);
                }
                else
                {
                    Point direction = clip.direction.get();

                    ARROW.set((float) direction.x, (float) direction.y, (float) direction.z);

                    if (ARROW.lengthSquared() > 1.0e-12F && ARROW.isFinite())
                    {
                        ARROW.normalize().mul(radius);
                    }
                    else
                    {
                        ARROW.zero();
                    }

                    JointWireframe.draw(stack, CENTER, ARROW, color);
                }

                RADIUS.set(radius);
                CollisionWireframe.draw(
                    stack, CollisionKind.SPHERE, RADIUS,
                    ((color >> 16) & 0xFF) / 255F,
                    ((color >> 8) & 0xFF) / 255F,
                    (color & 0xFF) / 255F,
                    ((color >> 24) & 0xFF) / 255F);

                stack.pop();
            }
        }
    }
}
