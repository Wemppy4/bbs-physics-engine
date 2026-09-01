package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import org.joml.Vector3f;

/**
 * One firing of an impulse clip (Э5), worked out once and offered to everything in the scene: a
 * point, a reach, and the velocity change anything inside the reach takes.
 *
 * <p><b>The knob is a speed, not a force.</b> An author setting up "the blast throws him off his
 * feet" thinks in how fast things fly, and a velocity change reads the same on a crate and on a
 * ragdoll's head; a true impulse (mass times that) would make the same knob fling a bottle across
 * the set and barely nudge a character, which on a film set reads as the knob doing nothing. Mass
 * still matters where it is felt — in what the flying thing does to whatever it hits.</p>
 *
 * <p>Two shapes of push. <b>Radial</b> — an explosion: everything flies away from the point, full
 * strength at the centre, fading to nothing at the edge of the radius. <b>Directed</b> — a shove or
 * a blast wave: everything inside the radius is pushed the same way, with the same fade. The fade
 * is linear in distance; a body is measured at its centre, a sheet of cloth vertex by vertex, which
 * is what makes a blast lift the near corner of a cape before the far one.</p>
 *
 * <p>Velocities are <em>added</em>, never written: a push lands on top of whatever motion the thing
 * already had, the way a real shove does. Only dynamic bodies take one — a kinematic bone at a full
 * handle belongs to the keyframes, and physics has no business kicking the animation.</p>
 */
public class SceneImpulse
{
    /* The epicentre, in the scene's own coordinates. */
    private final float x;
    private final float y;
    private final float z;

    private final float radius;
    private final float strength;

    /** Null for a radial push; a unit vector for a directed one. */
    private final Vector3f direction;

    /* Scratch, allocated once per firing rather than per body. */
    private final Vector3f velocity = new Vector3f();
    private final Vec3 scratch = new Vec3();

    private SceneImpulse(float x, float y, float z, float radius, float strength, Vector3f direction)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.strength = strength;
        this.direction = direction;
    }

    /**
     * Builds a push, or returns null when it could not move anything anyway — no reach, no
     * strength, or a directed push whose direction is the zero vector.
     *
     * @param x         the epicentre, already in scene coordinates (world minus the scene's origin)
     * @param radius    how far the push reaches, in blocks
     * @param strength  the velocity change at the epicentre, in blocks per second
     * @param direction the way a directed push shoves, in world axes — any length, normalized here
     *                  — or null for a radial one
     */
    public static SceneImpulse of(float x, float y, float z, float radius, float strength, Vector3f direction)
    {
        if (!(radius > 0F) || strength == 0F || !Float.isFinite(strength)
            || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
        {
            return null;
        }

        if (direction != null)
        {
            if (direction.lengthSquared() < 1.0e-12F || !direction.isFinite())
            {
                return null;
            }

            direction = new Vector3f(direction).normalize();
        }

        return new SceneImpulse(x, y, z, radius, strength, direction);
    }

    /**
     * The velocity change at a point, into {@code out}.
     *
     * @return false when the point is outside the reach — {@code out} says nothing then
     */
    public boolean velocityAt(float px, float py, float pz, Vector3f out)
    {
        float dx = px - this.x;
        float dy = py - this.y;
        float dz = pz - this.z;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance >= this.radius)
        {
            return false;
        }

        float scale = this.strength * (1F - distance / this.radius);

        if (this.direction != null)
        {
            out.set(this.direction).mul(scale);

            return true;
        }

        if (distance < 1.0e-6F)
        {
            /* A body standing exactly on the epicentre has no "away from it" — straight up is what
             * an explosion under something does. */
            out.set(0F, scale, 0F);

            return true;
        }

        out.set(dx / distance * scale, dy / distance * scale, dz / distance * scale);

        return true;
    }

    /**
     * Offers the push to one rigid body: a dynamic one inside the reach takes the velocity change
     * for where it stands, on top of what it already carries. Anything else is left alone.
     */
    public void apply(BodyInterface bodies, int id)
    {
        if (bodies.getMotionType(id) != EMotionType.Dynamic)
        {
            return;
        }

        RVec3 position = bodies.getCenterOfMassPosition(id);

        if (!this.velocityAt((float) position.xx(), (float) position.yy(), (float) position.zz(), this.velocity))
        {
            return;
        }

        Vec3 current = bodies.getLinearVelocity(id);

        this.scratch.set(
            current.getX() + this.velocity.x,
            current.getY() + this.velocity.y,
            current.getZ() + this.velocity.z);

        bodies.setLinearVelocity(id, this.scratch);

        /* A sleeping body ignores a written velocity, and a crate that settled would sleep through
         * the blast. */
        bodies.activateBody(id);
    }
}
