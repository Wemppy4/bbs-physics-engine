package mchorse.bbs_physics.chain;

import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.ragdoll.RagdollState;

/**
 * What the {@code ModelForm} mixin adds for the chain modifier: the stored setup and a slot for the
 * runtime pose a scene fills in.
 *
 * <p>The runtime side is a {@link RagdollState} — the same class the ragdoll answers into, because
 * it is the same question ("where does the simulation have these bones, in the model's own space")
 * and the same answer the renderer already knows how to substitute. A separate slot rather than a
 * shared one so that a model carrying both modifiers keeps two independent sets of bones: the
 * ragdoll's parts and the strands hanging off them.</p>
 */
public interface IChainForm
{
    /** The stored chain modifier — which bones are strands, and what they are like. */
    ValueData bbs_physics$getChain();

    /** Where the simulation has the strand bones, or null while no scene owns this form. */
    RagdollState bbs_physics$getChainState();

    void bbs_physics$setChainState(RagdollState state);
}
