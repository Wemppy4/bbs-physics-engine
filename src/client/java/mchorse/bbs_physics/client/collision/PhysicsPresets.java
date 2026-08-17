package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.presets.DataManager;

/**
 * Saved markup, kept beside the model it describes — the same arrangement BBS's IK presets use, and
 * for the same reason: all of it is written against one rig's bone names, so it is only ever worth
 * offering next to that rig.
 *
 * <p>One class, one file per kind of preset. The collision shapes and the ragdoll joints answer
 * different questions about the same skeleton and are copied separately, but there was never
 * anything different about <em>how</em> they are stored.</p>
 */
public class PhysicsPresets extends DataManager
{
    public static final PhysicsPresets COLLISION = new PhysicsPresets("collision_presets.json");
    public static final PhysicsPresets RAGDOLL = new PhysicsPresets("ragdoll_presets.json");

    private final String file;

    private PhysicsPresets(String file)
    {
        this.file = file;
    }

    @Override
    protected Link getFile(String group)
    {
        return Link.assets(ModelManager.MODELS_PREFIX + group + "/" + this.file);
    }
}
