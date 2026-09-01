package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.Settings;
import mchorse.bbs_mod.settings.ui.UISettingsOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import wemppy.bbs_physics.BBSPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the addon's settings into the settings screen, where they are looked for.
 *
 * <p>They are registered and loaded perfectly well without this — the screen simply has nowhere to
 * show them. Its sidebar is built from two modules named outright, {@code bbs} and {@code keybinds};
 * anything else an addon registers lands in the settings map and is never drawn. The one hook meant
 * for this, {@code RegisterSettingsUISectionEvent}, is a stub: it is posted, addons hand it their
 * builders, and nothing ever reads them back.</p>
 *
 * <p>So the tab is added at the end of the sidebar's own build, and selecting it does what the
 * screen's {@code selectCategory} does — except for the one line that makes it useless here, which
 * hard-codes the module to {@code bbs}. Everything the screen needs to draw a category is public or
 * shadowed below; the rendering itself is its own {@code refresh()}.</p>
 */
@Mixin(UISettingsOverlayPanel.class)
public abstract class UISettingsOverlayPanelMixin
{
    /** The one category the addon registers; see {@code BBSPhysicsSettings}. */
    private static final String BBS_PHYSICS$CATEGORY = "general";

    @Shadow
    public UIScrollView sidebar;

    @Shadow
    public UITextbox search;

    @Shadow
    private Settings settings;

    @Shadow
    private String selectedCategoryId;

    @Shadow
    private boolean isKeybindsSelected;

    @Shadow
    private UISettingsOverlayPanel.UISettingsTab currentTab;

    @Shadow
    public abstract void refresh();

    @Inject(method = "rebuildTabs", at = @At("TAIL"))
    private void bbs_physics$addTab(CallbackInfo info)
    {
        /* Nothing to show if the settings never registered — better a missing tab than one that
         * opens onto nothing. */
        if (BBSMod.getSettings().modules.get(BBSPhysics.MOD_ID) == null)
        {
            return;
        }

        IKey label = L10n.lang(BBSPhysics.MOD_ID + ".config.title");
        UISettingsOverlayPanel.UISettingsTab tab = new UISettingsOverlayPanel.UISettingsTab(
            label, Icons.SPHERE, BBS_PHYSICS$CATEGORY, false, this::bbs_physics$select);

        tab.tooltip(label, Direction.RIGHT);

        this.sidebar.add(tab);
    }

    /** The screen's own tab switch, with the addon's module in place of the hard-coded one. */
    private void bbs_physics$select(UISettingsOverlayPanel.UISettingsTab tab)
    {
        this.settings = BBSMod.getSettings().modules.get(BBSPhysics.MOD_ID);
        this.selectedCategoryId = BBS_PHYSICS$CATEGORY;
        this.isKeybindsSelected = false;

        if (this.currentTab != null)
        {
            this.currentTab.selected = false;
        }

        this.currentTab = tab;
        this.currentTab.selected = true;

        if (this.search != null)
        {
            this.search.setText("");
        }

        this.refresh();
    }
}
