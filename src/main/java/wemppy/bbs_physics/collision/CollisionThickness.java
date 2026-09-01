package wemppy.bbs_physics.collision;

/**
 * Which way a pixel plate's thickness stands off the pixels it collides for — the one thing the
 * geometry cannot decide for a sheet ({@link CollisionMode#PIXELS}).
 *
 * <p>A plate has to have thickness (the engine solves nothing without it), and that thickness
 * has to be on one side of the painted surface or the other. For a cube with an inside, "away
 * from the inside" is a fair default. A sheet of no depth has no inside: two strands drawn as
 * cards, one in front of the head and one behind it, want their thickness on opposite sides,
 * and nothing in the model says which — the author does, here.</p>
 */
public enum CollisionThickness
{
    /**
     * Away from the cube's centre; for a sheet, on the side its front, right or top face is
     * drawn on — the side an author sees first.
     */
    OUTWARD("outward"),

    /** Into the cube; for a sheet, on the side of its back, left or bottom face. */
    INWARD("inward"),

    /** Straddling the surface, half each way. */
    CENTERED("centered");

    public final String id;

    CollisionThickness(String id)
    {
        this.id = id;
    }

    public static CollisionThickness byId(String id, CollisionThickness fallback)
    {
        for (CollisionThickness value : values())
        {
            if (value.id.equals(id))
            {
                return value;
            }
        }

        return fallback;
    }
}
