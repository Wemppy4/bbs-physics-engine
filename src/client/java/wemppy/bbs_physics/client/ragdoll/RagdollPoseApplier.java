package wemppy.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.ragdoll.FormRagdolls;
import wemppy.bbs_physics.ragdoll.RagdollState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Substitutes the ragdoll's simulated pose into a model at draw time.
 *
 * <p>It writes where every constraint stage writes — {@code ModelGroup.orient} (the bone's full
 * local rotation, applied in place of the channels) and {@code ModelGroup.offset} (a shift in the
 * parent frame) — and it runs in the constraint phase between IK and the chain physics. Which is
 * the whole trick: the channels stay untouched FK truth, IK on ragdolled bones is faded out along
 * with the rest of the animated pose, and the hair chains that run right after anchor themselves to
 * bones that are already fallen. Nothing downstream had to learn anything.</p>
 *
 * <p><b>By the handle's weight, like every other stage of that phase.</b> The simulated frame is
 * mixed into what the pipeline had rather than replacing it, so 0.4 on the handle draws four tenths
 * of the fall — and, which is the point, a full 1 draws none of it and a full 0 draws all of it,
 * with nothing happening on any particular tick in between. The substitution used to be
 * all-or-nothing under a "handle below 1?" test, and that test was a threshold on a continuous
 * handle: harmless letting go, where the bodies are standing on the animation anyway, and a visible
 * jerk taking the character back, where they are only near it.</p>
 *
 * <p>The state holds each simulated bone's <em>pivot frame</em> in the model's flipped group space
 * — the space the render walk composes matrices in, actor-independent. Turning that absolute frame
 * into the local {@code orient}/{@code offset} takes the parent's composed frame, so the walk here
 * mirrors the renderer's own composition step for step, corrected bones included: a corrected
 * parent's children compose on the corrected frame, and bones that are themselves simulated get
 * their own absolute correction on top — errors never accumulate down the chain.</p>
 */
public final class RagdollPoseApplier
{
    /**
     * True while the film scene evaluates an actor's pose to drive the simulation. That walk must
     * see pure animation — the muscles' target — never the simulated pose, or the ragdoll would
     * chase its own tail.
     */
    private static boolean evaluating;

    /**
     * True while a form whose ragdoll owns the pose is being rendered. The chain physics reads its
     * anchor frames through a walk that skips {@code offset} by default (the IK stretch rule);
     * while a ragdoll's offsets are in the groups, they are the character's fall and must be seen.
     * Set per form by {@link #apply}, read by the {@code ModelPivotFrames} mixin.
     */
    private static boolean chainStretch;

    private RagdollPoseApplier()
    {}

    public static void setEvaluating(boolean value)
    {
        evaluating = value;
    }

    /**
     * Whether the walk running right now is the simulation's own — the one that asks where the
     * <em>animation</em> has everything, because that is the target every rig pulls towards. Read
     * by the body substitution too ({@code FormRendererMixin}): a form carrying the rigid body
     * modifier must answer that walk with its keyframes, not with where the body already is.
     */
    public static boolean isEvaluating()
    {
        return evaluating;
    }

    public static boolean isChainStretch()
    {
        return chainStretch;
    }

    /**
     * Applies the simulated pose of {@code form}'s ragdoll to its model, when there is one and it
     * is in charge. Safe to call for every model render — a form no scene owns, a ragdoll standing
     * at authority 1, or the scene's own evaluation walk all fall straight through.
     */
    public static void apply(Form form, ModelInstance instance, float transition)
    {
        chainStretch = false;

        if (evaluating || form == null || instance == null || !(instance.model instanceof Model cubic))
        {
            return;
        }

        /* Two sets of simulated bones, applied in this order and never mixed: the ragdoll's parts,
         * then the chain modifier's strands. Separate walks rather than one merged state because
         * they answer about different bones and the second one is composed on top of the first —
         * hair on a fallen head has to be placed against the head as it fell, and a walk that
         * started from the animation would place it where the head would have been.
         *
         * Order matters for nothing else: a bone belongs to one of them, never both (the chain
         * modifier claims bones the ragdoll does not have shapes for). */
        walk(cubic, FormRagdolls.getState(form), transition);
        walk(cubic, FormChains.getState(form), transition);
    }

    /** One state's substitution pass, when it has anything to say about this frame. */
    private static void walk(Model cubic, RagdollState state, float transition)
    {
        if (state == null || !state.isActive() || state.getWeight(transition) <= 0F)
        {
            return;
        }

        /* Any simulated bone at all means the old chain solver must read the offsets we are about
         * to write — its anchor walk skips them by default (the IK stretch rule), so a strand's
         * anchor would be read where the animation had it. */
        chainStretch = true;

        Walker walker = new Walker(state, transition);

        for (ModelGroup group : cubic.topGroups)
        {
            walker.walk(group, new Matrix4f());
        }
    }

    /** One application's scratch: the walk mirrors {@code applyGroupTransformations} exactly. */
    private static class Walker
    {
        private final RagdollState state;
        private final float transition;

        /** The simulation's share of the drawn pose for the bone being walked — see {@link #substitute}. */
        private float weight;

        private final Vector3f position = new Vector3f();
        private final Quaternionf rotation = new Quaternionf();
        private final Matrix4f inverse = new Matrix4f();
        private final Vector3f local = new Vector3f();
        private final Quaternionf parentRotation = new Quaternionf();
        private final Quaternionf simulated = new Quaternionf();

        private Walker(RagdollState state, float transition)
        {
            this.state = state;
            this.transition = transition;
        }

        private void walk(ModelGroup group, Matrix4f parent)
        {
            if (this.state.get(group.id, this.transition, this.position, this.rotation))
            {
                /* Each bone's own share: the form's handle everywhere, except on a torn bone,
                 * whose recorded authority is 0 from the tear on — drawn wholly fallen while its
                 * neighbours walk their keyframes untouched. */
                this.weight = this.state.getWeight(group.id, this.transition);

                if (this.weight > 0F)
                {
                    this.substitute(group, parent);
                }
            }

            Matrix4f matrix = new Matrix4f(parent);

            /* The renderer's own order: offset, translate, to pivot, rotate, scale, back from
             * pivot. The X sign flip in the translate is the cubic convention, copied exactly. */
            Vector3f pivot = group.initial.translate;
            Vector3f translate = group.current.translate;
            Vector3f scale = group.current.scale;

            if (group.offset != null)
            {
                matrix.translate(group.offset.x, group.offset.y, group.offset.z);
            }

            matrix.translate(-(translate.x - pivot.x) / 16F, (translate.y - pivot.y) / 16F, (translate.z - pivot.z) / 16F);
            matrix.translate(pivot.x / 16F, pivot.y / 16F, pivot.z / 16F);
            matrix.rotate(PhysicsRotations.evaluatedRotation(group));
            matrix.scale(scale.x, scale.y, scale.z);
            matrix.translate(-pivot.x / 16F, -pivot.y / 16F, -pivot.z / 16F);

            for (ModelGroup child : group.children)
            {
                this.walk(child, matrix);
            }
        }

        /**
         * Solves the one local rotation and parent-frame shift that land this bone's pivot frame
         * where the simulation has it, and blends them into what the pipeline had by the weight.
         *
         * <p><b>Blended, not written outright</b>, which is what the constraint stage's contract
         * asks of every stage ({@code ModelGroup.orient}): read the evaluated-so-far rotation, mix
         * your result against it by your weight, write the outcome. Ignoring the weight is what put
         * a cliff at the top of the handle — the substitution was all-or-nothing, so the last step
         * of a fade back to the animation was a jump of however far the bodies had failed to be
         * pulled. At weight 0 both lines below are the identity, and at 1 they are exactly what
         * they were before; the fade is now a fade at both ends.</p>
         *
         * <p>The animated side is read here rather than passed in because it is not the same thing
         * for every bone: {@code evaluatedRotation()} is whatever the channels and the IK solve
         * left, and {@code offset} is the IK stretch's shift where there was one.</p>
         */
        private void substitute(ModelGroup group, Matrix4f parent)
        {
            Vector3f pivot = group.initial.translate;
            Vector3f translate = group.current.translate;

            /* Where the pivot would land with no offset, in the parent's own coordinates: the
             * channel translate (with its X flip) plus the move to the pivot. */
            float dx = -(translate.x - pivot.x) / 16F + pivot.x / 16F;
            float dy = (translate.y - pivot.y) / 16F + pivot.y / 16F;
            float dz = (translate.z - pivot.z) / 16F + pivot.z / 16F;

            this.inverse.set(parent).invert();
            this.inverse.transformPosition(this.position, this.local);

            Vector3f offset = group.offset;
            float ox = offset == null ? 0F : offset.x;
            float oy = offset == null ? 0F : offset.y;
            float oz = offset == null ? 0F : offset.z;

            group.offset = new Vector3f(
                mix(ox, this.local.x - dx, this.weight),
                mix(oy, this.local.y - dy, this.weight),
                mix(oz, this.local.z - dz, this.weight));

            /* The local rotation that composes with the parent's to face where the body faces.
             * Unnormalized read because ancestor scale rides the frame; the result is normalized
             * for the same reason. */
            parent.getUnnormalizedRotation(this.parentRotation);
            this.parentRotation.conjugate().mul(this.rotation, this.simulated).normalize();

            /* Along the shorter arc, so a bone half a turn from its keyframes fades the near way
             * round rather than through the character. */
            group.orient = PhysicsRotations.evaluatedRotation(group).slerp(this.simulated, this.weight);
        }

        private static float mix(float animated, float simulated, float weight)
        {
            return animated + (simulated - animated) * weight;
        }
    }
}
