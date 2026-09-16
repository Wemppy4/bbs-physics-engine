package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.value.ValueKeyCombo;
import mchorse.bbs_mod.ui.utils.keys.KeybindSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.PhysicsKeybinds;

/** CML has no keybind registration event; append the addon category before settings are loaded. */
@Mixin(KeybindSettings.class)
public abstract class KeybindSettingsMixin
{
    @Inject(method = "register", at = @At("TAIL"))
    private static void bbs_physics$register(SettingsBuilder builder, CallbackInfo info)
    {
        builder.category("bbs_physics");
        builder.register(new ValueKeyCombo(PhysicsKeybinds.TOGGLE_DEBUG.id, PhysicsKeybinds.TOGGLE_DEBUG));
    }
}
