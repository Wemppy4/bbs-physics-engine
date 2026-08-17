package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.utils.StringUtils;

/**
 * Walking a form and everything nested inside it, once, in the one order that matters.
 *
 * <p>Every part of the addon that looks for something in an actor — bodies, ragdolls, sheets,
 * strands, collision markup, models that have not loaded — is asking the same question of the same
 * tree, and each of them used to carry its own copy of the recursion. Ten copies of four lines is
 * cheap; ten copies of a <em>convention</em> is not, and there are two of those here that everything
 * else depends on being got right:</p>
 *
 * <ul>
 * <li><b>The path.</b> A form is addressed by its position in the tree — "0/2" is the third body
 * part of the first — and that string is the key its evaluated matrix is read back by. The index
 * advances for <em>every</em> slot, including the empty ones, because BBS's own matrix walk does;
 * a walk that skipped empties would name every form after an empty slot wrongly and quietly drive
 * the wrong thing.</li>
 * <li><b>The anchor.</b> Descending out of a model means everything below hangs on one of its
 * bones, and that bone is what a ragdoll moves. So a child of a model takes that bone as its
 * anchor, and anything deeper inherits it: a sheet two groups down under an arm still hangs on the
 * arm.</li>
 * </ul>
 */
public final class FormTreeWalk
{
    private FormTreeWalk()
    {}

    /** What a walk tells its caller about each form it reaches. */
    public interface Visitor
    {
        /**
         * @param path   where this form sits in the tree, by the convention above — empty for the
         *               root the walk started at
         * @param anchor the bone this form hangs on, as {@code modelPath/bone}, or null when
         *               nothing above it is a model
         * @return whether to descend into this form's own body parts
         */
        boolean visit(Form form, String path, String anchor);
    }

    /** Visits {@code root} and, unless the visitor says otherwise, everything below it. */
    public static void walk(Form root, Visitor visitor)
    {
        walk(root, "", null, visitor);
    }

    /**
     * The same, for a tree that does not start at an actor's root — the paths handed to the visitor
     * are prefixed with {@code path}, so they stay the keys the actor's matrix cache knows.
     */
    public static void walk(Form root, String path, Visitor visitor)
    {
        walk(root, path, null, visitor);
    }

    private static void walk(Form form, String path, String anchor, Visitor visitor)
    {
        if (form == null || !visitor.visit(form, path, anchor))
        {
            return;
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                String childAnchor = form instanceof ModelForm
                    ? StringUtils.combinePaths(path, part.bone.get())
                    : anchor;

                walk(child, StringUtils.combinePaths(path, String.valueOf(i)), childAnchor, visitor);
            }

            /* Outside the null check, mirroring the matrix walk: a partless slot still takes an
             * index — see the class note on why that is not a detail. */
            i += 1;
        }
    }
}
