package mchorse.bbs_physics.forms;

/**
 * The "rigid body" modifier of one form: the mark saying "this falls", and the few numbers that
 * decide how.
 *
 * <p>It used to be a form of its own — a wrapper you dropped a block into (§5.1). The wrapper is
 * gone (Р7): physics is a property of an existing object, exactly as {@code Rigid Body} is in
 * Blender, so this rides along on whatever form the author already has. Dropping a crate is two
 * clicks on the crate now, not three levels of nesting.</p>
 *
 * <p><b>Still no geometry of its own.</b> What the body collides as comes from the collision markup
 * of the form it sits on and of everything nested inside it (§5.2), which is what lets the ragdoll
 * and, later, the hair read the same markup instead of a copy kept inside the body.</p>
 *
 * @param enabled     whether the form has this modifier at all
 * @param passive     Blender's Active/Passive: a passive body moves by its keyframes and shoves
 *                    what it meets, but nothing shoves it — the authority handle is ignored. For
 *                    scenery that has to be collidable without ever being knocked over
 * @param mass        kilograms. Only matters when this body meets another one; gravity pulls
 *                    everything alike, which is the answer to "the mass slider does nothing"
 * @param friction    0 is ice, 1 grips
 * @param restitution how much of an impact comes back as bounce. 0 lands dead, 1 never settles
 */
public record FormBody(boolean enabled, boolean passive, float mass, float friction, float restitution)
{
    public static final float DEFAULT_MASS = 10F;
    public static final float DEFAULT_FRICTION = 0.5F;
    public static final float DEFAULT_RESTITUTION = 0.2F;

    /** What a form has before anyone touches it: no body. */
    public static final FormBody EMPTY = new FormBody(false, false, DEFAULT_MASS, DEFAULT_FRICTION, DEFAULT_RESTITUTION);

    /** The body an author gets by pressing the button, with nothing else said. */
    public static FormBody added()
    {
        return new FormBody(true, false, DEFAULT_MASS, DEFAULT_FRICTION, DEFAULT_RESTITUTION);
    }

    public FormBody with(boolean enabled)
    {
        return new FormBody(enabled, this.passive, this.mass, this.friction, this.restitution);
    }

    public FormBody withPassive(boolean passive)
    {
        return new FormBody(this.enabled, passive, this.mass, this.friction, this.restitution);
    }

    public FormBody withMass(float mass)
    {
        return new FormBody(this.enabled, this.passive, mass, this.friction, this.restitution);
    }

    public FormBody withFriction(float friction)
    {
        return new FormBody(this.enabled, this.passive, this.mass, friction, this.restitution);
    }

    public FormBody withRestitution(float restitution)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, restitution);
    }

    /**
     * Whether this is worth storing at all. A form nobody has given a body to writes nothing, so it
     * stays byte-identical to one saved without the addon ever running.
     */
    public boolean isEmpty()
    {
        return this.equals(EMPTY);
    }
}
