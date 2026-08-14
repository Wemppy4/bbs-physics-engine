package mchorse.bbs_physics;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The addon's hook into BBS, declared under the {@code bbs-addon} entry point.
 *
 * <p>BBS instantiates this class and scans it for {@link Subscribe} methods at the very top of
 * its own initialization, so every registration event it posts afterwards reaches this addon.</p>
 */
public class BBSPhysicsAddon implements BBSAddonMod
{
    public BBSPhysicsAddon()
    {
        /* BBS's event bus swallows whatever a subscriber throws, so a broken addon looks exactly
         * like an absent one in the log. These two lines are the counterweight: they say the
         * entry point fired at all, and which BBS it attached to. */
        BBSPhysics.LOGGER.info("Attached to BBS {}.", version("bbs"));

        /* The client-side events (localization, client settings, dashboard panels) are declared
         * in BBS's client source set, so a subscriber for them can't be reached from here — this
         * class has to keep working on a dedicated server, where those classes don't exist. Hence
         * the reflective hand-off.
         *
         * The timing works out: this constructor runs inside BBS's own initializer, which Fabric
         * calls before any client initializer, and the client events are posted from the latter. */
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
        {
            try
            {
                BBSMod.events.register(Class.forName("mchorse.bbs_physics.client.BBSPhysicsClientAddon")
                    .getDeclaredConstructor()
                    .newInstance());
            }
            catch (Exception e)
            {
                BBSPhysics.LOGGER.error("Failed to register the client side of the physics addon!", e);
            }
        }
    }

    /**
     * Gives the addon's own assets a source of their own, so they can be addressed as
     * {@code bbs_physics:...} links anywhere BBS accepts one.
     */
    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event)
    {
        event.provider.register(new InternalAssetsSourcePack(
            BBSPhysics.ASSETS,
            "assets/" + BBSPhysics.MOD_ID + "/assets",
            BBSPhysicsAddon.class
        ));

        BBSPhysics.LOGGER.info("Registered the \"{}\" asset source.", BBSPhysics.ASSETS);
    }

    private static String version(String modId)
    {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(unknown)");
    }
}
