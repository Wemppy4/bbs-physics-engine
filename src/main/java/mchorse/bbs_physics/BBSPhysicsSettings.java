package mchorse.bbs_physics;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
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

    public static void register(SettingsBuilder builder)
    {
        builder.category("general", Icons.PHYSICS);

        enabled = builder.getBoolean("enabled", true);
        debug = builder.getBoolean("debug", false);
        collisionPreview = builder.getBoolean("collision_preview", true);
    }
}
