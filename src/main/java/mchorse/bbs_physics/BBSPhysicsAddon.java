package mchorse.bbs_physics;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
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

    private static String version(String modId)
    {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(unknown)");
    }
}
