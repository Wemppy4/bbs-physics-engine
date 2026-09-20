package wemppy.bbs_physics.client;

import mchorse.bbs_mod.api.client.events.FilmEvents;
import wemppy.bbs_physics.client.scene.FilmScenes;
import wemppy.bbs_physics.engine.JoltEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * The client entry point of the addon.
 *
 * <p>Note that this runs <em>after</em> BBS's own client initializer, which is where BBS posts
 * its client registration events — anything that has to be registered with BBS belongs in
 * {@link BBSPhysicsClientAddon}, not here.</p>
 */
public class BBSPhysicsClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        /* Started here rather than on first use, so that a platform without a Jolt library says
         * so among the addon's other start-up lines, instead of halfway through a film. The cost
         * is a library load; the alternative is finding out that physics is missing at the worst
         * possible moment. */
        JoltEngine.available();

        /* The four moments a simulation needs out of a running film. BBS posts them for every way
         * of running one — playback in the world, the editor's live scene, the frozen frame it
         * leaves standing, the recorder — which is what keeps the editor's viewport agreeing with
         * the exported video. They used to be taken by mixing into the film controller; since BBS
         * 2.6 they are events it posts on purpose, so four of its method names stopped being a
         * contract nobody had agreed to. */
        PhysicsApiIntegration.register();
        FilmEvents.CREATED.register(FilmScenes::onSetup);
        FilmEvents.TICK_AFTER.register(FilmScenes::onTick);
        FilmEvents.RENDER_AFTER.register(FilmScenes::onRender);
        FilmEvents.SHUTDOWN.register(FilmScenes::onShutdown);

        /* API 2 shuts down film controllers on reset. Also clear any addon-owned dormant/failure
         * entries on disconnect; clear() is idempotent and owns no already-closed worlds. */
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FilmScenes.clear());
    }
}
