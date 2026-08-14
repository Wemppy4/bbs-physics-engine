package mchorse.bbs_physics.client;

import mchorse.bbs_physics.engine.JoltEngine;
import net.fabricmc.api.ClientModInitializer;

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
    }
}
