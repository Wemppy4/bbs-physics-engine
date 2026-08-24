package mchorse.bbs_physics.collision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one slot — a bone, or a form itself — collides as. */
public record CollisionSlot(CollisionMode mode, List<CollisionShape> shapes)
{
    public static final CollisionSlot NONE = new CollisionSlot(CollisionMode.NONE, Collections.emptyList());
    public static final CollisionSlot AUTO = new CollisionSlot(CollisionMode.AUTO, Collections.emptyList());
    public static final CollisionSlot PIXELS = new CollisionSlot(CollisionMode.PIXELS, Collections.emptyList());

    public CollisionSlot
    {
        mode = mode == null ? CollisionMode.NONE : mode;
        shapes = shapes == null ? Collections.emptyList() : List.copyOf(shapes);
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
        return new CollisionSlot(mode, this.shapes);
    }

    public CollisionSlot withShapes(List<CollisionShape> shapes)
    {
        return new CollisionSlot(this.mode, shapes);
    }

    /** The same slot with one more primitive, switched to {@code SHAPES} since it now has some. */
    public CollisionSlot plus(CollisionShape shape)
    {
        List<CollisionShape> shapes = new ArrayList<>(this.shapes);

        shapes.add(shape);

        return new CollisionSlot(CollisionMode.SHAPES, shapes);
    }
}
