package mchorse.bbs_physics;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.engine.PhysicsWorld;

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

    /**
     * How hard things fall, in blocks per second squared. Earth is 9.81 and is the default; the
     * point of exposing it is that a film is not physics homework — half gravity reads as slow
     * motion without touching a single keyframe, and zero is a space shot.
     *
     * <p>Blender keeps gravity in the scene's properties rather than on the body, and so do we
     * (§7.4). Changing it invalidates the recording, the same as any other edit would.</p>
     */
    public static ValueFloat gravity;

    /**
     * How many times Jolt re-solves collisions inside one film tick. A tick is 50 ms, which is long
     * for a solver aimed at 60 Hz frames, so two is the floor for stacked bodies not sinking into
     * each other; more costs time and buys stability in a pile.
     *
     * <p>Fixed per recording rather than adaptive, deliberately: the number of collision steps is
     * part of the simulation's arithmetic, and a value that drifted with the frame rate would make
     * a film stop being reproducible.</p>
     */
    public static ValueInt collisionSteps;

    public static void register(SettingsBuilder builder)
    {
        builder.category("general", Icons.PHYSICS);

        enabled = builder.getBoolean("enabled", true);
        debug = builder.getBoolean("debug", false);
        collisionPreview = builder.getBoolean("collision_preview", true);
        debugLineWidth = builder.getFloat("debug_line_width", 1F, 0.25F, 6F);

        gravity = builder.getFloat("gravity", 9.81F, 0F, 40F);
        collisionSteps = builder.getInt("collision_steps", PhysicsWorld.COLLISION_STEPS, 1, 8);

        worldRadius = builder.getInt("world_radius", 32, 8, 96);
        worldBelow = builder.getInt("world_below", 32, 4, 128);
        worldAbove = builder.getInt("world_above", 24, 4, 128);
    }
}
