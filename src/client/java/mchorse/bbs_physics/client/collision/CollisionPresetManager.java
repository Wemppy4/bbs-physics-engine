package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.presets.DataManager;

/**
 * Saved collision markup, kept beside the model it describes — the same arrangement BBS's IK
 * presets use, and for the same reason: markup is written against one rig's bone names, so it is
 * only ever worth offering next to that rig.
 */
public class CollisionPresetManager extends DataManager
{
    public static final CollisionPresetManager INSTANCE = new CollisionPresetManager();

    @Override
    protected Link getFile(String group)
    {
        return Link.assets(ModelManager.MODELS_PREFIX + group + "/collision_presets.json");
    }
}
