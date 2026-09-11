package wemppy.bbs_physics;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.BBSApi;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterActionClipsEvent;
import mchorse.bbs_mod.api.events.RegisterFormsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
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
    /**
     * The addon API this addon is written against. Checked here rather than discovered halfway
     * through a film: BBS tells the user which of the two to update, instead of the mismatch
     * surfacing as a missing method somewhere in the middle of a scene.
     */
    public static final int API_VERSION = 1;

    public BBSPhysicsAddon()
    {
        BBSApi.requireVersion(BBSPhysics.MOD_ID, API_VERSION);

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
     * Registers the addon's form types — cloth (Р12), balloon and chain.
     *
     * <p>The rigid-body wrapper this addon once registered is gone (Р7): a body is a modifier on
     * an existing form. These three are forms again, and deliberately: they are not behaviours
     * bolted onto something that already had a look — the sheet <em>is</em> the object, its
     * rectangle belongs to the simulation, and no existing form loses anything by not being
     * wrapped in it.</p>
     *
     * <p>Registered on both sides, not just the client: a film carries its forms across the
     * network, and a server handed one has to be able to read it.</p>
     */
    @Subscribe
    public void onRegisterForms(RegisterFormsEvent event)
    {
        event.forms.register(new Link(BBSPhysics.MOD_ID, "cloth"), ClothForm.class, null);
        event.forms.register(new Link(BBSPhysics.MOD_ID, "balloon"), BalloonForm.class, null);
        event.forms.register(new Link(BBSPhysics.MOD_ID, "chain"), ChainForm.class, null);
    }

    /**
     * The Э5 action clips — "a push at a point" and "this bone comes off" — live on the same
     * action timeline as BBS's own clips and are registered the same way.
     *
     * <p>Both sides again: a film carries its clips across the network, and a server handed one
     * has to be able to read it (it just never acts on these — the physics scene is the one
     * consumer).</p>
     */
    @Subscribe
    public void onRegisterActionClips(RegisterActionClipsEvent event)
    {
        event.factory
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
