package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionSlot;
import mchorse.bbs_physics.collision.FormCollision;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Finds the marked-up collision in a form tree.
 *
 * <p>The markup only says what shape things are; who is a body and who is part of one is decided
 * here, and differently by each caller (§5.2). An actor turns every marked slot into a kinematic
 * body of its own, so a hand can shove a crate independently of a knee. A physics body welds
 * everything below it into one shape, so a model falls as a single piece.</p>
 *
 * <p>A nested physics body is never entered by either: it is its own body, and having it counted
 * twice would put two colliders in the same place, fighting each other.</p>
 */
public final class CollisionCollector
{
    /**
     * One marked-up slot found in a form tree.
     *
     * @param path   the matrix-cache path of the frame these shapes are expressed in — a form's
     *               path, or a form's path plus a bone name
     * @param label  what to call this piece when reporting it, for a human
     * @param shapes the shapes themselves, in that frame
     */
    public record Piece(String path, String label, List<CollisionShapes.SubShape> shapes)
    {}

    private CollisionCollector()
    {}

    /**
     * Everything an actor collides with, from its root form down — not entering physics bodies,
     * which look after themselves.
     */
    public static List<Piece> collectActor(Form root, MatrixCache matrices)
    {
        List<Piece> pieces = new ArrayList<>();

        walk(root, "", matrices, pieces, true);

        return pieces;
    }

    /**
     * Everything one physics body is made of: the markup of the forms nested inside it. The body
     * itself contributes no shape of its own — it is a mark saying "this falls", and what it looks
     * like (and therefore what it collides as) is whatever was put in it.
     */
    public static List<Piece> collectBody(PhysicsBodyForm body, String path, MatrixCache matrices)
    {
        List<Piece> pieces = new ArrayList<>();

        walkParts(body, path, matrices, pieces, true);

        return pieces;
    }

    /**
     * Every marked-up slot in a tree, physics bodies included — what the preview draws, because an
     * author looking at a model wants to see all of its collision, not the part that happens to
     * belong to whichever body they are not standing in.
     */
    public static List<Piece> collectAll(Form root, MatrixCache matrices)
    {
        List<Piece> pieces = new ArrayList<>();

        walk(root, "", matrices, pieces, false);

        return pieces;
    }

    private static void walk(Form form, String path, MatrixCache matrices, List<Piece> pieces, boolean stopAtBodies)
    {
        if (form == null)
        {
            return;
        }

        if (stopAtBodies && form instanceof PhysicsBodyForm)
        {
            return;
        }

        collectForm(form, path, matrices, pieces);
        walkParts(form, path, matrices, pieces, stopAtBodies);
    }

    private static void walkParts(Form form, String path, MatrixCache matrices, List<Piece> pieces, boolean stopAtBodies)
    {
        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                walk(child, StringUtils.combinePaths(path, String.valueOf(i)), matrices, pieces, stopAtBodies);
            }

            /* Outside the null check, mirroring the matrix walk: a partless slot still takes an index. */
            i += 1;
        }
    }

    /** The markup of one form: its own shape, plus its bones when it is a model. */
    private static void collectForm(Form form, String path, MatrixCache matrices, List<Piece> pieces)
    {
        FormCollision collision = FormCollisions.get(form);

        if (collision.isEmpty())
        {
            return;
        }

        CollisionSlot self = collision.get(FormCollision.SELF);

        if (self.mode() == CollisionMode.SHAPES)
        {
            add(pieces, path, form.getDisplayName(), CollisionShapes.ofSelf(self, frameScale(matrices, path)));
        }

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        ModelInstance instance = ModelFormRenderer.getModel(modelForm);
        IModel model = instance == null ? null : instance.model;

        if (model == null)
        {
            return;
        }

        Collection<String> known = model.getAllGroupKeys();

        for (Map.Entry<String, CollisionSlot> entry : collision.slots().entrySet())
        {
            String bone = entry.getKey();

            /* Markup outlives the rig it was written against — a preset from another model, a bone
             * renamed since. A body built for a bone that is not there would never be told where to
             * stand, and would sit at the scene's origin colliding with whatever happens to be
             * standing on it. */
            if (bone.equals(FormCollision.SELF) || !known.contains(bone))
            {
                continue;
            }

            String bonePath = StringUtils.combinePaths(path, bone);

            add(pieces, bonePath, bone, CollisionShapes.ofBone(model, bone, entry.getValue(), frameScale(matrices, bonePath)));
        }
    }

    private static void add(List<Piece> pieces, String path, String label, List<CollisionShapes.SubShape> shapes)
    {
        if (!shapes.isEmpty())
        {
            pieces.add(new Piece(path, label, shapes));
        }
    }

    /**
     * How much the frame at {@code path} scales what is drawn in it — the model's display scale,
     * the form's transform, every parent's transform, all at once.
     *
     * <p>It has to be baked into the shapes because a Jolt shape does not scale with the body it
     * belongs to: the body follows the frame's position and rotation only, so a model at 2× would
     * otherwise collide at 1×. The reading is exact for a uniform scale and an approximation for a
     * non-uniform one under rotation, where no single set of extents is right anyway. A scale
     * animated mid-film is not followed — the shapes are built once.</p>
     */
    private static Vector3f frameScale(MatrixCache matrices, String path)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);
        Matrix4f matrix = entry == null ? null : entry.matrix();

        if (matrix == null)
        {
            return new Vector3f(1F);
        }

        Vector3f scale = matrix.getScale(new Vector3f());

        /* A degenerate frame — a form scaled to nothing — would otherwise produce shapes Jolt
         * refuses to build. One is as good a guess as any for something that is not being drawn. */
        if (scale.x < 1.0e-4F || scale.y < 1.0e-4F || scale.z < 1.0e-4F)
        {
            return new Vector3f(1F);
        }

        return scale;
    }
}
