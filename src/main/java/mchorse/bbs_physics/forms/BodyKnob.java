package mchorse.bbs_physics.forms;

/**
 * The numbers of the rigid body modifier that can be keyframed — each one a {@link
 * PhysicsKnobValue} on the form, see there. What is not here (the type, the frozen axes, the sleep
 * flag) stays in the modifier's stored blob.
 */
public enum BodyKnob
{
    MASS("bbs_physics_body_mass", FormBody.DEFAULT_MASS, 0.01F, 10000F),
    FRICTION("bbs_physics_body_friction", FormBody.DEFAULT_FRICTION, 0F, 1F),
    RESTITUTION("bbs_physics_body_restitution", FormBody.DEFAULT_RESTITUTION, 0F, 1F),
    LINEAR_DAMPING("bbs_physics_body_linear_damping", FormBody.DEFAULT_LINEAR_DAMPING, 0F, 1F),
    ANGULAR_DAMPING("bbs_physics_body_angular_damping", FormBody.DEFAULT_ANGULAR_DAMPING, 0F, 1F),
    GRAVITY("bbs_physics_body_gravity", FormBody.DEFAULT_GRAVITY, -2F, 2F);

    public final String id;
    public final float fallback;
    public final float min;
    public final float max;

    BodyKnob(String id, float fallback, float min, float max)
    {
        this.id = id;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
    }

    public float of(FormBody body)
    {
        return switch (this)
        {
            case MASS -> body.mass();
            case FRICTION -> body.friction();
            case RESTITUTION -> body.restitution();
            case LINEAR_DAMPING -> body.linearDamping();
            case ANGULAR_DAMPING -> body.angularDamping();
            case GRAVITY -> body.gravity();
        };
    }

    public FormBody into(FormBody body, float value)
    {
        return switch (this)
        {
            case MASS -> body.withMass(value);
            case FRICTION -> body.withFriction(value);
            case RESTITUTION -> body.withRestitution(value);
            case LINEAR_DAMPING -> body.withLinearDamping(value);
            case ANGULAR_DAMPING -> body.withAngularDamping(value);
            case GRAVITY -> body.withGravity(value);
        };
    }
}
