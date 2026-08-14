package mchorse.bbs_physics;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The addon's hook into BBS, declared under the {@code bbs-addon} entry point. Its client
 * counterpart is {@link mchorse.bbs_physics.client.BBSPhysicsClientAddon}, under
 * {@code bbs-client-addon}.
 *
 * <p>BBS instantiates this class and scans it for {@link Subscribe} methods at the very top of
 * its own initialization, so every registration event it posts afterwards reaches this addon.</p>
 */
public class BBSPhysicsAddon implements BBSAddonMod
{
    public BBSPhysicsAddon()
    {
        BBSPhysics.LOGGER.info("Attached to BBS {}.", version("bbs"));
    }

    /**
     * Gives the addon's own assets a source of their own, so they can be addressed as
     * {@code bbs_physics:...} links anywhere BBS accepts one.
     */
    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event)
    {
        event.registerAddon(BBSPhysics.ASSETS, BBSPhysicsAddon.class);
    }

    /**
     * Registers the addon's form types.
     *
     * <p>Hooked to the settings event for one reason: <b>timing</b>. BBS builds its form factory
     * partway through its own initialization, and the addon's Fabric entry point runs before it
     * gets there — registering from there finds {@code getForms()} still null and takes the game
     * down at startup. Of the events BBS posts, this is the first one after the factories exist.
     * There is no "register forms" event to use instead; when there is, this moves.</p>
     *
     * <p>Registered on both sides, not just the client: a film carries its forms across the
     * network, and a server handed one has to be able to read it.</p>
     */
    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event)
    {
        BBSMod.getForms().register(new Link(BBSPhysics.MOD_ID, "body"), PhysicsBodyForm.class, null);
    }

    private static String version(String modId)
    {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(unknown)");
    }
}
