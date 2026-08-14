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

    public static void register(SettingsBuilder builder)
    {
        builder.category("general", Icons.PHYSICS);

        enabled = builder.getBoolean("enabled", true);
        debug = builder.getBoolean("debug", false);
    }
}
