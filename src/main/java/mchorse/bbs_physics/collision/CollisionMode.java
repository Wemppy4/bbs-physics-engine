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
     * A plate on one chosen side of the bone's cubes — the author says which side, the geometry
     * says where that side is.
     *
     * <p>For the case a cubic rig makes unavoidable: a second layer — hair, a jacket, a brim — is
     * drawn as a thin cube <em>inside</em> the cube it sits on, because that is how its texture is
     * made to appear over it. Two solids in the same place is the one thing a physics engine may
     * not have — it must push them apart and cannot — so the strand is thrown out of the head. A
     * plate on the outward-facing side is a surface to slide along instead of a volume to be
     * expelled from.</p>
     *
     * <p><b>Which side is the author's call</b>, and that is the whole design. The version before
     * this one worked it out from the geometry — thinnest axis, whichever way the cube leaned — and
     * a guess that is right most of the time is worse than a choice, because when it is wrong there
     * is nothing to correct. Here there is a list of six.</p>
     *
     * <p>Live, like {@link #AUTO}: nothing is written down but the side, so a cube that changes
     * size or moves takes its collision with it.</p>
     */
    FACE("face"),

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
