package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.settings.values.core.ValueData;

/**
 * What the {@code ModelForm} mixin adds to every model form: the ragdoll setup and a slot for the
 * runtime state a scene fills in.
 *
 * <p>The animation-strength handle is <em>not</em> here. It moved onto {@code Form} itself when the
 * rigid body stopped being a form of its own (Р7): §4 asks for one handle with one meaning, and a
 * form is a body or a ragdoll, never both — see {@code PhysicsForms#getAuthority}.</p>
 */
public interface IRagdollForm
{
    /** The stored ragdoll setup — joints and the enabled flag. */
    ValueData bbs_physics$getRagdoll();

    /** Where the simulation has the bones, or null while no scene owns this form. */
    RagdollState bbs_physics$getRagdollState();

    void bbs_physics$setRagdollState(RagdollState state);
}
