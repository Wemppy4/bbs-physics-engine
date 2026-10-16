package wemppy.bbs_physics;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
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
    public static ValueBoolean manualCalculation;
    public static ValueBoolean calculationShortcutMigrated;

    /**
     * Whether a heavy scene may step on several threads. On by default: the result is the same to
     * the bit either way (see {@link wemppy.bbs_physics.engine.PhysicsThreads}), so this is only
     * the way out if the threads ever misbehave on someone's machine.
     */
    public static ValueBoolean threads;

    /**
     * Whether the collision markup is drawn over the model it belongs to. Its own switch rather
     * than a share of the film's debug overlay: this one is for authoring, it belongs on while a
     * shape is being placed and off the rest of the time, and it is toggled straight from the
     * collision tab.
     */
    public static ValueBoolean collisionPreview;

    /**
     * How thick the outlines of the debug overlays are drawn, as a multiple of a deliberately fine
     * default.
     *
     * <p>Author-facing because there is no one right weight: a quarter-pixel face plate has to be
     * compared against the model up close, where a heavy outline is thicker than the thing it
     * outlines and reads as a narrow box; a whole rig looked at from across the set needs lines that
     * survive the distance.</p>
     *
     * <p>The outlines are bars of geometry rather than GL lines, so this is a real thickness in the
     * world rather than a hint the driver may ignore — a core profile is free to clamp every line
     * width to one, which is what a line-width setting would have run into.</p>
     */
    public static ValueFloat debugLineWidth;

    /**
     * Whether a plate — a box thinner than a quarter pixel along one axis — is outlined as a flat
     * rectangle in its mid-plane rather than as the twelve edges of the sliver it really is.
     *
     * <p>On by default: a plate is a surface as far as an author is concerned, and twelve edges
     * around a quarter-pixel box read as a narrow box, which is the one thing the overlay must not
     * suggest. Off shows the honest sliver, for when the actual thickness is what is being
     * looked at.</p>
     */
    public static ValueBoolean debugFlatPlates;

    /**
     * Two sections: how the addon works for this player, and what it draws for them. What the
     * simulation computes — gravity, speed, precision, the world around — is the film's own, see
     * {@link wemppy.bbs_physics.engine.FilmPhysics}; a game setting there made the same film come
     * out differently on every machine.
     */
    public static void register(SettingsBuilder builder)
    {
        builder.category("general", Icons.PHYSICS);

        enabled = builder.getBoolean("enabled", true);
        manualCalculation = builder.getBoolean("manual_calculation", false);
        threads = builder.getBoolean("threads", true);
        calculationShortcutMigrated = builder.getBoolean("calculation_shortcut_migrated", false);
        calculationShortcutMigrated.invisible();

        builder.category("display", Icons.VISIBLE);

        collisionPreview = builder.getBoolean("collision_preview", true);
        debug = builder.getBoolean("debug", false);
        debugLineWidth = builder.getFloat("debug_line_width", 1F, 0.25F, 6F);
        debugFlatPlates = builder.getBoolean("debug_flat_plates", true);
    }
}
