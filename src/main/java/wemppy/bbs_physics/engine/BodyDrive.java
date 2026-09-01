package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import org.joml.Quaternionf;

/**
 * Pulling a dynamic body towards the pose the animation has for it — the one mechanism behind the
 * "animation strength" handle (§4), used by every rigid thing in a scene: a prop, a ragdoll's bone,
 * a rope's segment, a strand of hair.
 *
 * <p><b>The pull is a velocity, not a teleport.</b> The body is offered the speed that would carry
 * it to the pose over the coming tick, mixed with the speed it already has in the handle's
 * proportion. That single choice is what makes the handle behave: at 1 the mix is the pose exactly
 * and the body arrives; at 0.5 half of last tick's fall survives into this one, so the body sags and
 * trails and an impact that changed its velocity is half kept; at 0 nothing is written and the body
 * keeps flying, which is the throw. Writing the position instead would put the body through whatever
 * stood in the way, and writing the pose's velocity outright — scaled by the handle, without the mix
 * — would erase gravity every tick and leave a weakly animated object hovering.</p>
 *
 * <p><b>The axis and angle are worked out by hand, never through JOML's axis-angle conversion</b>,
 * and that is the scar of a long hunt. On the tick a body is let go it has been standing glued to
 * the animation, so the delta between where it is and where the pose wants it is the identity give
 * or take float dust — and rounding can land that dust a hair <em>above</em> w = 1. JOML takes a
 * square root of {@code 1 - w²}, negative there, behind a guard that catches infinity and walks
 * straight past NaN. The axis comes out NaN, NaN times a zero angle is still NaN, and one poisoned
 * velocity spreads through the joints to every part of a ragdoll in a single solver pass: sane one
 * tick, gone the next, with no runaway for the diagnostics to catch. Clamped, the dust reads as what
 * it is — no turn at all. Whether a given release exploded depended on the last bits of the pose,
 * which is why it came and went with keyframe shuffling.</p>
 *
 * <p>One of these is held per rig rather than per body: it carries the scratch the blend needs, and
 * this runs for every driven body on every simulated tick.</p>
 */
public final class BodyDrive
{
    /**
     * The fastest the pull may ask a body to travel, in blocks per second. The gap to the pose is
     * closed at gap-per-tick, which for any honest animation is modest — but keyframes also cut,
     * and a cut of twenty blocks read as a velocity is four hundred blocks per second handed to
     * the solver with the authority as its only brake. Capped, a cut becomes a fast catch-up over
     * a few ticks instead of a body raking the set; kept safely under the runaway diagnostics'
     * hundred, so a capped pull can never trip them by itself. The fastest swing a film plausibly
     * animates is an order of magnitude below the cap, so ordinary drives never feel it.
     *
     * <p>Read by {@link SwingWindow} too, which caps a release for the same reason and must cap it
     * at the same number: two guards on the same quantity that disagree are one guard and one bug.
     * </p>
     */
    static final float MAX_PULL_SPEED = 60F;

    /** The same cap for the turn: about three revolutions per second, under the runaway line. */
    static final float MAX_PULL_SPIN = 20F;

    /* Where the body actually is, against the target — the difference is what it is given. */
    private final RVec3 currentPosition = new RVec3();
    private final Quat currentRotation = new Quat();
    private final Quaternionf target = new Quaternionf();
    private final Quaternionf delta = new Quaternionf();
    private final Vec3 linear = new Vec3();
    private final Vec3 angular = new Vec3();

    /**
     * Drives one body towards a target transform in scene coordinates.
     *
     * @return whether the velocities were handed over. False means the blend came out non-finite and
     *         nothing was written — the body falls free for this tick, which is the harmless version
     *         of every way a broken pose can go wrong. The caller reports it, because only the
     *         caller knows what to call the thing; {@link #describe()} has the numbers
     */
    public boolean apply(BodyInterface bodies, int id, RVec3 position, Quat rotation, float authority)
    {
        return this.apply(bodies, id, position,
            rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW(), authority);
    }

    /** The same, for a caller whose target rotation is already a JOML quaternion. */
    public boolean apply(BodyInterface bodies, int id, RVec3 position, Quaternionf rotation, float authority)
    {
        return this.apply(bodies, id, position, rotation.x, rotation.y, rotation.z, rotation.w, authority);
    }

    private boolean apply(BodyInterface bodies, int id, RVec3 position, float qx, float qy, float qz, float qw, float authority)
    {
        bodies.getPositionAndRotation(id, this.currentPosition, this.currentRotation);

        Vec3 velocity = bodies.getLinearVelocity(id);
        Vec3 spin = bodies.getAngularVelocity(id);

        /* The speed that would carry the body home this tick, capped — see MAX_PULL_SPEED: past
         * the cap the gap is a cut in the keyframes, not a motion, and the pull becomes a fast
         * catch-up instead of a projectile. */
        float homeX = (float) (position.xx() - this.currentPosition.xx()) / PhysicsWorld.TICK;
        float homeY = (float) (position.yy() - this.currentPosition.yy()) / PhysicsWorld.TICK;
        float homeZ = (float) (position.zz() - this.currentPosition.zz()) / PhysicsWorld.TICK;
        float homeSpeed = (float) Math.sqrt(homeX * homeX + homeY * homeY + homeZ * homeZ);

        if (homeSpeed > MAX_PULL_SPEED)
        {
            float scale = MAX_PULL_SPEED / homeSpeed;

            homeX *= scale;
            homeY *= scale;
            homeZ *= scale;
        }

        this.linear.set(
            PhysicsMath.mix(velocity.getX(), homeX, authority),
            PhysicsMath.mix(velocity.getY(), homeY, authority),
            PhysicsMath.mix(velocity.getZ(), homeZ, authority));

        /* The turn that takes the body from where it is facing to where the pose faces, as an axis
         * it spins around and how far — which is what an angular velocity is. Normalized because
         * the pose's rotation is read off a matrix that may carry scale. */
        this.target.set(qx, qy, qz, qw).normalize();
        this.delta.set(this.currentRotation.getX(), this.currentRotation.getY(), this.currentRotation.getZ(), this.currentRotation.getW()).conjugate();
        this.target.mul(this.delta, this.delta);

        /* Two quaternions describe every turn, one going the short way round and one the long way.
         * Taking the wrong one spins a body that is a degree off almost all the way around. */
        if (this.delta.w < 0F)
        {
            this.delta.set(-this.delta.x, -this.delta.y, -this.delta.z, -this.delta.w);
        }

        /* Clamped, for the reason the class comment spells out in full. */
        float w = Math.min(this.delta.w, 1F);
        float sinHalfSquared = 1F - w * w;
        float speed = 0F;
        float axisX = 0F;
        float axisY = 0F;
        float axisZ = 0F;

        if (sinHalfSquared > 1e-12F)
        {
            float invSinHalf = (float) (1D / Math.sqrt(sinHalfSquared));

            /* Capped for the same reason the travel is: a half-turn cut read as one tick's spin
             * is sixty radians a second, and nothing downstream survives being asked for that. */
            speed = Math.min(2F * (float) Math.acos(w) / PhysicsWorld.TICK, MAX_PULL_SPIN);
            axisX = this.delta.x * invSinHalf;
            axisY = this.delta.y * invSinHalf;
            axisZ = this.delta.z * invSinHalf;
        }

        this.angular.set(
            PhysicsMath.mix(spin.getX(), axisX * speed, authority),
            PhysicsMath.mix(spin.getY(), axisY * speed, authority),
            PhysicsMath.mix(spin.getZ(), axisZ * speed, authority));

        /* The last line of defence before the solver: one non-finite component would take the body
         * — and anything constrained to it — out of the world next step. */
        if (!PhysicsMath.finite(this.linear) || !PhysicsMath.finite(this.angular))
        {
            return false;
        }

        bodies.setLinearAndAngularVelocity(id, this.linear, this.angular);

        /* A body Jolt has put to sleep ignores the velocity it is handed, and a pulled body that
         * settled on the floor for a moment would never take the animation back up again. */
        bodies.activateBody(id);

        return true;
    }

    /** The velocities the last drive worked out, for the caller's report when it refused them. */
    public String describe()
    {
        return "linear (" + this.linear.getX() + ", " + this.linear.getY() + ", " + this.linear.getZ()
            + "), angular (" + this.angular.getX() + ", " + this.angular.getY() + ", " + this.angular.getZ() + ")";
    }
}
