package wemppy.bbs_physics.forms;

/**
 * The "rigid body" modifier of one form: the mark saying "this falls", and the numbers that decide
 * how.
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
 * <p>The knobs are Blender's rigid body panel, plus what Jolt offers that Blender does not and an
 * author reaches for anyway: a frozen axis (a door, a wheel, a swing — no joint needed) and a
 * gravity factor (a balloon, a slow fall).</p>
 *
 * @param enabled        whether the form has this modifier at all
 * @param passive        Blender's Active/Passive: a passive body moves by its keyframes and shoves
 *                       what it meets, but nothing shoves it — the authority handle is ignored. For
 *                       scenery that has to be collidable without ever being knocked over
 * @param mass           kilograms. Only matters when this body meets another one; gravity pulls
 *                       everything alike, which is the answer to "the mass slider does nothing"
 * @param friction       0 is ice, 1 grips
 * @param restitution    how much of an impact comes back as bounce. 0 lands dead, 1 never settles
 * @param linearDamping  the share of its speed a body sheds per film tick — air, in effect. 0 keeps
 *                       rolling forever, 1 stops dead
 * @param angularDamping the same for spin
 * @param gravity        how much of the scene's gravity this body feels: 1 as everything, 0 floats,
 *                       below 0 rises
 * @param asleep         whether the body starts asleep — still until something touches it. A stack
 *                       of crates that is not asleep shivers on the spot until the first push
 * @param lockMove       axes the body may not travel along, as bits — 1 for X, 2 for Y, 4 for Z
 * @param lockSpin       axes the body may not turn about, the same bits
 */
public record FormBody(boolean enabled, boolean passive, float mass, float friction, float restitution,
    float linearDamping, float angularDamping, float gravity, boolean asleep, int lockMove, int lockSpin)
{
    public static final float DEFAULT_MASS = 10F;
    public static final float DEFAULT_FRICTION = 0.5F;
    public static final float DEFAULT_RESTITUTION = 0.2F;
    public static final float DEFAULT_LINEAR_DAMPING = 0.02F;
    public static final float DEFAULT_ANGULAR_DAMPING = 0.02F;
    public static final float DEFAULT_GRAVITY = 1F;

    public static final int AXIS_X = 1;
    public static final int AXIS_Y = 2;
    public static final int AXIS_Z = 4;

    /** What a form has before anyone touches it: no body. */
    public static final FormBody EMPTY = new FormBody(false, false, DEFAULT_MASS, DEFAULT_FRICTION, DEFAULT_RESTITUTION,
        DEFAULT_LINEAR_DAMPING, DEFAULT_ANGULAR_DAMPING, DEFAULT_GRAVITY, false, 0, 0);

    public FormBody
    {
        lockMove &= AXIS_X | AXIS_Y | AXIS_Z;
        lockSpin &= AXIS_X | AXIS_Y | AXIS_Z;
    }

    /** The body an author gets by pressing the button, with nothing else said. */
    public static FormBody added()
    {
        return EMPTY.with(true);
    }

    public FormBody with(boolean enabled)
    {
        return new FormBody(enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withPassive(boolean passive)
    {
        return new FormBody(this.enabled, passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withMass(float mass)
    {
        return new FormBody(this.enabled, this.passive, mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withFriction(float friction)
    {
        return new FormBody(this.enabled, this.passive, this.mass, friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withRestitution(float restitution)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withLinearDamping(float linearDamping)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withAngularDamping(float angularDamping)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, angularDamping, this.gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withGravity(float gravity)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, gravity, this.asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withAsleep(boolean asleep)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, asleep, this.lockMove, this.lockSpin);
    }

    public FormBody withLockMove(int lockMove)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, lockMove, this.lockSpin);
    }

    public FormBody withLockSpin(int lockSpin)
    {
        return new FormBody(this.enabled, this.passive, this.mass, this.friction, this.restitution, this.linearDamping, this.angularDamping, this.gravity, this.asleep, this.lockMove, lockSpin);
    }

    /** One axis bit of {@code lockMove} or {@code lockSpin} flipped. */
    public static int toggle(int mask, int axis, boolean locked)
    {
        return locked ? mask | axis : mask & ~axis;
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
