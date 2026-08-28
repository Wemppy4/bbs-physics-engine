package mchorse.bbs_physics.ragdoll;

/**
 * The whole-ragdoll numbers that can be keyframed — each one a {@code PhysicsKnobValue} on the
 * model form. The joints, the exclusions and the self-collision switch stay in the stored blob.
 */
public enum RagdollKnob
{
    MASS("bbs_physics_ragdoll_mass", FormRagdoll.DEFAULT_MASS, 0F, 10000F),
    DAMPING("bbs_physics_ragdoll_damping", FormRagdoll.DEFAULT_DAMPING, 0F, 1F),
    FRICTION("bbs_physics_ragdoll_friction", FormRagdoll.DEFAULT_FRICTION, 0F, 100F),
    GRAVITY("bbs_physics_ragdoll_gravity", FormRagdoll.DEFAULT_GRAVITY, -2F, 2F),
    MUSCLES("bbs_physics_ragdoll_muscles", FormRagdoll.DEFAULT_MUSCLES, 0F, 1F),
    MUSCLE_DAMPING("bbs_physics_ragdoll_muscle_damping", FormRagdoll.DEFAULT_MUSCLE_DAMPING, 0F, 1F);

    public final String id;
    public final float fallback;
    public final float min;
    public final float max;

    RagdollKnob(String id, float fallback, float min, float max)
    {
        this.id = id;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
    }

    public float of(FormRagdoll ragdoll)
    {
        return switch (this)
        {
            case MASS -> ragdoll.mass();
            case DAMPING -> ragdoll.damping();
            case FRICTION -> ragdoll.friction();
            case GRAVITY -> ragdoll.gravity();
            case MUSCLES -> ragdoll.muscles();
            case MUSCLE_DAMPING -> ragdoll.muscleDamping();
        };
    }

    public FormRagdoll into(FormRagdoll ragdoll, float value)
    {
        return switch (this)
        {
            case MASS -> ragdoll.withMass(value);
            case DAMPING -> ragdoll.withDamping(value);
            case FRICTION -> ragdoll.withFriction(value);
            case GRAVITY -> ragdoll.withGravity(value);
            case MUSCLES -> ragdoll.withMuscles(value);
            case MUSCLE_DAMPING -> ragdoll.withMuscleDamping(value);
        };
    }
}
