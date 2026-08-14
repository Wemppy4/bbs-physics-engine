package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * A form that <em>is</em> a physical body. It has no look of its own — whatever should be seen is
 * put inside it as a body part, and this form only decides where that ends up.
 *
 * <p>Built as a wrapper rather than as a property bolted onto every existing form because a
 * physical body is its own thing in a scene: it has mass, it is pushed around, and it stops
 * obeying its keyframes the moment it is let go. Wrapping keeps that clear, and it means any form
 * BBS has — a block, an item, a model — can be dropped into the world without any of them knowing
 * physics exists.</p>
 */
public class PhysicsBodyForm extends Form
{
    /**
     * How much the animation owns this body, from 0 to 1.
     *
     * <p>At 1 the body is kinematic: it follows its keyframes exactly and shoves everything it
     * meets, while nothing can shove it. At 0 it is fully dynamic and lives its own life from
     * wherever it currently is. Animating this from 1 to 0 is how a thrown object is <em>let
     * go</em> — it keeps the speed the keyframes gave it and flies on by itself.</p>
     *
     * <p>Values in between are a ragdoll's territory (a motor pulling towards the animated pose)
     * and are treated as a threshold for now: this form is a rigid body, and half-animating a
     * crate has no meaning the way it does for a limb.</p>
     */
    public final ValueFloat authority = new ValueFloat("authority", 1F, 0F, 1F);

    /* Collider size in blocks. A box because that is what nearly everything in Minecraft looks
     * like; other shapes come with the collider work. */
    public final ValueFloat sizeX = new ValueFloat("size_x", 1F, 0.05F, 64F);
    public final ValueFloat sizeY = new ValueFloat("size_y", 1F, 0.05F, 64F);
    public final ValueFloat sizeZ = new ValueFloat("size_z", 1F, 0.05F, 64F);

    /** Kilograms. Only matters when this body meets another one — gravity pulls everything alike. */
    public final ValueFloat mass = new ValueFloat("mass", 10F, 0.01F, 10000F);

    public final ValueFloat friction = new ValueFloat("friction", 0.5F, 0F, 1F);

    /** How much of an impact comes back as bounce. 0 lands dead, 1 never settles. */
    public final ValueFloat restitution = new ValueFloat("restitution", 0.2F, 0F, 1F);

    /**
     * Where the simulation has this body, relative to the actor it hangs on. Filled in by the
     * scene every tick and read by the renderer — runtime only, never saved: it is the result of
     * the simulation, not a description of it.
     *
     * <p>Null until a scene claims this form, which is also what "this form is not being
     * simulated" means — in the form editor's preview, for instance, where the form is being
     * edited rather than played.</p>
     */
    public PhysicsBodyState state;

    public PhysicsBodyForm()
    {
        super();

        this.add(this.authority.slider());
        this.add(this.sizeX);
        this.add(this.sizeY);
        this.add(this.sizeZ);
        this.add(this.mass);
        this.add(this.friction.slider());
        this.add(this.restitution.slider());
    }

    /** Whether the animation still owns the body — see {@link #authority}. */
    public boolean isKinematic()
    {
        return this.authority.get() >= 0.5F;
    }

    @Override
    public String getDefaultDisplayName()
    {
        return "physics body";
    }
}
