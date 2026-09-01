package wemppy.bbs_physics.chain;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.values.ValueAnchor;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * A chain: a strand of rigid segments hanging from the form's origin — a rope, a cable, a chain
 * between two posts, a leash, a swing. The third soft-ish form after cloth and the balloon, and
 * the first one whose whole point is its <em>ends</em>: the top rides the animation wherever the
 * author put the form, and the bottom can be free, pinned to any actor's bone, or tied to a
 * physics body — which it then honestly drags around.
 *
 * <p>A form of its own rather than a modifier for the same reason cloth is (Р12): there is nothing
 * to modify — the strand does not exist in any model, the form <em>is</em> the thing. The
 * bone-chain modifier for hair and tails is a separate, later work that will reuse this rig.</p>
 *
 * <p><b>What a segment looks like is the author's business:</b> {@link #link} holds any BBS form —
 * a cube model link, a textured billboard, anything — drawn once per segment, hanging down from
 * the segment's start. With no link set, the renderer draws a built-in rope band so the form is
 * never invisible.</p>
 *
 * <p><b>The bottom end is a keyframable anchor</b> ({@link #attach}), the same track type the
 * film's actor anchors use: pick an actor and a bone in the timeline, and the end follows it —
 * or grabs it, if what it names is a falling physics body. Re-keying the anchor mid-film
 * re-ties the rope; a fade between keys eases the end over instead of snapping it.</p>
 *
 * <p>The one animation handle every physics form shares (§4) means the same thing here: at 1 the
 * strand is the straight line the author placed, at 0 it is entirely the simulation's. The
 * palette's default chain starts at 0, because a rope that hangs and swings the moment it is
 * dropped into a scene is what anyone reaching for a rope wants.</p>
 */
public class ChainForm extends Form
{
    /** What one segment looks like — any form, repeated down the strand. Null draws the built-in band. */
    public final ValueForm link = new ValueForm("link");

    /* What the strand is: physical size in blocks, resolution in segments. More segments bend
     * finer and cost more — each one is a rigid body simulated and recorded every tick. */
    public final ValueFloat length = new ValueFloat("length", 2F, 0.2F, 32F);
    public final ValueInt segments = new ValueInt("segments", 8, 1, 64);
    public final ValueFloat radius = new ValueFloat("radius", 0.05F, 0.01F, 0.5F);

    /* What the strand is like. Stiffness is the spring that pulls it back straight: 0 is a rope,
     * 1 is a garden hose that barely bends. Damping is how fast swinging dies down. */
    public final ValueFloat mass = new ValueFloat("mass", 2F, 0.01F, 1000F);
    public final ValueFloat stiffness = new ValueFloat("stiffness", 0F, 0F, 1F);
    public final ValueFloat damping = new ValueFloat("damping", 0.2F, 0F, 1F);
    public final ValueFloat friction = new ValueFloat("friction", 0.5F, 0F, 1F);

    /** Gravity multiplier, the balloon's convention: negative floats the strand upwards. */
    public final ValueFloat gravity = new ValueFloat("gravity", 1F, -2F, 2F);

    /**
     * Whether the top of the strand is held by the form's frame. On, the rope hangs from wherever
     * the author put the form and follows it; off, the whole strand is loose — a rope lying on
     * the ground or flying free.
     */
    public final ValueBoolean heldStart = new ValueBoolean("heldStart", true);

    /**
     * Where the bottom end is tied, as a keyframable anchor track — free when it names no actor.
     * Visible is what makes a form value a timeline track in BBS, and {@link ValueAnchor} brings
     * the anchor keyframe editor (actor picker included) along for free.
     */
    public final ValueAnchor attach = new ValueAnchor("attach", new Anchor());

    /**
     * Where the simulation has this strand, segment by segment, in the form's own frame. Filled
     * in by the scene every frame and read by the renderer — runtime only, never saved. Null until
     * a scene claims this form, which is also what "not being simulated" means: the form editor's
     * preview draws the straight strand.
     */
    public ChainState state;

    public ChainForm()
    {
        super();

        this.add(this.link);
        this.add(this.length);
        this.add(this.segments);
        this.add(this.radius);
        this.add(this.mass);
        this.add(this.stiffness);
        this.add(this.damping);
        this.add(this.friction);
        this.add(this.gravity);
        this.add(this.heldStart);
        this.add(this.attach);
    }

    /** One segment's length in blocks — the strand divided evenly. */
    public float getSegmentLength()
    {
        return this.length.get() / Math.max(this.segments.get(), 1);
    }

    /**
     * Where segment {@code i}'s centre sits on the straight strand, in the form's own frame — the
     * one layout everybody shares: the builder seeds the simulation with it, the drive pulls
     * towards it, and the renderer falls back to it on frames with no recorded answer. The strand
     * hangs straight down from the pivot.
     */
    public float restY(int i)
    {
        return -(i + 0.5F) * this.getSegmentLength();
    }

    @Override
    public String getDefaultDisplayName()
    {
        return "chain";
    }
}
