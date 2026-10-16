package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.forms.FormTreeWalk;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.ragdoll.FormRagdolls;
import wemppy.bbs_physics.ragdoll.RagdollState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A drive reads its own animation, but inherits the physical pose of its parents. Suppressing
 * the whole scene would lose those parents; substituting the driven object would make it chase
 * its own output. The scope also covers anchor resolution, and is restored after failed walks.
 */
public final class PoseEvaluation implements AutoCloseable
{
    public enum Kind { FORM, BODY, RAGDOLL, CHAIN }

    private static PoseEvaluation current;

    private PoseEvaluation previous;
    private final Form target;
    private final Kind kind;
    private final Set<Form> descendants = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Whether the walk running in this scope took in anything simulated — see {@link #noteTakenIn}. */
    private boolean tookIn;

    public PoseEvaluation(Form target, Kind kind)
    {
        this.target = target;
        this.kind = kind;

        FormTreeWalk.walk(target, (form, path, anchor) ->
        {
            if (form != target)
            {
                this.descendants.add(form);
            }

            return true;
        });
    }

    public PoseEvaluation enter()
    {
        this.previous = current;
        this.tookIn = false;
        current = this;
        return this;
    }

    public static boolean allows(Form form, Kind component)
    {
        return current == null || current.allowsHere(form, component);
    }

    /** {@link #allows}, asked of this scope whether or not it is the one entered. */
    private boolean allowsHere(Form form, Kind component)
    {
        if (this.descendants.contains(form))
        {
            return false;
        }

        if (form != this.target)
        {
            return true;
        }

        /* A body's shape/target comes from animation throughout its own tree. A bone chain
         * may ride this model's ragdoll, but must not read its own simulated strands. */
        return this.kind != Kind.BODY && (component == Kind.BODY
            || this.kind == Kind.CHAIN && component == Kind.RAGDOLL);
    }

    /**
     * Whether a walk in this scope would take in anything simulated among {@code forms} — the same
     * questions the substitutions ask on their way through the walk, asked before it.
     *
     * <p>A walk that takes in nothing is the animation alone, the same whoever it is walked for, and
     * may be shared or kept. Each substitution draws only at a weight above zero, that is with its
     * handle below a full 1: a character standing on its keyframes takes nothing in.</p>
     */
    public boolean takesIn(List<Form> forms)
    {
        for (Form form : forms)
        {
            PhysicsBodyState body = PhysicsForms.getState(form);

            if (body != null && body.isSimulated() && body.getWeight(1F) > 0F && this.allowsHere(form, Kind.BODY))
            {
                return true;
            }

            if (draws(FormRagdolls.getState(form)) && this.allowsHere(form, Kind.RAGDOLL))
            {
                return true;
            }

            if (draws(FormChains.getState(form)) && this.allowsHere(form, Kind.CHAIN))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean draws(RagdollState state)
    {
        return state != null && state.isActive() && state.getWeight(1F) > 0F;
    }

    /** Said by a substitution as it draws the simulation into the walk running in this scope. */
    public static void noteTakenIn()
    {
        if (current != null)
        {
            current.tookIn = true;
        }
    }

    /** Whether the last walk in this scope took in anything simulated — see {@link #noteTakenIn}. */
    public boolean tookIn()
    {
        return this.tookIn;
    }

    @Override
    public void close()
    {
        current = this.previous;
    }
}
