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

    /**
     * The bone's cubes as their <em>drawn</em> surface: every side of every cube becomes thin
     * plates laid over the pixels that are actually painted, and nothing is built where the
     * texture is transparent — or inside the cube at all.
     *
     * <p>For the way cubic models are really made. A strand of hair is not a solid box: it is a
     * flat cube, often of no depth whatsoever, with a lock painted on it and the rest of the
     * texture left clear; the second layer of a head is a whole cube with hair painted on three
     * of its sides. Measured by its bounds, such a cube collides as a slab of air and hits the
     * body a pixel or more before the hair does. Read by its pixels, the collision is the
     * silhouette the viewer sees.</p>
     *
     * <p>This replaces the older "face" mode — one plate on one side the author named — which is
     * exactly what this produces for a cube whose side is painted edge to edge, without the
     * author having to name the side. Files saved with that mode load as this one.</p>
     *
     * <p>Live, like {@link #AUTO}: nothing is written down but the choice, so a cube that changes
     * size, or a texture that is repainted, takes its collision with it.</p>
     */
    PIXELS("pixels"),

    /** The primitives the author placed by hand. */
    SHAPES("shapes");

    public final String id;

    CollisionMode(String id)
    {
        this.id = id;
    }

    public static CollisionMode byId(String id, CollisionMode fallback)
    {
        /* The retired "face" mode was a plate on one side, chosen by hand; a pixel read lays the
         * same plate wherever that side is painted, so a file that asked for one gets the other. */
        if ("face".equals(id))
        {
            return PIXELS;
        }

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
