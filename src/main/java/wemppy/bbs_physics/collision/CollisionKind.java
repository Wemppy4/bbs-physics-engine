package wemppy.bbs_physics.collision;

/**
 * The primitives a collision shape can be. Deliberately the small set every engine agrees on —
 * these are the shapes Jolt solves cheaply and exactly, and the shapes an author can place by eye.
 *
 * <p>A mesh-accurate hull is not among them on purpose: §5.2 of the concept measures collision per
 * bone, and a bone's honest silhouette is a box, a ball or a limb. Anything finer costs contacts
 * without changing what the viewer sees.</p>
 */
public enum CollisionKind
{
    /** {@code size} is the full width, height and depth. */
    BOX("box"),

    /** A ball: {@code size.x} is its diameter, the rest is ignored. */
    SPHERE("sphere"),

    /** A capped cylinder: {@code size.x} is the diameter, {@code size.y} the total height. */
    CAPSULE("capsule"),

    /** A flat-ended cylinder: {@code size.x} is the diameter, {@code size.y} the height. */
    CYLINDER("cylinder");

    public final String id;

    CollisionKind(String id)
    {
        this.id = id;
    }

    public static CollisionKind byId(String id, CollisionKind fallback)
    {
        for (CollisionKind kind : values())
        {
            if (kind.id.equals(id))
            {
                return kind;
            }
        }

        return fallback;
    }
}
