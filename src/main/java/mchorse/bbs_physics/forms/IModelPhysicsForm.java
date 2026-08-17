package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.ragdoll.RagdollState;

/**
 * What the addon adds to a <em>model</em> form on top of {@link IPhysicsForm}: the two modifiers
 * that need a skeleton to mean anything, and a runtime slot for each.
 *
 * <p>On {@code ModelForm} rather than {@code Form} because both are properties of having bones — a
 * block has a shape, so collision goes on every form, but there is nothing on it to joint or to
 * hang.</p>
 *
 * <p>Both runtime slots are a {@link RagdollState}, because it is the same question ("where does the
 * simulation have these bones, in the model's own space") and the same answer the renderer already
 * knows how to substitute. Two slots rather than one shared so that a model carrying both modifiers
 * keeps two independent sets of bones: the ragdoll's parts, and the strands hanging off them.</p>
 */
public interface IModelPhysicsForm
{
    /** The stored ragdoll setup — which bones are excluded, and how each is jointed. */
    ValueData bbs_physics$getRagdoll();

    /** Where the simulation has the ragdoll's bones, or null while no scene owns this form. */
    RagdollState bbs_physics$getRagdollState();

    void bbs_physics$setRagdollState(RagdollState state);

    /** The stored chain modifier — which bones are strands, and what they are like. */
    ValueData bbs_physics$getChain();

    /** Where the simulation has the strand bones, or null while no scene owns this form. */
    RagdollState bbs_physics$getChainState();

    void bbs_physics$setChainState(RagdollState state);
}
