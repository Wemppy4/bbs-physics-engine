package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * What the {@code ModelForm} mixin adds to every model form: the ragdoll setup, the animation
 * authority handle, and a slot for the runtime state a scene fills in.
 */
public interface IRagdollForm
{
    /** The stored ragdoll setup — joints and the enabled flag. */
    ValueData bbs_physics$getRagdoll();

    /**
     * The "animation strength" handle, 0..1 — the same handle the physics body form has, with the
     * same meaning: 1 is kinematic obedience, 0 is a free ragdoll, in between the muscles weaken.
     * A visible float value, which in BBS means it is a keyframeable timeline track for free.
     */
    ValueFloat bbs_physics$getAuthority();

    /** Where the simulation has the bones, or null while no scene owns this form. */
    RagdollState bbs_physics$getRagdollState();

    void bbs_physics$setRagdollState(RagdollState state);
}
