package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.utils.Anchor;

/** A partial release follows the held target until physics owns the pose completely. */
public final class PhysicsAnchor
{
    private PhysicsAnchor() {}

    public static Anchor release(Anchor anchor, float authority)
    {
        return anchor.isFadeOut() && authority > 0F && authority < 1F ? anchor.previous.copy() : anchor;
    }
}
