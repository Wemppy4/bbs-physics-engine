package mchorse.bbs_physics.balloon;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.misc.ValueVector4f;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * An inflated ball: a textured sphere that Jolt's soft body solver keeps pumped up with pressure —
 * a beach ball that dents when it lands and springs back, a football to knock around, a helium
 * balloon that floats away when the gravity knob goes below zero.
 *
 * <p>The cloth form's sibling in every structural way (Р12: soft bodies are forms of their own,
 * not modifiers): the mesh is a grid the simulation owns, its size is physical, and the shape it
 * takes is the whole point. What sets it apart is that the mesh is closed and has air inside —
 * Jolt's pressure model pushes every face outwards, which is what makes it a ball rather than a
 * sack: poke it and it pops back out.</p>
 *
 * <p>The ball is centred on the form's origin — where the author puts the form is where the ball
 * is, no hanging edge to reason about. The one animation handle every physics form shares (§4)
 * covers it too: at 1 the ball is the perfect sphere the author placed, riding its keyframes; at 0
 * it is entirely the solver's; in between it is pulled towards the sphere by that much. Dropping
 * the handle to 0 mid-flight is the same "let go" every body has — the ball keeps the velocity the
 * animation gave it.</p>
 */
public class BalloonForm extends Form
{
    /* What the ball looks like. */
    public final ValueLink texture = new ValueLink("texture", null);
    public final ValueColor color = new ValueColor("color", Color.white());
    public final ValueBoolean linear = new ValueBoolean("linear", false);
    public final ValueBoolean mipmap = new ValueBoolean("mipmap", false);
    public final ValueBoolean shading = new ValueBoolean("shading", true);

    /**
     * Pixels shaved off the texture before it is wrapped around the ball — left, top, right,
     * bottom, the picture form's convention, same as cloth. Crop only picks the region; the ball's
     * size stays whatever {@link #radius} says.
     */
    public final ValueVector4f crop = new ValueVector4f("crop", new Vector4f(0F, 0F, 0F, 0F));

    /* What the ball is: size in blocks, resolution in mesh rows. More segments dent finer and cost
     * more — each vertex is simulated and recorded every tick. */
    public final ValueFloat radius = new ValueFloat("radius", 0.5F, 0.1F, 8F);
    public final ValueInt segments = new ValueInt("segments", 16, 3, 32);
    public final ValueInt rings = new ValueInt("rings", 9, 2, 24);

    /**
     * How pumped up the ball is, 0..1. Zero is a deflated sack that drapes over whatever it lands
     * on; one is drum-tight — and visibly inflated a little <em>past</em> the authored radius,
     * because that is what pressure does to a real ball. The mapping to Jolt's pressure scales
     * with radius squared and mass (the BalloonSmoke stand's formula), so one knob position feels
     * the same on a marble and a boulder.
     */
    public final ValueFloat inflation = new ValueFloat("inflation", 0.5F, 0F, 1F);

    /* What the skin is like. Stiffness rides the same log scale as cloth (1 is rope-taut, 0 is
     * knit), with a stiffer default because rubber is not fabric. */
    public final ValueFloat stiffness = new ValueFloat("stiffness", 0.6F, 0F, 1F);
    public final ValueFloat mass = new ValueFloat("mass", 1F, 0.01F, 1000F);
    public final ValueFloat friction = new ValueFloat("friction", 0.5F, 0F, 1F);
    public final ValueFloat restitution = new ValueFloat("restitution", 0.3F, 0F, 1F);

    /**
     * Damping, defaulted a notch above cloth's: a pressurized ball standing on one mesh vertex
     * picks up a slow contact-fed roll (measured in the stand, not guessed), and this is the knob
     * that keeps it from wandering off the set.
     */
    public final ValueFloat damping = new ValueFloat("damping", 0.25F, 0F, 1F);

    /**
     * Gravity multiplier: 1 falls like anything else, 0 is weightless, below zero floats up — the
     * helium balloon, one knob instead of a separate form for it.
     */
    public final ValueFloat gravity = new ValueFloat("gravity", 1F, -2F, 2F);

    /**
     * Where the simulation has this ball, vertex by vertex, in the form's own frame. Filled in by
     * the scene every frame and read by the renderer — runtime only, never saved. Null until a
     * scene claims this form, which is also what "not being simulated" means: the form editor's
     * preview draws the perfect sphere.
     */
    public BalloonState state;

    public BalloonForm()
    {
        super();

        this.add(this.texture);
        this.add(this.color);
        this.add(this.linear);
        this.add(this.mipmap);
        this.add(this.shading);
        this.add(this.crop);
        this.add(this.radius);
        this.add(this.segments);
        this.add(this.rings);
        this.add(this.inflation);
        this.add(this.stiffness);
        this.add(this.mass);
        this.add(this.friction);
        this.add(this.restitution);
        this.add(this.damping);
        this.add(this.gravity);
    }

    /** North pole, {@code rings} rows of {@code segments}, south pole. */
    public int getVertexCount()
    {
        return this.rings.get() * this.segments.get() + 2;
    }

    /** The south pole's index — the last vertex. */
    public int getSouthPole()
    {
        return this.getVertexCount() - 1;
    }

    /**
     * Where vertex {@code v} sits on the perfect sphere, in the form's own frame — the one layout
     * everybody shares: the builder seeds the simulation with it, the drive pulls towards it, and
     * the renderer falls back to it on frames with no recorded answer. Vertex 0 is the north pole,
     * then the rings top to bottom, then the south pole; within a ring the angle runs with the
     * vertex index.
     */
    public void spherePoint(int v, Vector3f out)
    {
        float radius = this.radius.get();
        int segments = this.segments.get();
        int rings = this.rings.get();

        if (v == 0)
        {
            out.set(0F, radius, 0F);

            return;
        }

        if (v == rings * segments + 1)
        {
            out.set(0F, -radius, 0F);

            return;
        }

        int ring = (v - 1) / segments;
        int segment = (v - 1) % segments;

        double theta = Math.PI * (ring + 1) / (rings + 1);
        double phi = 2D * Math.PI * segment / segments;

        out.set(
            (float) (Math.sin(theta) * Math.cos(phi)) * radius,
            (float) Math.cos(theta) * radius,
            (float) (Math.sin(theta) * Math.sin(phi)) * radius);
    }

    @Override
    public String getDefaultDisplayName()
    {
        return "balloon";
    }
}
