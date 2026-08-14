package mchorse.bbs_physics.client;

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
    {}
}
