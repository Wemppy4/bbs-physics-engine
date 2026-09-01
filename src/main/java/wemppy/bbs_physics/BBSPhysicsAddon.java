package wemppy.bbs_physics;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import wemppy.bbs_physics.actions.TearActionClip;
import wemppy.bbs_physics.balloon.BalloonForm;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.cloth.ClothForm;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The addon's hook into BBS, declared under the {@code bbs-addon} entry point. Its client
 * counterpart is {@link wemppy.bbs_physics.client.BBSPhysicsClientAddon}, under
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
     * Registers the addon's form types — one, cloth (Р12).
     *
     * <p>The rigid-body wrapper this addon once registered is gone (Р7): a body is a modifier on
     * an existing form. Cloth is a form again, and deliberately: it is not a behaviour bolted onto
     * something that already had a look — the sheet <em>is</em> the object, its rectangle belongs
     * to the simulation, and no existing form loses anything by not being wrapped in it.</p>
     *
     * <p>Hooked to the settings event for one reason: <b>timing</b>. BBS builds its form factory
     * partway through its own initialization, and the addon's Fabric entry point runs before it
     * gets there — registering from there finds {@code getForms()} still null and takes the game
     * down at startup. Of the events BBS posts, this is the first one after the factories exist.</p>
     *
     * <p>Registered on both sides, not just the client: a film carries its forms across the
     * network, and a server handed one has to be able to read it.</p>
     */
    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event)
    {
        BBSMod.getForms().register(new Link(BBSPhysics.MOD_ID, "cloth"), ClothForm.class, null);
        BBSMod.getForms().register(new Link(BBSPhysics.MOD_ID, "balloon"), BalloonForm.class, null);
        BBSMod.getForms().register(new Link(BBSPhysics.MOD_ID, "chain"), ChainForm.class, null);

        /* The Э5 action clips — "a push at a point" and "this bone comes off" — live on the same
         * action timeline as BBS's own clips and are registered the same way. Both sides again:
         * a film carries its clips across the network, and a server handed one has to be able to
         * read it (it just never acts on these — the physics scene is the one consumer). */
        BBSMod.getFactoryActionClips()
            .register(new Link(BBSPhysics.MOD_ID, "impulse"), ImpulseActionClip.class, new ClipFactoryData(Icons.SHARD, 0xff9500))
            .register(new Link(BBSPhysics.MOD_ID, "tear"), TearActionClip.class, new ClipFactoryData(Icons.CUT, 0xff4444));
    }

    private static String version(String modId)
    {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(unknown)");
    }
}
