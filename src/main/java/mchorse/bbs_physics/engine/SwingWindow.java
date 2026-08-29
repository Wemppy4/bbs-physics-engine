package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The speed a body is let go with, read off the <em>swing</em> rather than off the last twentieth
 * of a second before the hand opened.
 *
 * <p><b>Why the last tick is the wrong tick.</b> A body the animation owns is steered by
 * {@link KinematicDrive}, which hands Jolt the velocity that arrives at this tick's pose — and Jolt
 * keeps that velocity when the body turns dynamic, which is what makes a throw a throw. It works
 * perfectly for an animation that is still travelling when the handle drops. But an author keys a
 * throw the way an author keys everything: a pose here, a pose there, smooth interpolation between
 * them. Smooth interpolation <em>arrives at rest</em> — the velocity at a keyframe is zero by
 * construction — so a hand that swung across the set at four blocks a second is, on the very frame
 * the object leaves it, doing almost nothing. The object inherited that almost-nothing and dropped
 * where it stood, and no amount of work on the release itself could have fixed it: the speed was
 * gone before the release was reached.</p>
 *
 * <p><b>So the release reads a window.</b> The last few ticks of animated pose are kept, and the
 * body is let go with the fastest single tick among them — the swing at its strongest, which is
 * what "he threw it" means and what an author is picturing. Games that let a player throw something
 * held in the hand do exactly this and for exactly this reason: the last frame before a release is
 * almost always a deceleration.</p>
 *
 * <p><b>What it will not do is invent a throw.</b> The window is the animation's own motion, so an
 * object the keyframes carried somewhere and set down is let go with nothing, because nothing is
 * what its keyframes were doing. A throw with no swing behind it is what the impulse clip is for
 * (§5.5). And the newest tick in the window is the velocity the body would have inherited anyway,
 * so taking the fastest can only ever help a release, never slow one down.</p>
 *
 * <p>Held per rig and pushed once a tick while the animation owns the body; a cut in the keyframes,
 * a reset, or the animation taking the body back throws the window away, because a jump across the
 * set is not a swing and must never be read as one.</p>
 */
public final class SwingWindow
{
    /**
     * How many ticks back the swing is looked for — a fifth of a second. Long enough to reach past
     * the tail of an eased keyframe, which is where the speed went; short enough that it is still
     * the same gesture, and not something the arm was doing a moment before.
     */
    private static final int TICKS = 4;

    /* The animated pose as it stood on each of the last few ticks, in scene coordinates, oldest
     * overwritten first. One more slot than there are ticks, because a tick of motion is a pair. */
    private final Vector3f[] positions = new Vector3f[TICKS + 1];
    private final Quaternionf[] rotations = new Quaternionf[TICKS + 1];

    private final Quaternionf delta = new Quaternionf();
    private final Vector3f linear = new Vector3f();
    private final Vector3f angular = new Vector3f();
    private final Vec3 scratchLinear = new Vec3();
    private final Vec3 scratchAngular = new Vec3();

    /** How many ticks have been pushed since the window was last thrown away. */
    private int written;

    public SwingWindow()
    {
        for (int i = 0; i < this.positions.length; i++)
        {
            this.positions[i] = new Vector3f();
            this.rotations[i] = new Quaternionf();
        }
    }

    /**
     * Forgets everything: the body was put somewhere rather than moved there, so the ticks either
     * side of that are not a motion and the distance between them is not a speed.
     */
    public void clear()
    {
        this.written = 0;
    }

    /** One tick of animated pose, in scene coordinates — the same target the body was steered to. */
    public void push(RVec3 position, Quat rotation)
    {
        int at = this.written % this.positions.length;

        this.positions[at].set((float) position.xx(), (float) position.yy(), (float) position.zz());
        this.rotations[at].set(rotation.getX(), rotation.getY(), rotation.getZ(), rotation.getW());

        this.written++;
    }

    /**
     * Lets the body go with the swing: the fastest tick the window holds, linear and angular worked
     * out separately.
     *
     * @return whether a velocity was handed over. False means the window has nothing to say — the
     *         body has only just been picked up, or it was standing still — and the body keeps
     *         whatever it inherited, which in that case is the right answer anyway
     */
    public boolean release(BodyInterface bodies, int id)
    {
        int pairs = Math.min(this.written - 1, TICKS);

        if (pairs < 1)
        {
            return false;
        }

        float fastest = 0F;
        float quickest = 0F;

        this.linear.zero();
        this.angular.zero();

        for (int i = 0; i < pairs; i++)
        {
            int to = (this.written - 1 - i) % this.positions.length;
            int from = (this.written - 2 - i) % this.positions.length;

            float x = (this.positions[to].x - this.positions[from].x) / PhysicsWorld.TICK;
            float y = (this.positions[to].y - this.positions[from].y) / PhysicsWorld.TICK;
            float z = (this.positions[to].z - this.positions[from].z) / PhysicsWorld.TICK;
            float speed = (float) Math.sqrt(x * x + y * y + z * z);

            if (speed > fastest)
            {
                fastest = speed;

                this.linear.set(x, y, z);
            }

            /* The turn from one tick's facing to the next, as an axis and how far around it — the
             * same arithmetic BodyDrive does, and clamped with the same care: the two rotations can
             * be equal to the last bit, and a w that rounds a hair above 1 takes the square root of
             * a negative number straight past JOML's guard and poisons the body with NaN. */
            this.delta.set(this.rotations[from].x, this.rotations[from].y, this.rotations[from].z, this.rotations[from].w).conjugate();
            this.rotations[to].mul(this.delta, this.delta);

            if (this.delta.w < 0F)
            {
                this.delta.set(-this.delta.x, -this.delta.y, -this.delta.z, -this.delta.w);
            }

            float w = Math.min(this.delta.w, 1F);
            float sinHalfSquared = 1F - w * w;

            if (sinHalfSquared <= 1e-12F)
            {
                continue;
            }

            float spin = 2F * (float) Math.acos(w) / PhysicsWorld.TICK;

            if (spin > quickest)
            {
                float invSinHalf = (float) (1D / Math.sqrt(sinHalfSquared));

                quickest = spin;

                this.angular.set(this.delta.x * invSinHalf, this.delta.y * invSinHalf, this.delta.z * invSinHalf).mul(spin);
            }
        }

        if (fastest <= 0F && quickest <= 0F)
        {
            return false;
        }

        /* Capped exactly as the pull is: a cut ought to have thrown the window away already, but a
         * release is not the place to find out that one slipped through. */
        if (fastest > BodyDrive.MAX_PULL_SPEED)
        {
            this.linear.mul(BodyDrive.MAX_PULL_SPEED / fastest);
        }

        if (quickest > BodyDrive.MAX_PULL_SPIN)
        {
            this.angular.mul(BodyDrive.MAX_PULL_SPIN / quickest);
        }

        this.scratchLinear.set(this.linear.x, this.linear.y, this.linear.z);
        this.scratchAngular.set(this.angular.x, this.angular.y, this.angular.z);

        if (!PhysicsMath.finite(this.scratchLinear) || !PhysicsMath.finite(this.scratchAngular))
        {
            return false;
        }

        bodies.setLinearAndAngularVelocity(id, this.scratchLinear, this.scratchAngular);
        bodies.activateBody(id);

        return true;
    }

    /** The velocity the last release handed over, for the caller's report. */
    public String describe()
    {
        return "linear (" + this.linear.x + ", " + this.linear.y + ", " + this.linear.z
            + "), angular (" + this.angular.x + ", " + this.angular.y + ", " + this.angular.z + ")";
    }
}
