package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * What the addon adds to <em>every</em> BBS form: its collision markup, its rigid body modifier, the
 * animation-strength handle, and the runtime slot the simulation writes its answer into.
 *
 * <p>Implemented by the mixin on {@code Form}, which is how a form gains these without BBS knowing
 * anything about physics (Р5) — one interface per mixin, so what a form carries and where it comes
 * from are the same list. Model forms carry more; see {@link IModelPhysicsForm}.</p>
 *
 * <p>The handle lives here rather than next to the ragdoll because §4 calls for <em>one</em> handle:
 * a form is either a body or a ragdoll, and an author who learned what the number means on a crate
 * should not have to learn it again on a character.</p>
 */
public interface IPhysicsForm
{
    /** The collision markup — what shape this form is, per bone or as a whole. */
    ValueData bbs_physics$getCollision();

    /** The stored rigid body modifier — the mark saying "this falls", and how. */
    ValueData bbs_physics$getBody();

    ValueFloat bbs_physics$getAuthority();

    /** One keyframable number of the rigid body modifier — see {@link PhysicsKnobValue}. */
    PhysicsKnobValue bbs_physics$getBodyKnob(BodyKnob knob);

    PhysicsBodyState bbs_physics$getBodyState();

    void bbs_physics$setBodyState(PhysicsBodyState state);
}
