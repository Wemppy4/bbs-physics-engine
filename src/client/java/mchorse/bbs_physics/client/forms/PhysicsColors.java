package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.collision.CollisionMode;

/**
 * The colours the addon's overlays and lists agree on.
 *
 * <p>They have to agree: the dot beside a bone in the Physics tab and the mark beside the same bone
 * in the Collision tab are the same fact, and an author who learns "cyan means measured" in one
 * place should not find it means something else in the other.</p>
 */
public final class PhysicsColors
{
    private PhysicsColors()
    {}

    /**
     * How a bone's shape was described: measured from its own cubes, read off their painted pixels,
     * or placed by hand.
     */
    public static int markup(CollisionMode mode)
    {
        return switch (mode)
        {
            case AUTO -> Colors.CYAN;
            case PIXELS -> Colors.GREEN;
            default -> Colors.ORANGE;
        };
    }
}
