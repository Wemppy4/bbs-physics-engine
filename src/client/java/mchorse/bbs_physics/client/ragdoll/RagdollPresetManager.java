package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.presets.DataManager;

/**
 * Saved ragdoll setups, kept beside the model they describe — the same arrangement the collision
 * and IK presets use, and for the same reason: joints are written against one rig's bone names.
 */
public class RagdollPresetManager extends DataManager
{
    public static final RagdollPresetManager INSTANCE = new RagdollPresetManager();

    @Override
    protected Link getFile(String group)
    {
        return Link.assets(ModelManager.MODELS_PREFIX + group + "/ragdoll_presets.json");
    }
}
