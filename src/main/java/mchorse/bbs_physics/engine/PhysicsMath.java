package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.Vec3;
import org.joml.Vector3f;

/**
 * The handful of arithmetic every rig needs, in one place.
 *
 * <p>None of it is deep — a lerp, a finiteness test, an arbitrary perpendicular. What it is, is
 * <em>shared</em>: each of these used to exist as a private copy in four to eight classes, which is
 * how the NaN that took whole ragdolls out of the world came to be fixed in two of the four places
 * that had it. A guard that only some of the callers have is not a guard.</p>
 */
public final class PhysicsMath
{
    /** Shared and never written to — the velocity a body that is being placed is stopped with. */
    public static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    private PhysicsMath()
    {}

    /**
     * The authority blend: how much of the animation's answer to take over the simulation's.
     *
     * <p>At 1 the animated value exactly, at 0 the physical one untouched, and proportionally in
     * between — which is what makes the handle a fade rather than a switch. Applied to
     * <em>velocities</em> at every call site, deliberately: writing positions would put a body
     * through whatever stood in the way, and writing the animation's velocity outright would erase
     * gravity every tick and leave a weakly animated object hanging in the air instead of sagging.
     * </p>
     */
    public static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }

    /** Whether a velocity may be handed to the solver at all — one bad component loses the body. */
    public static boolean finite(Vec3 velocity)
    {
        return Float.isFinite(velocity.getX()) && Float.isFinite(velocity.getY()) && Float.isFinite(velocity.getZ());
    }

    /** Whether a point is a place at all — not infinite, not the result of dividing by zero. */
    public static boolean finite(Vector3f point)
    {
        return Float.isFinite(point.x) && Float.isFinite(point.y) && Float.isFinite(point.z);
    }

    public static boolean finite(double value)
    {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Some unit vector at right angles to {@code axis} — the reference direction a swing-twist joint
     * measures its twist from, and the plane a cone is built around.
     *
     * <p>Which one it is does not matter as long as the same axis always gets the same answer:
     * scene assembly and the viewport preview both ask, and a joint drawn against one reference and
     * built against another is a preview that lies. The degenerate case — an axis parallel to the
     * helper, where the cross product collapses — answers with a fixed direction rather than the NaN
     * a normalization of nothing produces.</p>
     */
    public static Vector3f perpendicular(Vector3f axis)
    {
        Vector3f helper = Math.abs(axis.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);
        Vector3f plane = helper.cross(axis, new Vector3f());

        if (plane.lengthSquared() < 1e-12F)
        {
            return new Vector3f(1F, 0F, 0F);
        }

        return plane.normalize();
    }
}
