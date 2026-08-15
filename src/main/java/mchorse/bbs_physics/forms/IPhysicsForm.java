package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * What the addon adds to every BBS form: the rigid body modifier, the animation-strength handle,
 * and the runtime slot the simulation writes its answer into.
 *
 * <p>Implemented by the mixin on {@code Form}, which is how a form gains these without BBS knowing
 * anything about physics (Р5). The handle lives here rather than next to the ragdoll because §4
 * calls for <em>one</em> handle: a form is either a body or a ragdoll, and an author who learned
 * what the number means on a crate should not have to learn it again on a character.</p>
 */
public interface IPhysicsForm
{
    ValueData bbs_physics$getBody();

    ValueFloat bbs_physics$getAuthority();

    PhysicsBodyState bbs_physics$getBodyState();

    void bbs_physics$setBodyState(PhysicsBodyState state);
}
