package mchorse.bbs_physics.client;

import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterClientSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;

import java.util.Collections;

/**
 * The client half of {@link mchorse.bbs_physics.BBSPhysicsAddon}, split off so that the events
 * declared in BBS's client source set are never touched on a dedicated server.
 */
public class BBSPhysicsClientAddon
{
    @Subscribe
    public void onRegisterL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> Collections.singletonList(new Link(BBSPhysics.ASSETS, "strings/" + lang + ".json")));

        /* BBS loads its language files just before posting this event, so registering alone would
         * leave the addon's strings unread until the next language switch — every label would show
         * its raw key on the first launch. One extra pass over a handful of small JSON files at
         * start up is the cheapest way to have them right from the first frame. */
        event.l10n.reload();
    }

    @Subscribe
    public void onRegisterClientSettings(RegisterClientSettingsEvent event)
    {
        event.register(Icons.PHYSICS, BBSPhysics.MOD_ID, BBSPhysicsSettings::register);
    }
}
