package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;

/**
 * Moving a kinematic body to where the animation has it this tick — the counterpart of
 * {@link BodyDrive} for the bodies physics never owns: an actor's bones, a ragdoll at a full
 * handle, a rope's pins.
 *
 * <p><b>Steered when the target is a motion, placed when it is a cut.</b> {@code moveKinematic}
 * hands Jolt the velocity that arrives at the target in one tick, and that velocity is the whole
 * point: it is what a shoulder shoves a crate with. But keyframes do not only describe motion — a
 * film cuts, an actor is keyed from one side of the set to the other in a single frame — and a
 * "velocity" across a cut is the distance divided by a twentieth of a second. The body sweeps the
 * whole way ({@code LinearCast} sweeps precisely so that nothing is skipped), batting everything on
 * the path aside at hundreds of blocks per second, and whatever it was jointed to inherits the
 * swing. One keyed teleport read as an explosion.</p>
 *
 * <p>So a target no honest animation reaches in one tick — farther than {@value #MAX_STEP} blocks,
 * or turned more than half-way round — is treated as what it is: a cut. The body is put there and
 * stopped, exactly as a scene reset puts it, and nothing between the two places ever feels it.
 * Below the threshold nothing changes at all, which is what makes this safe to use everywhere:
 * the fastest swing a film plausibly animates is well under it.</p>
 */
public final class KinematicDrive
{
    /**
     * The farthest a kinematic body may be steered in one tick, in blocks. Five blocks per tick is
     * a hundred blocks per second — the same "no character reaches this honestly" line the ragdoll
     * runaway diagnostics draw, and about ten times the fastest hand swing a film animates.
     */
    private static final float MAX_STEP = 5F;
    private static final double MAX_STEP_SQUARED = MAX_STEP * MAX_STEP;

    /**
     * How far a body may be turned in one tick before the turn is a cut: past 120°, which is the
     * quaternion dot falling under cos(60°). A spin that fast — over three revolutions a second,
     * sustained — is not something keyframes describe by accident short of a cut.
     */
    private static final float MAX_TURN_DOT = 0.5F;

    private final RVec3 currentPosition = new RVec3();
    private final Quat currentRotation = new Quat();

    /**
     * Steers the body to the target over the coming tick, or places it there outright when the
     * distance is a cut rather than a motion.
     */
    public void move(BodyInterface bodies, int id, RVec3 target, Quat rotation)
    {
        bodies.getPositionAndRotation(id, this.currentPosition, this.currentRotation);

        double dx = target.xx() - this.currentPosition.xx();
        double dy = target.yy() - this.currentPosition.yy();
        double dz = target.zz() - this.currentPosition.zz();

        boolean cut = dx * dx + dy * dy + dz * dz > MAX_STEP_SQUARED;

        if (!cut)
        {
            float dot = Math.abs(
                this.currentRotation.getX() * rotation.getX()
                + this.currentRotation.getY() * rotation.getY()
                + this.currentRotation.getZ() * rotation.getZ()
                + this.currentRotation.getW() * rotation.getW());

            cut = dot < MAX_TURN_DOT;
        }

        if (cut)
        {
            this.place(bodies, id, target, rotation);
        }
        else
        {
            bodies.moveKinematic(id, target, rotation, PhysicsWorld.TICK);
        }
    }

    /**
     * Puts the body at the target and stops it — a reset, or a cut. The velocity is zeroed
     * explicitly because a kinematic body's velocity is state: left over from the last steer, it
     * would carry the body away from where it was just put, once per step, until something steers
     * it again.
     */
    public void place(BodyInterface bodies, int id, RVec3 target, Quat rotation)
    {
        bodies.setPositionAndRotation(id, target, rotation, EActivation.Activate);
        bodies.setLinearAndAngularVelocity(id, PhysicsMath.ZERO, PhysicsMath.ZERO);
    }
}
