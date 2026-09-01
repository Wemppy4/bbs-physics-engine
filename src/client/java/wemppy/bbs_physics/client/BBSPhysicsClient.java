package wemppy.bbs_physics.client;

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

        /* Leaving a world drops the film controllers without shutting them down, and a Jolt world
         * is native memory that no garbage collector will come back for. This is the one place
         * that is guaranteed to run in that case. */
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FilmScenes.clear());
    }
}
