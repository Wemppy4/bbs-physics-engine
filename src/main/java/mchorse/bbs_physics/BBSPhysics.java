package mchorse.bbs_physics;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The common entry point of the physics engine addon.
 *
 * <p>Everything this addon plugs into BBS with goes through {@link BBSPhysicsAddon} rather than
 * through here — BBS builds its registries during its own initialization, and the addon events
 * are the only place that is guaranteed to run at the right moment. This class is left for what
 * belongs to the mod itself: identifiers, the logger and, later, its own registrations.</p>
 */
public class BBSPhysics implements ModInitializer
{
    public static final String MOD_ID = "bbs_physics";

    /**
     * The asset source prefix this addon's own files are addressed by, as in
     * {@code bbs_physics:strings/en_us.json}.
     *
     * <p>BBS keeps its assets under the {@code assets:} prefix; sharing it would mean fighting
     * over file names with the host mod, so the addon gets a source pack of its own.</p>
     */
    public static final String ASSETS = MOD_ID;

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize()
    {}
}
