package wemppy.bbs_physics.engine;

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

    /**
     * The handle's <b>grip</b> — how hard the pull holds a body — as against the handle itself,
     * which is how much of the drawn frame the animation still owns.
     *
     * <p>The two were one number, and that is what made a fade read as a switch. The pull is a
     * deadbeat one: at strength s it takes the fraction s out of the gap between body and pose
     * <em>every tick</em>, so even a bare half puts a body back on its keyframes within three ticks
     * and looks exactly as held as a full handle does. The whole visible life of a fade was crammed
     * into its last few percent — and all the way down that slope the pull was scrubbing off the
     * very speed the animation had given the body, so a thing let go slowly was let go with
     * nothing. Both halves of "the fade still snaps, and then it hangs in the air".</p>
     *
     * <p>Squared, the grip falls away well ahead of the handle: three quarters of the way down it is
     * a little over a half, halfway down a quarter. What holds the object at that point is the drawn
     * blend instead ({@code PhysicsBodyState.getWeight}), which costs a body no momentum at all. The
     * two ends are untouched — 1 holds outright, 0 lets go — so nothing keyed as a hard release
     * changes.</p>
     */
    public static float grip(float authority)
    {
        return authority * authority;
    }

    /**
     * The hardest a knob at its very top may bite: nineteen twentieths of a body's speed gone in one
     * tick. Not 1, because both conversions below take a logarithm and "all of it" has no answer —
     * and because a body that keeps a twentieth of its motion already reads as stopped.
     */
    private static final float DAMPING_CEILING = 0.95F;

    /**
     * The author's "damping" knob turned into the number Jolt wants — <b>the one place the two
     * scales meet</b>, because they are not the same scale at all, and reading the knob as if it
     * were Jolt's own is what made everything here feel sharp.
     *
     * <p><b>The knob is a fraction of speed lost over a film tick</b>, which is BBS's own scale: its
     * chain solver multiplies by {@code pow(1 - damping, h)} every sub-step and ships a default of
     * 0.15, so hair there sheds fifteen percent of its motion per tick and settles the way an author
     * expects. Jolt's damping is a <em>rate per second</em>. Handing it 0.2 does not mean "a fifth
     * per tick", it means a fifth per second — which the {@code DampingSmoke} stand measures at
     * seven tenths of one percent of a strand's speed per tick, a thirtieth of the intended bite.
     * Strands and sheets kept nearly all their energy, so nothing ever calmed down, and the motion
     * read as sharp.</p>
     *
     * <p>Jolt sheds the speed <em>linearly</em>, {@code v × (1 - rate × dt)}, once per piece it cuts
     * the tick into — so inverting it is a matter of counting the pieces, and this is that inversion.
     * The two callers count differently and the stand is what established the numbers: a rigid body
     * is damped once per collision sub-step, a soft body's vertices once per solver iteration
     * <em>within</em> each sub-step. Both land on the asked-for loss to a tenth of a percent, at any
     * sub-step count — which is the point, since a quality setting must not change how a film
     * feels.</p>
     *
     * @param knob   the author's 0..1 damping value: 0 sheds nothing at all, 0.15 is BBS's own
     *               default, 1 is as good as stopped
     * @param pieces how many times the engine will apply the damping across one tick
     */
    private static float damping(float knob, int pieces)
    {
        float loss = Math.min(Math.max(knob, 0F), DAMPING_CEILING);

        if (loss <= 0F)
        {
            return 0F;
        }

        int count = Math.max(1, pieces);

        /* What has to survive each piece for the tick as a whole to land on the knob. */
        float perPiece = (float) Math.pow(1F - loss, 1D / count);

        return (1F - perPiece) * count / PhysicsWorld.TICK;
    }

    /**
     * The knob for a rigid body — a rope's segment, a bone of hair. Damped once per collision
     * sub-step.
     *
     * @param steps {@link PhysicsWorld#getCollisionSteps()}
     */
    public static float bodyDamping(float knob, int steps)
    {
        return damping(knob, steps);
    }

    /**
     * The knob for a soft body — a sheet of cloth, an inflated ball. Damped once per solver
     * iteration, and the solver runs its iterations inside every collision sub-step, so the tick is
     * cut into the product of the two.
     *
     * <p>Told apart from {@link #bodyDamping} by measurement, not by reading: a first pass damped
     * both the same way and the stand caught the sheet shedding 97% where 95% was asked for. Ten
     * iterations is ten bites out of the speed instead of one, and the rate has to be a tenth as
     * fierce to arrive at the same place.</p>
     *
     * @param iterations the body's own {@code numIterations}
     */
    public static float softDamping(float knob, int steps, int iterations)
    {
        return damping(knob, steps * Math.max(1, iterations));
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
