package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import wemppy.bbs_physics.collision.FormCollisions;
import wemppy.bbs_physics.forms.BodyKnob;
import wemppy.bbs_physics.forms.IPhysicsForm;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.forms.PhysicsAuthorityValue;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsForms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every form its physics: the collision markup, the rigid body modifier, the
 * animation-strength handle, and the runtime slot the simulation answers into.
 *
 * <p>Everything stored here is one more value in the form's own group, so it is saved, loaded,
 * copied, pasted and sent over the network by the machinery that already does all of that —
 * nothing in the addon has to know how a film is stored. The markup and the modifier are marked
 * invisible, which in BBS means "not a timeline track": a shape and a switch are descriptions, not
 * things to animate. The handle is deliberately visible, which is exactly what turns it into a
 * track (and it hides itself while the form has no physics — see {@link PhysicsAuthorityValue}).
 * The modifier's numbers are visible values of the same kind ({@link PhysicsKnobValue}), one per
 * knob, which is what lets an author keyframe a body's friction or gravity: the blob keeps only
 * what cannot be animated.</p>
 *
 * <p>{@code canPersist} is overridden here — a plain method, which the mixin merges in as an
 * override of {@code ValueGroup}'s — so that a knob is written to the file only while it means
 * something: while the modifier is on the form, or while the author has moved it off its default.
 * Every form in every film would otherwise carry six physics numbers it never uses.</p>
 *
 * <p>All of it on the base {@code Form} rather than per type, because none of it is a property of
 * being a model: a block, an item and a group have a shape too, and any of them can be dropped.
 * That is the whole of Р7 in one line — physics is something an existing object <em>gains</em>,
 * not a wrapper it has to be put inside.</p>
 */
@Mixin(Form.class)
public class FormMixin implements IPhysicsForm
{
    @Unique
    private ValueData bbs_physics$collision;

    @Unique
    private ValueData bbs_physics$body;

    @Unique
    private ValueFloat bbs_physics$authority;

    @Unique
    private PhysicsBodyState bbs_physics$bodyState;

    @Unique
    private PhysicsKnobValue[] bbs_physics$bodyKnobs;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$addPhysics(CallbackInfo info)
    {
        ValueData collision = new ValueData(FormCollisions.KEY);
        ValueData body = new ValueData(PhysicsForms.BODY_KEY);

        collision.invisible();
        body.invisible();

        ValueFloat authority = new PhysicsAuthorityValue(PhysicsForms.AUTHORITY_KEY);

        authority.slider();

        this.bbs_physics$collision = collision;
        this.bbs_physics$body = body;
        this.bbs_physics$authority = authority;

        Form form = (Form) (Object) this;

        form.add(collision);
        form.add(body);
        form.add(authority);

        BodyKnob[] knobs = BodyKnob.values();

        this.bbs_physics$bodyKnobs = new PhysicsKnobValue[knobs.length];

        for (BodyKnob knob : knobs)
        {
            PhysicsKnobValue value = new PhysicsKnobValue(knob.id, knob.fallback, knob.min, knob.max, PhysicsForms::isBody);

            this.bbs_physics$bodyKnobs[knob.ordinal()] = value;
            form.add(value);
        }
    }

    /**
     * Overrides {@code ValueGroup.canPersist} for this form — see the class note. Everything that
     * is not a physics knob is written as before.
     */
    protected boolean canPersist(BaseValue value)
    {
        return !(value instanceof PhysicsKnobValue knob) || knob.isWorthStoring();
    }

    @Override
    public PhysicsKnobValue bbs_physics$getBodyKnob(BodyKnob knob)
    {
        return this.bbs_physics$bodyKnobs[knob.ordinal()];
    }

    @Override
    public ValueData bbs_physics$getCollision()
    {
        return this.bbs_physics$collision;
    }

    @Override
    public ValueData bbs_physics$getBody()
    {
        return this.bbs_physics$body;
    }

    @Override
    public ValueFloat bbs_physics$getAuthority()
    {
        return this.bbs_physics$authority;
    }

    @Override
    public PhysicsBodyState bbs_physics$getBodyState()
    {
        return this.bbs_physics$bodyState;
    }

    @Override
    public void bbs_physics$setBodyState(PhysicsBodyState state)
    {
        this.bbs_physics$bodyState = state;
    }
}
