package mchorse.bbs_physics.collision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one slot — a bone, or a form itself — collides as.
 *
 * @param thickness which way a pixel plate stands off its pixels — read in {@link
 *                  CollisionMode#PIXELS} only
 * @param plate     how thick a pixel plate is, in model pixels — same mode only
 */
public record CollisionSlot(CollisionMode mode, CollisionThickness thickness, float plate, List<CollisionShape> shapes)
{
    /** A quarter of a pixel: reads as a surface, still solves as a solid. */
    public static final float DEFAULT_PLATE = 0.25F;

    /** The thinnest plate Jolt will still build a box for, and the thickest worth offering. */
    public static final float MIN_PLATE = 0.0625F;
    public static final float MAX_PLATE = 4F;

    public static final CollisionSlot NONE = new CollisionSlot(CollisionMode.NONE, Collections.emptyList());
    public static final CollisionSlot AUTO = new CollisionSlot(CollisionMode.AUTO, Collections.emptyList());
    public static final CollisionSlot PIXELS = new CollisionSlot(CollisionMode.PIXELS, Collections.emptyList());

    public CollisionSlot
    {
        mode = mode == null ? CollisionMode.NONE : mode;
        thickness = thickness == null ? CollisionThickness.OUTWARD : thickness;
        plate = Float.isNaN(plate) || plate <= 0F ? DEFAULT_PLATE : Math.min(Math.max(plate, MIN_PLATE), MAX_PLATE);
        shapes = shapes == null ? Collections.emptyList() : List.copyOf(shapes);
    }

    public CollisionSlot(CollisionMode mode, List<CollisionShape> shapes)
    {
        this(mode, CollisionThickness.OUTWARD, DEFAULT_PLATE, shapes);
    }

    /**
     * Whether this slot says nothing and can be dropped from storage. A slot in {@code SHAPES}
     * mode with nothing in it says something quite different from an absent one — it says the
     * author emptied it — but they behave identically, so it is not worth a line in the file.
     */
    public boolean isEmpty()
    {
        return this.mode == CollisionMode.NONE || (this.mode == CollisionMode.SHAPES && this.shapes.isEmpty());
    }

    public CollisionSlot withMode(CollisionMode mode)
    {
        return new CollisionSlot(mode, this.thickness, this.plate, this.shapes);
    }

    public CollisionSlot withThickness(CollisionThickness thickness)
    {
        return new CollisionSlot(this.mode, thickness, this.plate, this.shapes);
    }

    public CollisionSlot withPlate(float plate)
    {
        return new CollisionSlot(this.mode, this.thickness, plate, this.shapes);
    }

    public CollisionSlot withShapes(List<CollisionShape> shapes)
    {
        return new CollisionSlot(this.mode, this.thickness, this.plate, shapes);
    }

    /** The same slot with one more primitive, switched to {@code SHAPES} since it now has some. */
    public CollisionSlot plus(CollisionShape shape)
    {
        List<CollisionShape> shapes = new ArrayList<>(this.shapes);

        shapes.add(shape);

        return new CollisionSlot(CollisionMode.SHAPES, this.thickness, this.plate, shapes);
    }
}
