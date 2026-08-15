package mchorse.bbs_physics.collision;

/**
 * What a marked-up slot — one bone, or a whole form — contributes to collision.
 *
 * <p>{@link #NONE} is the default for everything, and that is the point of the whole system: a
 * model's bones do not collide until an author says which of them do. Marking everything would
 * cost contacts on hundreds of cubes, and worse, it would fight the hair and cloth solvers for
 * ownership of the bones they drive (§5.2).</p>
 */
public enum CollisionMode
{
    /** Not part of collision at all. Every slot starts here. */
    NONE("none"),

    /** Measured from the geometry the slot is drawn from — a bone's own cubes. */
    AUTO("auto"),

    /** The primitives the author placed by hand. */
    SHAPES("shapes");

    public final String id;

    CollisionMode(String id)
    {
        this.id = id;
    }

    public static CollisionMode byId(String id, CollisionMode fallback)
    {
        for (CollisionMode mode : values())
        {
            if (mode.id.equals(id))
            {
                return mode;
            }
        }

        return fallback;
    }
}
