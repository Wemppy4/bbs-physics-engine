package mchorse.bbs_physics.cloth;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.misc.ValueVector4f;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector4f;

/**
 * A sheet of cloth: a textured rectangle that Jolt's soft body solver bends, swings and drapes
 * over whatever it lands on — a cape, a flag, a curtain, a towel over a chair.
 *
 * <p>A form of its own rather than a modifier on the picture form (Вемпи's call, Р12). It draws
 * like a picture — one texture on a rectangle — but nothing else about it is picture-like: the
 * rectangle is a grid the simulation owns, its size is physical rather than pixel-derived, and
 * the whole point of the thing is the shape it takes on its own. What it has instead of the
 * picture's knobs is where it is held ({@link ClothEdge}) and what its fabric is like.</p>
 *
 * <p>The sheet hangs <em>down</em> from the form's origin: the held edge runs along the pivot, so
 * dropping the form onto a body part at the shoulders hangs a cape, and onto a pole hangs a flag.
 * The one animation handle every physics form shares (§4) covers cloth too: at 1 the sheet is the
 * flat rectangle the author placed, at 0 it is entirely the simulation's, and in between the loose
 * vertices are pulled towards flat by that much.</p>
 */
public class ClothForm extends Form
{
    /* What the sheet looks like. */
    public final ValueLink texture = new ValueLink("texture", null);
    public final ValueColor color = new ValueColor("color", Color.white());
    public final ValueBoolean linear = new ValueBoolean("linear", false);
    public final ValueBoolean mipmap = new ValueBoolean("mipmap", false);
    public final ValueBoolean shading = new ValueBoolean("shading", true);

    /**
     * Pixels shaved off the texture before it is stretched over the sheet — left, top, right,
     * bottom, the picture form's convention. Crop only picks the region; the sheet's shape stays
     * whatever {@link #width} and {@link #height} say, because a sheet's size is physical, not
     * pixel-derived.
     */
    public final ValueVector4f crop = new ValueVector4f("crop", new Vector4f(0F, 0F, 0F, 0F));

    /* What the sheet is: size in blocks, resolution in cells. More cells drape finer and cost
     * more — each vertex is simulated and recorded every tick. */
    public final ValueFloat width = new ValueFloat("width", 1F, 0.1F, 16F);
    public final ValueFloat height = new ValueFloat("height", 1.5F, 0.1F, 16F);
    public final ValueInt segmentsX = new ValueInt("segmentsX", 8, 1, 32);
    public final ValueInt segmentsY = new ValueInt("segmentsY", 12, 1, 32);

    /** Which edge rides the animation while the rest hangs — stored by {@link ClothEdge} name. */
    public final ValueString edge = new ValueString("edge", ClothEdge.TOP.name());

    /**
     * Whether other sheets can land on this one.
     *
     * <p>Off by default, and not out of caution about the look: Jolt does not collide soft bodies
     * with each other at all, so this is served by building a grid of thin kinematic slabs that
     * follow the sheet and stand in for it. That is real bodies in the world per sheet, which is
     * worth asking for rather than assuming — one cape on a character needs none of it.</p>
     */
    public final ValueBoolean selfCollision = new ValueBoolean("selfCollision", false);

    /* What the fabric is like. */
    public final ValueFloat mass = new ValueFloat("mass", 1F, 0.01F, 1000F);
    public final ValueFloat stiffness = new ValueFloat("stiffness", 0.5F, 0F, 1F);
    public final ValueFloat damping = new ValueFloat("damping", 0.2F, 0F, 1F);
    public final ValueFloat friction = new ValueFloat("friction", 0.5F, 0F, 1F);

    /**
     * Where the simulation has this sheet, vertex by vertex, in the form's own frame. Filled in by
     * the scene every frame and read by the renderer — runtime only, never saved. Null until a
     * scene claims this form, which is also what "not being simulated" means: the form editor's
     * preview draws the flat sheet.
     */
    public ClothState state;

    public ClothForm()
    {
        super();

        this.add(this.texture);
        this.add(this.color);
        this.add(this.linear);
        this.add(this.mipmap);
        this.add(this.shading);
        this.add(this.crop);
        this.add(this.width);
        this.add(this.height);
        this.add(this.segmentsX);
        this.add(this.segmentsY);
        this.add(this.edge);
        this.add(this.selfCollision);
        this.add(this.mass);
        this.add(this.stiffness);
        this.add(this.damping);
        this.add(this.friction);
    }

    public ClothEdge getEdge()
    {
        return ClothEdge.of(this.edge.get());
    }

    /** Vertices across: one more than there are cells. */
    public int getColumns()
    {
        return this.segmentsX.get() + 1;
    }

    /** Vertices down: one more than there are cells. */
    public int getRows()
    {
        return this.segmentsY.get() + 1;
    }

    /**
     * Where vertex ({@code c}, {@code r}) sits on the flat sheet, in the form's own frame — the
     * one layout everybody shares: the builder seeds the simulation with it, the drive pulls
     * towards it, and the renderer falls back to it on frames with no recorded answer. The sheet
     * is centred on the pivot across and hangs down from it.
     */
    public float flatX(int c)
    {
        float width = this.width.get();

        return c * (width / (this.getColumns() - 1)) - width / 2F;
    }

    public float flatY(int r)
    {
        return -r * (this.height.get() / (this.getRows() - 1));
    }

    @Override
    public String getDefaultDisplayName()
    {
        return "cloth";
    }
}
