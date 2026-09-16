package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.forms.FormTreeWalk;

import java.util.Collections;
import java.util.IdentityHashMap;
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
        current = this;
        return this;
    }

    public static boolean allows(Form form, Kind component)
    {
        if (current == null)
        {
            return true;
        }

        if (current.descendants.contains(form))
        {
            return false;
        }

        if (form != current.target)
        {
            return true;
        }

        /* A body's shape/target comes from animation throughout its own tree. A bone chain
         * may ride this model's ragdoll, but must not read its own simulated strands. */
        return current.kind != Kind.BODY && (component == Kind.BODY
            || current.kind == Kind.CHAIN && component == Kind.RAGDOLL);
    }

    @Override
    public void close()
    {
        current = this.previous;
    }
}
