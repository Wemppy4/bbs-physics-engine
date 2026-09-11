package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import wemppy.bbs_physics.client.chain.ChainMute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps BBS's own chain solver off the bones our chain modifier drives — see {@link ChainMute} for
 * why two owners for one strand is a strand doing neither thing.
 *
 * <p>The whole of it is one redirect, at the question BBS asks of each bone while compiling a
 * model's chains: a bone whose strand we own answers "no chain here", and BBS walks on. Chains we
 * do not own compile exactly as before, which is the point — an author may keep a skirt on the old
 * physics while the hair moves to ours.</p>
 *
 * <p>Targeted by name rather than by class literal because {@code ModelPhysicsCache} is
 * package-private and cannot be named from here at all. That is also why the filtering moved: up
 * to BBS 2.4 the whole config was one value on the form, and this redirected the <em>read</em> of
 * it; since 2.6 a chain is declared on the bone it starts at, so the question is asked per bone
 * and answered per bone.</p>
 */
@Mixin(targets = "mchorse.bbs_mod.cubic.physics.ModelPhysicsCache")
public class ModelPhysicsCacheMixin
{
    @Redirect(
        method = "compileFresh",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/forms/forms/utils/FormBone;hasPhysicsChain()Z"
        ),
        remap = false
    )
    private static boolean bbs_physics$muteClaimedChains(FormBone bone, IModel model, ModelForm form)
    {
        /* The bones in between are not walked yet at this point — BBS builds that list a few lines
         * later, and only for the chains it kept — so the claim is answered on the two ends. */
        return bone.hasPhysicsChain() && !ChainMute.claims(form, bone.getId(), bone.physicsEnd.get(), null);
    }
}
