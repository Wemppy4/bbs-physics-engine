package wemppy.bbs_physics.client;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.RegisterClientSettingsEvent;
import mchorse.bbs_mod.api.client.events.RegisterKeybindsEvent;
import mchorse.bbs_mod.api.client.events.RegisterDashboardPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterClipPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormEditorsEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormRenderersEvent;
import mchorse.bbs_mod.api.client.events.RegisterFormSectionsEvent;
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
import mchorse.bbs_mod.api.client.events.RegisterPreviewOverlaysEvent;
import mchorse.bbs_mod.api.client.events.RegisterTrackStylesEvent;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.actions.TearActionClip;
import wemppy.bbs_physics.balloon.BalloonForm;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.client.clips.UIImpulseActionClip;
import wemppy.bbs_physics.client.clips.UITearActionClip;
import wemppy.bbs_physics.client.forms.BalloonFormRenderer;
import wemppy.bbs_physics.client.forms.ChainFormRenderer;
import wemppy.bbs_physics.client.forms.ClothFormRenderer;
import wemppy.bbs_physics.client.forms.PhysicsFormSection;
import wemppy.bbs_physics.client.forms.PhysicsKeys;
import wemppy.bbs_physics.client.forms.UIBalloonFormPanel;
import wemppy.bbs_physics.client.forms.UIChainFormPanel;
import wemppy.bbs_physics.client.forms.UIClothFormPanel;
import wemppy.bbs_physics.client.forms.UISoftForm;
import wemppy.bbs_physics.client.scene.SceneStatusOverlay;
import wemppy.bbs_physics.cloth.ClothForm;
import wemppy.bbs_physics.forms.BodyKnob;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.ragdoll.RagdollKnob;
import wemppy.bbs_physics.chain.ChainKnob;

import java.util.Collections;

/**
 * The client half of {@link wemppy.bbs_physics.BBSPhysicsAddon}, declared under the
 * {@code bbs-client-addon} entry point.
 *
 * <p>It is split off and kept in the client source set so that BBS's client-only event classes
 * are never loaded on a dedicated server.</p>
 */
public class BBSPhysicsClientAddon implements BBSAddonMod
{
    @Subscribe
    public void onRegisterTrackStyles(RegisterTrackStylesEvent event)
    {
        event.register(PhysicsForms.AUTHORITY_KEY, Icons.PHYSICS, 0x62c980);

        for (BodyKnob knob : BodyKnob.values())
        {
            event.register(knob.id, Icons.BLOCK, 0xe5a45b);
        }

        for (RagdollKnob knob : RagdollKnob.values())
        {
            event.register(knob.id, Icons.POSE, 0xb28be0);
        }

        for (ChainKnob knob : ChainKnob.values())
        {
            event.register(knob.id, Icons.CURVES, 0x58bec9);
        }
    }

    @Subscribe
    public void onRegisterL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> Collections.singletonList(new Link(BBSPhysics.ASSETS, "strings/" + lang + ".json")));
    }

    @Subscribe
    public void onRegisterClientSettings(RegisterClientSettingsEvent event)
    {
        event.register(Icons.PHYSICS, BBSPhysics.MOD_ID, BBSPhysicsSettings::register);
    }

    @Subscribe
    public void onRegisterKeybinds(RegisterKeybindsEvent event)
    {
        event.register(PhysicsKeybinds.class);
        event.registerCategoryIcon("bbs_physics", Icons.PHYSICS);
    }

    @Subscribe
    public void onRegisterDashboard(RegisterDashboardPanelsEvent event)
    {
        event.dashboard.overlay.keys().register(PhysicsKeybinds.TOGGLE_DEBUG, () ->
        {
            BBSPhysicsSettings.debug.toggle();
        }).strict().active(() -> BBSPhysicsSettings.debug != null);
    }

    /**
     * How each of the addon's forms is drawn. The registry is keyed by the form's exact class and
     * then by its super classes, so an addon's form is as first-class as BBS's own.
     */
    @Subscribe
    public void onRegisterFormRenderers(RegisterFormRenderersEvent event)
    {
        event.register(ClothForm.class, ClothFormRenderer::new);
        event.register(BalloonForm.class, BalloonFormRenderer::new);
        event.register(ChainForm.class, ChainFormRenderer::new);
    }

    /** How each of the addon's forms is edited — the same lookup as the renderer. */
    @Subscribe
    public void onRegisterFormEditors(RegisterFormEditorsEvent event)
    {
        event.register(ClothForm.class, () -> new UISoftForm<>(UIClothFormPanel::new, PhysicsKeys.CLOTH_TITLE, Icons.MATERIAL));
        event.register(BalloonForm.class, () -> new UISoftForm<>(UIBalloonFormPanel::new, PhysicsKeys.BALLOON_TITLE, Icons.SPHERE));
        event.register(ChainForm.class, () -> new UISoftForm<>(UIChainFormPanel::new, PhysicsKeys.CHAIN_TITLE, Icons.CURVES));
    }

    /**
     * The addon's own tab of the form palette, where its three forms are picked from. Without it
     * they would exist and work with nowhere for an author to reach them.
     */
    @Subscribe
    public void onRegisterFormSections(RegisterFormSectionsEvent event)
    {
        event.register(PhysicsFormSection::new);
    }

    /**
     * The scene's readout over the editor's viewport, shown with the debug overlay. A layer of the
     * preview since BBS 2.6 — before it, the only way in was a mixin into the controller's HUD.
     */
    @Subscribe
    public void onRegisterPreviewOverlays(RegisterPreviewOverlaysEvent event)
    {
        event.register(SceneStatusOverlay::new);
    }

    /** The Э5 action clips' panels — the same lookup BBS's own clip panels sit in. */
    @Subscribe
    public void onRegisterClipPanels(RegisterClipPanelsEvent event)
    {
        event.register(ImpulseActionClip.class, UIImpulseActionClip::new);
        event.register(TearActionClip.class, UITearActionClip::new);
    }
}
