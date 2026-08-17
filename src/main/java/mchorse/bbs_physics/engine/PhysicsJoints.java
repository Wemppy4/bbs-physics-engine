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

    private PhysicsJoints()
    {}

    /**
     * Sets one joint's motors to the stiffness and damping knobs.
     *
     * <p>Position mode with a spring means "return to the shape you were built in" — the hairstyle,
     * the rope's natural hang — while Off is a strand that goes wherever physics takes it. It has to
     * be a motor rather than friction in the joint: friction fights the spring and the strand ends
     * up leaning by a degree or so and staying there, which reads as a rope hung crooked.</p>
     */
    public static void tune(SwingTwistConstraint constraint, float stiffness, float damping)
    {
        EMotorState state = stiffness > 0F ? EMotorState.Position : EMotorState.Off;

        constraint.setSwingMotorState(state);
        constraint.setTwistMotorState(state);

        if (stiffness > 0F)
        {
            float frequency = 0.5F + stiffness * (SPRING_TOP_HZ - 0.5F);
            float ratio = 0.1F + damping * 0.9F;

            for (MotorSettings motor : new MotorSettings[] {constraint.getSwingMotorSettings(), constraint.getTwistMotorSettings()})
            {
                motor.getSpringSettings().setFrequency(frequency);
                motor.getSpringSettings().setDamping(ratio);
            }
        }
    }
}
