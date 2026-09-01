package wemppy.bbs_physics.client;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterClientSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.actions.TearActionClip;
import wemppy.bbs_physics.balloon.BalloonForm;
import wemppy.bbs_physics.client.clips.UIImpulseActionClip;
import wemppy.bbs_physics.client.clips.UITearActionClip;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.client.forms.BalloonFormRenderer;
import wemppy.bbs_physics.client.forms.ChainFormRenderer;
import wemppy.bbs_physics.client.forms.ClothFormRenderer;
import wemppy.bbs_physics.client.forms.PhysicsKeys;
import wemppy.bbs_physics.client.forms.UIBalloonFormPanel;
import wemppy.bbs_physics.client.forms.UIChainFormPanel;
import wemppy.bbs_physics.client.forms.UIClothFormPanel;
import wemppy.bbs_physics.client.forms.UISoftForm;
import wemppy.bbs_physics.cloth.ClothForm;

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
    public void onRegisterL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> Collections.singletonList(new Link(BBSPhysics.ASSETS, "strings/" + lang + ".json")));
    }

    @Subscribe
    public void onRegisterClientSettings(RegisterClientSettingsEvent event)
    {
        /* No physics icon in CML's set; the sphere is the nearest thing it ships. */
        event.register(Icons.SPHERE, BBSPhysics.MOD_ID, BBSPhysicsSettings::register);

        /* How cloth is drawn and how it is edited. Both registries are static maps keyed by the
         * form's class, so an addon's form is as first-class as BBS's own. Done from an event
         * rather than from the Fabric entry point for the same timing reason the form type itself
         * is — see BBSPhysicsAddon. */
        FormUtilsClient.register(ClothForm.class, ClothFormRenderer::new);
        FormUtilsClient.register(BalloonForm.class, BalloonFormRenderer::new);
        FormUtilsClient.register(ChainForm.class, ChainFormRenderer::new);

        UIFormEditor.register(ClothForm.class, () -> new UISoftForm<>(UIClothFormPanel::new, PhysicsKeys.CLOTH_TITLE, Icons.MATERIAL));
        UIFormEditor.register(BalloonForm.class, () -> new UISoftForm<>(UIBalloonFormPanel::new, PhysicsKeys.BALLOON_TITLE, Icons.SPHERE));
        UIFormEditor.register(ChainForm.class, () -> new UISoftForm<>(UIChainFormPanel::new, PhysicsKeys.CHAIN_TITLE, Icons.CURVES));

        /* The Э5 action clips' panels — the same static registry BBS's own clip panels sit in. */
        UIClip.register(ImpulseActionClip.class, UIImpulseActionClip::new);
        UIClip.register(TearActionClip.class, UITearActionClip::new);
    }
}
