package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Substitutes the ragdoll's simulated pose into a model at draw time.
 *
 * <p>It writes where every constraint stage writes — {@code ModelGroup.orient} (the bone's full
 * local rotation, applied in place of the channels) and {@code ModelGroup.offset} (a shift in the
 * parent frame) — and it runs in the constraint phase between IK and the chain physics. Which is
 * the whole trick: the channels stay untouched FK truth, IK on ragdolled bones is overwritten and
 * thereby muted, and the hair chains that run right after anchor themselves to bones that are
 * already fallen. Nothing downstream had to learn anything.</p>
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

        RagdollState state = FormRagdolls.getState(form);

        if (state == null || !state.isActive())
        {
            return;
        }

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

        private final Vector3f position = new Vector3f();
        private final Quaternionf rotation = new Quaternionf();
        private final Matrix4f inverse = new Matrix4f();
        private final Vector3f local = new Vector3f();
        private final Quaternionf parentRotation = new Quaternionf();

        private Walker(RagdollState state, float transition)
        {
            this.state = state;
            this.transition = transition;
        }

        private void walk(ModelGroup group, Matrix4f parent)
        {
            if (this.state.get(group.id, this.transition, this.position, this.rotation))
            {
                this.substitute(group, parent);
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
            matrix.rotate(group.evaluatedRotation());
            matrix.scale(scale.x, scale.y, scale.z);
            matrix.translate(-pivot.x / 16F, -pivot.y / 16F, -pivot.z / 16F);

            for (ModelGroup child : group.children)
            {
                this.walk(child, matrix);
            }
        }

        /**
         * Solves the one local rotation and parent-frame shift that land this bone's pivot frame
         * exactly where the simulation has it, and writes them where the renderer reads.
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

            group.offset = new Vector3f(this.local.x - dx, this.local.y - dy, this.local.z - dz);

            /* The local rotation that composes with the parent's to face where the body faces.
             * Unnormalized read because ancestor scale rides the frame; the result is normalized
             * for the same reason. */
            parent.getUnnormalizedRotation(this.parentRotation);

            group.orient = this.parentRotation.conjugate().mul(this.rotation, new Quaternionf()).normalize();
        }
    }
}
