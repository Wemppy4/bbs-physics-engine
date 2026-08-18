package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.MotorSettings;
import com.github.stephengold.joltjni.SwingTwistConstraint;
import com.github.stephengold.joltjni.enumerate.EMotorState;

/**
 * Setting up the joints a hanging strand is made of — one place, because a rope and a lock of hair
 * are the same mechanism with different numbers.
 */
public final class PhysicsJoints
{
    /**
     * The stiffest a joint's spring is allowed to be, in hertz. Past this the spring is stiffer than
     * the step it is solved in and the strand buzzes instead of holding its shape.
     */
    private static final float SPRING_TOP_HZ = 12F;

    /**
     * How stiff the last joint of a strand is next to the first one — the tip keeps this fraction of
     * the root's stiffness, and everything between is a straight ramp.
     *
     * <p>Taken from BBS's own chain solver, which carries the same constant and says why in one
     * line: a flat stiffness reads stiff and lifeless, and the gradient is what gives a strand a
     * living, whip-like tail. Every joint here used to get the identical spring — precisely the flat
     * case BBS had already learned not to ship. A stiff root and a loose end is also what hair and
     * rope actually do: the root is held by the scalp or the knot, the tip is held by nothing.</p>
     */
    private static final float TIP_STIFFNESS = 0.4F;

    private PhysicsJoints()
    {}

    /**
     * Sets one joint's motors to the stiffness and damping knobs, softened by how far down the
     * strand the joint sits.
     *
     * <p>Position mode with a spring means "return to the shape you were built in" — the hairstyle,
     * the rope's natural hang — while Off is a strand that goes wherever physics takes it. It has to
     * be a motor rather than friction in the joint: friction fights the spring and the strand ends
     * up leaning by a degree or so and staying there, which reads as a rope hung crooked.</p>
     *
     * <p>The spring's <em>frequency</em> is scaled by the square root of the falloff rather than the
     * falloff itself, because a spring's stiffness goes as the square of its frequency: the tip is
     * meant to be {@link #TIP_STIFFNESS} as stiff, not that fraction as fast.</p>
     *
     * @param index how far along the strand this joint is, the root being 0
     * @param count how many joints the strand has in all — a strand of one has no gradient to speak
     *              of and keeps the root's stiffness
     */
    public static void tune(SwingTwistConstraint constraint, float stiffness, float damping, int index, int count)
    {
        EMotorState state = stiffness > 0F ? EMotorState.Position : EMotorState.Off;

        constraint.setSwingMotorState(state);
        constraint.setTwistMotorState(state);

        if (stiffness > 0F)
        {
            float along = count <= 1 ? 0F : Math.min(Math.max(index, 0), count - 1) / (float) (count - 1);
            float falloff = 1F - (1F - TIP_STIFFNESS) * along;

            float frequency = (0.5F + stiffness * (SPRING_TOP_HZ - 0.5F)) * (float) Math.sqrt(falloff);

            /* The spring's own damping ratio, where 1 is critical — a spring that returns to its
             * shape without overshooting it. This used to start at 0.1, which is a bell: the strand
             * sprang past the pose, came back past it, and did that for a second and a half after
             * every step the character took. Underdamped springs are half of what "sharp" meant. */
            float ratio = 0.25F + damping * 0.75F;

            for (MotorSettings motor : new MotorSettings[] {constraint.getSwingMotorSettings(), constraint.getTwistMotorSettings()})
            {
                motor.getSpringSettings().setFrequency(frequency);
                motor.getSpringSettings().setDamping(ratio);
            }
        }
    }
}
