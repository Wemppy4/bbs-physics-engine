package wemppy.bbs_physics.client;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import org.lwjgl.glfw.GLFW;

/** Remappable shortcuts registered with BBS's keybind settings. */
public final class PhysicsKeybinds
{
    public static final KeyCombo TOGGLE_DEBUG = new KeyCombo("toggle_debug",
        L10n.lang("bbs_physics.config.general.debug"), GLFW.GLFW_KEY_F5, GLFW.GLFW_KEY_LEFT_SHIFT)
        .categoryKey("bbs_physics").category(L10n.lang("keybinds.config.bbs_physics.title"));

    public static final KeyCombo CALCULATE = new KeyCombo("calculate",
        L10n.lang("bbs_physics.calculate"), GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_LEFT_SHIFT)
        .categoryKey("bbs_physics").category(L10n.lang("keybinds.config.bbs_physics.title"));

    /** Move only the previous default, once; preserve custom assignments. */
    public static void migrateCalculationShortcut()
    {
        var migrated = wemppy.bbs_physics.BBSPhysicsSettings.calculationShortcutMigrated;
        if (migrated == null || migrated.get()) return;
        var settings = mchorse.bbs_mod.BBSMod.getSettings().modules;
        var keys = settings.get("keybinds");
        var physics = settings.get(wemppy.bbs_physics.BBSPhysics.MOD_ID);
        if (keys == null || physics == null) return;
        if (CALCULATE.keys.equals(java.util.List.of(GLFW.GLFW_KEY_F5,
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_LEFT_SHIFT)))
        {
            CALCULATE.keys.set(0, GLFW.GLFW_KEY_R);
            keys.save();
        }
        migrated.set(true);
        physics.save();
    }

    private PhysicsKeybinds()
    {}
}
