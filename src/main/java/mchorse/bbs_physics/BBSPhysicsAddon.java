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

    /*
     * The addon registers no form types of its own any more.
     *
     * <p>It used to register one — "physics body", the wrapper you dropped a crate into. Р7 removed
     * it: physics is a modifier on an existing form now (a value the {@code Form} mixin adds), which
     * is how Blender, Unity and Godot all do it, and it spares the author three levels of nesting to
     * make a box fall. The timing lesson that made this method exist is worth keeping, though, in
     * case a form type is ever wanted again: BBS builds its form factory partway through its own
     * initialization, after the addon's Fabric entry point has already run, so a registration there
     * finds {@code getForms()} null and takes the game down at startup. The settings event is the
     * first one BBS posts after the factories exist.
     */

    private static String version(String modId)
    {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(unknown)");
    }
}
