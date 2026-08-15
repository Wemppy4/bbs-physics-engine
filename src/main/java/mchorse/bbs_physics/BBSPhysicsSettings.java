package mchorse.bbs_physics;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * The addon's settings module. It shows up as its own section in BBS's settings overlay, and is
 * saved next to BBS's own configs as {@code config/bbs/settings/bbs_physics.json}.
 *
 * <p>Labels come from the addon's language files, keyed by the value's path — see
 * {@code assets/bbs_physics/assets/strings/en_us.json}.</p>
 */
public class BBSPhysicsSettings
{
    public static ValueBoolean enabled;
    public static ValueBoolean debug;

    /**
     * Whether the collision markup is drawn over the model it belongs to. Its own switch rather
     * than a share of the film's debug overlay: this one is for authoring, it belongs on while a
     * shape is being placed and off the rest of the time, and it is toggled straight from the
     * collision tab.
     */
    public static ValueBoolean collisionPreview;

    /**
     * How far around the scene the world's blocks are collected, in blocks.
     *
     * <p>Author-facing because there is no right answer: a shot in a room needs a fraction of what
     * a shot over a canyon does, and the cost is a block scan whose volume is this cubed. Beyond
     * the region there is simply no ground, and a body that leaves it falls forever — which looks
     * exactly like falling through the floor, so the status readout names it rather than leaving it
     * to be guessed at.</p>
     *
     * <p>Down reaches further than the default used to by a wide margin. Things fall; a scene set
     * on a ledge, a rooftop or a table over a drop had barely a dozen blocks of ground beneath it,
     * and anything that went over the edge left the world's collision within a second.</p>
     */
    public static ValueInt worldRadius;
    public static ValueInt worldBelow;
    public static ValueInt worldAbove;

    public static void register(SettingsBuilder builder)
    {
        builder.category("general", Icons.PHYSICS);

        enabled = builder.getBoolean("enabled", true);
        debug = builder.getBoolean("debug", false);
        collisionPreview = builder.getBoolean("collision_preview", true);

        worldRadius = builder.getInt("world_radius", 32, 8, 96);
        worldBelow = builder.getInt("world_below", 32, 4, 128);
        worldAbove = builder.getInt("world_above", 24, 4, 128);
    }
}
