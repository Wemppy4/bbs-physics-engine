package mchorse.bbs_physics.collision;

/**
 * Which side of a cube the {@link CollisionMode#FACE} mode lays its plate on.
 *
 * <p>Named for the model's own axes, the way an author looks at a cube in the model editor, and
 * chosen by hand on purpose: a second layer — hair, a jacket, a hat brim — is drawn as a thin cube
 * inside the one it sits on, and only the author knows which of its sides is the one facing the
 * world. Guessing that from the geometry is what the previous attempt did, and guessing is exactly
 * what this replaces.</p>
 */
public enum CollisionFace
{
    /** Towards positive Z in model space — the side a cube's "front" texture is drawn on. */
    FRONT("front", 2, 1F),
    BACK("back", 2, -1F),
    LEFT("left", 0, -1F),
    RIGHT("right", 0, 1F),
    TOP("top", 1, 1F),
    BOTTOM("bottom", 1, -1F);

    public final String id;

    /** Which axis of the cube the plate is flattened along: 0 = X, 1 = Y, 2 = Z. */
    public final int axis;

    /** Which end of that axis the plate sits on. */
    public final float sign;

    CollisionFace(String id, int axis, float sign)
    {
        this.id = id;
        this.axis = axis;
        this.sign = sign;
    }

    public static CollisionFace byId(String id, CollisionFace fallback)
    {
        for (CollisionFace face : values())
        {
            if (face.id.equals(id))
            {
                return face;
            }
        }

        return fallback;
    }
}
