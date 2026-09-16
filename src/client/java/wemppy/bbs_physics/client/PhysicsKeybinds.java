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

    private PhysicsKeybinds()
    {}
}
