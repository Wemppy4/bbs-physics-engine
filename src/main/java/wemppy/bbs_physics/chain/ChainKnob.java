package wemppy.bbs_physics.chain;

/**
 * The numbers of the hair modifier that can be keyframed — each one a {@code PhysicsKnobValue} on
 * the model form. The bones and the self-collision switch stay in the stored blob.
 */
public enum ChainKnob
{
    STIFFNESS("bbs_physics_chain_stiffness", FormChain.DEFAULT_STIFFNESS, 0F, 1F),
    DAMPING("bbs_physics_chain_damping", FormChain.DEFAULT_DAMPING, 0F, 1F),
    GRAVITY("bbs_physics_chain_gravity", FormChain.DEFAULT_GRAVITY, -2F, 2F),
    MASS("bbs_physics_chain_mass", FormChain.DEFAULT_MASS, 0.01F, 100F),
    FALLOFF("bbs_physics_chain_falloff", FormChain.DEFAULT_FALLOFF, 0F, 1F),
    BEND("bbs_physics_chain_bend", FormChain.DEFAULT_BEND, 5F, 180F);

    public final String id;
    public final float fallback;
    public final float min;
    public final float max;

    ChainKnob(String id, float fallback, float min, float max)
    {
        this.id = id;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
    }

    public float of(FormChain chain)
    {
        return switch (this)
        {
            case STIFFNESS -> chain.stiffness();
            case DAMPING -> chain.damping();
            case GRAVITY -> chain.gravity();
            case MASS -> chain.mass();
            case FALLOFF -> chain.falloff();
            case BEND -> chain.bend();
        };
    }

    public FormChain into(FormChain chain, float value)
    {
        return switch (this)
        {
            case STIFFNESS -> chain.withStiffness(value);
            case DAMPING -> chain.withDamping(value);
            case GRAVITY -> chain.withGravity(value);
            case MASS -> chain.withMass(value);
            case FALLOFF -> chain.withFalloff(value);
            case BEND -> chain.withBend(value);
        };
    }
}
