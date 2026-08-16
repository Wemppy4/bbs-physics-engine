package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import mchorse.bbs_physics.client.collision.CollisionShapes;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.engine.PhysicsCache;
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
 * <p>Both transforms come out of {@link PhysicsCache} rather than out of Jolt: the world is a
 * recorder now, not a source of pictures, and it may be hundreds of ticks ahead of the cursor. The
 * body writes itself into the recording as each tick is simulated and reads itself back out as each
 * frame is drawn.</p>
 *
 * <p>The debug overlay draws each of the body's {@link Shape}s in the body's frame — a compound
 * collider is several of them, a plain body is one. What is drawn is exactly what the simulation
 * collides with, down to the shape being round when it is round, because an overlay that prettied
 * things up would be useless for the one thing it exists for: seeing where the engine thinks the
 * shapes are.</p>
 */
public class SceneBody
{
    public final int id;

    public final float red;
    public final float green;
    public final float blue;

    private final List<Shape> shapes = new ArrayList<>(1);

    /** This body's slot in the recording, handed out when the scene registers it. */
    private int channel = -1;

    /** Whether the frame being drawn has a recorded transform at all — see {@link #isKnown()}. */
    private boolean known;

    /** Whether the last tick recorded put this body nowhere at all — see {@link #record}. */
    private boolean lost;

    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f position = new Vector3f();

    private final Quaternionf previousRotation = new Quaternionf();
    private final Quaternionf rotation = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();

    /** One shape inside the body: what it is, how big, and where it sits in the body's frame. */
    public record Shape(CollisionKind kind, Vector3f half, Vector3f offset, Quaternionf rotation)
    {}

    public SceneBody(int id, float red, float green, float blue)
    {
        this.id = id;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** A body that is a single centred box — the floor fallback. */
    public SceneBody(int id, float halfX, float halfY, float halfZ, float red, float green, float blue)
    {
        this(id, red, green, blue);

        this.addShape(new Shape(CollisionKind.BOX, new Vector3f(halfX, halfY, halfZ), new Vector3f(), new Quaternionf()));
    }

    public void addShape(Shape shape)
    {
        this.shapes.add(shape);
    }

    /** Everything one collider is made of, in the order the engine has it. */
    public void addShapes(List<CollisionShapes.SubShape> subs)
    {
        for (CollisionShapes.SubShape sub : subs)
        {
            this.shapes.add(new Shape(sub.kind(), sub.half(), sub.offset(), sub.rotation()));
        }
    }

    public List<Shape> getShapes()
    {
        return this.shapes;
    }

    /** Swaps in a new set of shapes — the collider of a body that has just been rebuilt. */
    public void setShapes(List<CollisionShapes.SubShape> subs)
    {
        this.shapes.clear();
        this.addShapes(subs);
    }

    public void setChannel(int channel)
    {
        this.channel = channel;
    }

    /**
     * Whether this body has a transform for the frame being drawn. False on a frame the recording
     * has not reached, where the debug overlay draws nothing for it rather than a shape sitting at
     * a stale place — an overlay exists to say where the engine thinks things are, and a shape left
     * over from another tick would say something untrue.
     */
    public boolean isKnown()
    {
        return this.known;
    }

    /**
     * Writes where the body stands into the recording, for the tick that has just been simulated.
     *
     * <p>Unless it stands nowhere. A body the solver has lost — an impossible impulse takes a few
     * ticks of doubling to reach infinity, and one more to reach "not a number" — is recorded as
     * silence, exactly like a frame the recording has not reached, so the overlay draws nothing for
     * it rather than a shape at a place that does not exist.</p>
     *
     * <p>Which was the whole problem with drawing it anyway: the shape simply vanished, and a
     * vanished shape is indistinguishable from a shape that was never there. Counted here, it
     * becomes a number the readout can say out loud — see {@link SceneStatus#lost()}.</p>
     */
    public void record(BodyInterface bodies, PhysicsCache cache, int tick)
    {
        bodies.getPositionAndRotation(this.id, this.scratchPosition, this.scratchRotation);

        this.position.set(this.scratchPosition.x(), this.scratchPosition.y(), this.scratchPosition.z());
        this.rotation.set(this.scratchRotation.getX(), this.scratchRotation.getY(), this.scratchRotation.getZ(), this.scratchRotation.getW());

        this.lost = !finite(this.position.x) || !finite(this.position.y) || !finite(this.position.z)
            || !finite(this.rotation.x) || !finite(this.rotation.y) || !finite(this.rotation.z) || !finite(this.rotation.w);

        cache.write(tick, this.channel, this.position, this.rotation, this.lost ? PhysicsCache.SILENT : 1F);
    }

    /**
     * Whether the simulation lost this body on the tick it last recorded. Clears itself: a body put
     * back where the animation has it — a restart, an edit — records a place again.
     */
    public boolean isLost()
    {
        return this.lost;
    }

    /** Whether a coordinate is a place at all — not infinite, not the result of dividing by zero. */
    private static boolean finite(float value)
    {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /**
     * Takes the body's transform for {@code tick} out of the recording.
     *
     * <p>A paused film needs nothing special from this. Reading the same tick again lands the same
     * numbers in both slots, so the interpolation collapses on its own — the old {@code freeze()},
     * and the shaking-while-frozen bug it was written for, are gone with the checkpoint design.</p>
     *
     * @param teleport whether the film jumped rather than advanced one tick, in which case there is
     *                 no meaningful previous transform to be drawn sliding out of
     */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        this.previousPosition.set(this.position);
        this.previousRotation.set(this.rotation);

        boolean had = this.known;

        this.known = cache.read(tick, this.channel, this.position, this.rotation);

        if (this.known && (teleport || !had))
        {
            this.previousPosition.set(this.position);
            this.previousRotation.set(this.rotation);
        }
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
