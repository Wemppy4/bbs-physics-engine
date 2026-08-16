package mchorse.bbs_physics.cloth;

/**
 * Which part of a sheet is held in place — the vertices that ride the animation while the rest of
 * the cloth hangs, swings and collides.
 *
 * <p>What "held" means mechanically: those vertices get no mass of their own and are stood on
 * their spot in the form's frame every tick, so they follow the form wherever its keyframes or its
 * body part carry it. A cape pinned along its top edge follows the shoulders; a flag pinned along
 * its left edge follows the pole; a sheet pinned by nothing is let go on the first tick and simply
 * falls as a whole.</p>
 */
public enum ClothEdge
{
    /** The whole top row — a cape on shoulders, a curtain on a rail, a flag hung flat. */
    TOP,

    /** The left column — a flag on a pole. */
    LEFT,

    /** Just the two top corners — a banner that sags in the middle. */
    TOP_CORNERS,

    /** Nothing at all: the sheet is loose from the first tick. */
    NONE;

    public static ClothEdge of(String name)
    {
        for (ClothEdge edge : values())
        {
            if (edge.name().equalsIgnoreCase(name))
            {
                return edge;
            }
        }

        return TOP;
    }

    /**
     * Whether the vertex at column {@code c} of row {@code r} is held, on a grid of
     * {@code w} columns by {@code h} rows.
     */
    public boolean holds(int c, int r, int w, int h)
    {
        return switch (this)
        {
            case TOP -> r == 0;
            case LEFT -> c == 0;
            case TOP_CORNERS -> r == 0 && (c == 0 || c == w - 1);
            case NONE -> false;
        };
    }
}
