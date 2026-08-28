package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.cubic.ModelInstance;
import org.joml.Vector3f;

/**
 * Two things about a drawn model that BBS answers with a method and CML keeps as a plain field.
 */
public final class PhysicsModels
{
    private PhysicsModels()
    {}

    /** How much the model is scaled by its own configuration. */
    public static Vector3f scale(ModelInstance model)
    {
        return model.scale;
    }

    /**
     * The name presets are filed under for this model — its own if it names one, otherwise the
     * model's id, so that two models never share a shelf by accident.
     */
    public static String poseGroup(ModelInstance model)
    {
        String group = model.poseGroup;

        return group == null || group.isEmpty() ? model.id : group;
    }
}
