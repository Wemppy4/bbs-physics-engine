package mchorse.bbs_physics.client;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterClientSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.client.forms.ClothFormRenderer;
import mchorse.bbs_physics.client.forms.UIClothForm;
import mchorse.bbs_physics.cloth.ClothForm;

import java.util.Collections;

/**
 * The client half of {@link mchorse.bbs_physics.BBSPhysicsAddon}, declared under the
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
        event.register(Icons.PHYSICS, BBSPhysics.MOD_ID, BBSPhysicsSettings::register);

        /* How cloth is drawn and how it is edited. Both registries are static maps keyed by the
         * form's class, so an addon's form is as first-class as BBS's own. Done from an event
         * rather than from the Fabric entry point for the same timing reason the form type itself
         * is — see BBSPhysicsAddon. */
        FormUtilsClient.register(ClothForm.class, ClothFormRenderer::new);
        UIFormEditor.register(ClothForm.class, UIClothForm::new);
    }
}
