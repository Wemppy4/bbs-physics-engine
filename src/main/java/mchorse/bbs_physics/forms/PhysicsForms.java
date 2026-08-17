package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.chain.ChainForm;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.ragdoll.FormRagdolls;

/**
 * Reading and writing the physics a form carries: its rigid body modifier and the one handle both
 * modifiers share.
 *
 * <p>The same arrangement as {@code FormCollisions} and {@code FormRagdolls} — the data lives on
 * the form, put there by a mixin, and travels with it through save, copy and network for free.</p>
 */
public final class PhysicsForms
{
    /** Where the rigid body modifier is stored, prefixed so it cannot collide with a BBS key. */
    public static final String BODY_KEY = "bbs_physics_body";

    /**
     * The animation-strength handle — visible, which is all it takes for BBS to offer it as a
     * timeline track. Shared by the body and the ragdoll: §4 is explicit that this is one handle
     * with one meaning, and a form is one or the other, never both.
     */
    public static final String AUTHORITY_KEY = "bbs_physics_authority";

    private PhysicsForms()
    {}

    /** The body modifier of {@code form}, never null; empty when it has none. */
    public static FormBody getBody(Form form)
    {
        ValueData value = bodyValue(form);

        return value == null ? FormBody.EMPTY : BodyIO.fromData(value.get());
    }

    public static void setBody(Form form, FormBody body)
    {
        ValueData value = bodyValue(form);

        if (value == null)
        {
            return;
        }

        MapType map = BodyIO.toData(body);

        value.set(map.isEmpty() ? null : map);
    }

    /** Whether this form is a rigid body, without parsing the rest — the per-frame check. */
    public static boolean isBody(Form form)
    {
        ValueData value = bodyValue(form);

        return value != null && BodyIO.isEnabled(value.get());
    }

    /** Whether the form is simulated at all — by either modifier, or by being a soft form. */
    public static boolean isSimulated(Form form)
    {
        return isBody(form) || FormRagdolls.isEnabled(form) || FormChains.isEnabled(form)
            || form instanceof ClothForm || form instanceof BalloonForm || form instanceof ChainForm;
    }

    /**
     * Whether anything in this form's tree is simulated — the form itself or any body part below
     * it. What the scene asks per actor when deciding where the world's collision must exist:
     * an actor with nothing simulated anywhere never needs ground to catch anything.
     */
    public static boolean isSimulatedTree(Form form)
    {
        boolean[] found = new boolean[1];

        FormTreeWalk.walk(form, (child, path, anchor) ->
        {
            found[0] |= isSimulated(child);

            /* No early exit worth arranging: this is asked once per actor when a scene is built,
             * and a form tree is a handful of nodes. */
            return !found[0];
        });

        return found[0];
    }

    /**
     * The animation authority of {@code form} right now, clamped to 0..1. The keyframed track has
     * already been written into the value for whatever tick is being worked on; 1 for a form with
     * no handle at all, because that is what "not simulated" means.
     *
     * <p>A passive body reports a full 1 whatever the track says: passive means the animation owns
     * it, always, and honouring a keyframe that says otherwise would make the type do nothing.</p>
     */
    public static float getAuthority(Form form)
    {
        if (!(form instanceof IPhysicsForm physics))
        {
            return 1F;
        }

        /* Asked straight of the stored data rather than through {@link #getBody}, which would parse
         * the whole modifier into a record to read one flag. This is the most-called question in the
         * addon — every rig asks it every tick, every renderer asks it every frame — so the record
         * it used to build was the addon's largest single source of per-frame garbage. */
        ValueData body = physics.bbs_physics$getBody();

        if (body != null && BodyIO.isPassive(body.get()))
        {
            return 1F;
        }

        ValueFloat authority = physics.bbs_physics$getAuthority();

        return authority == null ? 1F : MathUtils.clamp(authority.get(), 0F, 1F);
    }

    /**
     * Whether the animation owns the form <em>outright</em>.
     *
     * <p>Only a full 1 counts. Anything less is a dynamic body being pulled towards the pose, which
     * is the whole point of the handle being continuous: a threshold in the middle turned a fade
     * from 1 to 0 into a switch flipping on whichever tick happened to cross it, and that jump is
     * what an author sees as the object twitching as it is released.</p>
     */
    public static boolean isKinematic(Form form)
    {
        return getAuthority(form) >= 1F;
    }

    /**
     * Sets the resting value of the handle — what the form does on a tick no keyframe covers.
     *
     * <p>Editing it in the form editor is how an author says "this starts out loose" without
     * opening the timeline at all; a keyframed track still overrules it wherever it exists.</p>
     */
    public static void setAuthority(Form form, float value)
    {
        if (form instanceof IPhysicsForm physics)
        {
            ValueFloat authority = physics.bbs_physics$getAuthority();

            if (authority != null)
            {
                authority.set(MathUtils.clamp(value, 0F, 1F));
            }
        }
    }

    public static PhysicsBodyState getState(Form form)
    {
        return form instanceof IPhysicsForm physics ? physics.bbs_physics$getBodyState() : null;
    }

    public static void setState(Form form, PhysicsBodyState state)
    {
        if (form instanceof IPhysicsForm physics)
        {
            physics.bbs_physics$setBodyState(state);
        }
    }

    private static ValueData bodyValue(Form form)
    {
        return form instanceof IPhysicsForm physics ? physics.bbs_physics$getBody() : null;
    }
}
