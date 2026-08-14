package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A body's transform on the render side, in the two ticks the current frame sits between.
 *
 * <p>Physics steps at the film's 20 Hz while the game draws at whatever the monitor does, so a
 * body drawn straight from Jolt would visibly stutter. Keeping the previous tick's transform next
 * to the current one lets the frame be drawn between them — the same trick Minecraft plays on its
 * own entities with {@code tickDelta}.</p>
 *
 * <p>The debug overlay draws each of the body's {@link Shape}s in the body's frame — a compound
 * bone collider is several boxes, a plain body is one. What is drawn is exactly what the
 * simulation collides with (capsules and spheres are drawn as their bounding boxes), because an
 * overlay that prettied things up would be useless for the one thing it exists for: seeing where
 * the engine thinks the shapes are.</p>
 */
public class SceneBody
{
    public final int id;

    public final float red;
    public final float green;
    public final float blue;

    private final List<Shape> shapes = new ArrayList<>(1);

    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f position = new Vector3f();

    private final Quaternionf previousRotation = new Quaternionf();
    private final Quaternionf rotation = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();

    /** One shape inside the body: half extents, and where and how it sits in the body's frame. */
    public record Shape(Vector3f half, Vector3f offset, Quaternionf rotation)
    {}

    public SceneBody(int id, float red, float green, float blue)
    {
        this.id = id;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** A body that is a single centred box — the floor fallback and the physics body form. */
    public SceneBody(int id, float halfX, float halfY, float halfZ, float red, float green, float blue)
    {
        this(id, red, green, blue);

        this.addShape(new Vector3f(halfX, halfY, halfZ), new Vector3f(), new Quaternionf());
    }

    public void addShape(Vector3f half, Vector3f offset, Quaternionf rotation)
    {
        this.shapes.add(new Shape(half, offset, rotation));
    }

    public List<Shape> getShapes()
    {
        return this.shapes;
    }

    /** Resizes a single-box body in place — the author dragging the collider size slider. */
    public void setHalfExtents(float halfX, float halfY, float halfZ)
    {
        if (!this.shapes.isEmpty())
        {
            this.shapes.get(0).half().set(halfX, halfY, halfZ);
        }
    }

    /**
     * Pulls the body's transform out of Jolt.
     *
     * @param teleport whether to drop the previous transform on top of the new one, so the body is
     *                 drawn where it is instead of sliding there from where it used to be. True
     *                 after a scrub, a restore or any jump of more than one tick
     */
    public void sample(BodyInterface bodies, boolean teleport)
    {
        this.previousPosition.set(this.position);
        this.previousRotation.set(this.rotation);

        bodies.getPositionAndRotation(this.id, this.scratchPosition, this.scratchRotation);

        this.position.set(this.scratchPosition.x(), this.scratchPosition.y(), this.scratchPosition.z());
        this.rotation.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW());

        if (teleport)
        {
            this.previousPosition.set(this.position);
            this.previousRotation.set(this.rotation);
        }
    }

    /**
     * Collapses the interpolation onto the current transform, so the body is drawn exactly where
     * it stands instead of being swung between the last two ticks.
     *
     * <p>Needed the moment the simulation stops advancing — a paused editor. The frame's
     * {@code tickDelta} keeps sweeping 0 to 1 whether or not physics moved, so a body left with
     * two different transforms goes on rocking between them forever, which reads as a frozen
     * scene that is somehow still shaking.</p>
     */
    public void freeze()
    {
        this.previousPosition.set(this.position);
        this.previousRotation.set(this.rotation);
    }

    public Vector3f getPosition(float transition, Vector3f out)
    {
        return this.previousPosition.lerp(this.position, transition, out);
    }

    public Quaternionf getRotation(float transition, Quaternionf out)
    {
        return this.previousRotation.slerp(this.rotation, transition, out);
    }
}
