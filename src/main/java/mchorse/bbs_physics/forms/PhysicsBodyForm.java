package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.MathUtils;

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
     * How much the animation owns this body, from 0 to 1 — a continuous handle, not a switch.
     *
     * <p>At 1 the body is kinematic: it follows its keyframes exactly and shoves everything it
     * meets, while nothing can shove it. At 0 it is fully dynamic and lives its own life from
     * wherever it currently is. In between it is a dynamic body that is <em>pulled</em> towards the
     * animated pose by that fraction of the velocity the pose asks for: at 0.9 it all but keeps up
     * with the animation while sagging a little under gravity and giving way to anything it hits,
     * at 0.2 it barely leans that way.</p>
     *
     * <p>That is what makes the handover watchable. Animating this from 1 to 0 over a few ticks is
     * how a thrown object is <em>let go</em>: the pull fades instead of being cut, and whatever
     * speed the keyframes had already given the body stays in it, so the object flies on by itself.
     * A step from 1 straight to 0 is still allowed and still throws — it just happens in one tick.
     * </p>
     */
    public final ValueFloat authority = new ValueFloat("authority", 1F, 0F, 1F);

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
        this.add(this.mass);
        this.add(this.friction.slider());
        this.add(this.restitution.slider());
    }

    /** The authority handle, kept inside its range whatever a keyframe track hands over. */
    public float getAuthority()
    {
        return MathUtils.clamp(this.authority.get(), 0F, 1F);
    }

    /**
     * Whether the animation owns the body <em>outright</em> — see {@link #authority}.
     *
     * <p>Only a full 1 counts. Anything less is a dynamic body being pulled towards the pose, which
     * is the whole point of the handle being continuous: a threshold in the middle turned a fade
     * from 1 to 0 into a switch flipping on whichever tick happened to cross it, and that jump is
     * exactly what an author sees as the object twitching as it is released.</p>
     */
    public boolean isKinematic()
    {
        return this.getAuthority() >= 1F;
    }

    @Override
    public String getDefaultDisplayName()
    {
        return "physics body";
    }
}
