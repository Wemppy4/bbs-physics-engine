package mchorse.bbs_physics.client.ragdoll;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.JointWireframe;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollJoint;
import mchorse.bbs_physics.ragdoll.RagdollJointKind;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws a ragdoll's joints over the model, in the form editor.
 *
 * <p>This is the answer to the most unpredictable thing in the addon. Which bone hangs off which is
 * decided in three steps (§5.3) — the author's own attachment, the nearest marked ancestor, and
 * failing both, geometry — and until now <b>the author had no way of learning which one fired</b>.
 * A standard player rig resolves entirely by geometry, so "why is the arm jointed to the torso and
 * not to the shoulder" was a question the interface simply refused to answer.</p>
 *
 * <p>What is drawn: a line from each bone's pivot to the pivot of whatever it hangs on, a dot at
 * the joint itself, and the colour of the joint's kind — so a knee that is still a cone rather than
 * a hinge is visible as a colour, before the character falls over sideways in the film.</p>
 *
 * <p><b>Welds are drawn too</b>, in a colour of their own ({@link RagdollWelds}): a bone ticked out
 * of the ragdoll under a falling one is not jointed to it, it <em>is</em> it, and the two readings
 * of an unticked bone — welded into its parent, or standing still on the animation — are otherwise
 * indistinguishable until the character falls.</p>
 *
 * <p>Depth testing is off for the same reason the collision preview turns it off: joints live
 * inside the model, and an overlay that respected depth would be an overlay nobody ever sees.</p>
 */
public final class RagdollPreview
{
    /** Welds, in a colour no joint kind uses — they are not a joint, which is the point of them. */
    private static final int WELD = 0xFFCC66FF;

    private RagdollPreview()
    {}

    public static void render(Form form, IEntity entity, MatrixStack stack, float transition)
    {
        render(form, entity, stack, transition, null);
    }

    /**
     * @param selection the path of the body part the editor has selected, whose joints are the ones
     *                  worth drawing; null means the root is selected and the root model's joints
     *                  are drawn, as before. A selected part that is not a ragdolled model draws
     *                  nothing at all — which is the honest answer, and quieter than showing
     *                  somebody else's skeleton over it
     */
    public static void render(Form form, IEntity entity, MatrixStack stack, float transition, String selection)
    {
        if (form == null || entity == null || stack == null)
        {
            return;
        }

        if (BBSPhysicsSettings.collisionPreview == null || !BBSPhysicsSettings.collisionPreview.get())
        {
            return;
        }

        /* The tree is walked from the root either way — the matrices are keyed by paths from it —
         * but whose ragdoll is drawn is the selection's business. */
        String prefix = selection == null ? "" : selection;
        Form target = selection == null ? form : FormUtils.getForm(form, selection);

        if (!(target instanceof ModelForm modelForm) || !FormRagdolls.isEnabled(target))
        {
            return;
        }

        ModelInstance instance = ModelFormRenderer.getModel(modelForm);
        Model model = instance != null && instance.model instanceof Model cubic ? cubic : null;

        if (model == null)
        {
            return;
        }

        try
        {
            MatrixCache matrices = FormUtilsClient.getRenderer(form).collectMatrices(entity, transition);
            FormRagdoll config = FormRagdolls.get(target);
            List<CollisionCollector.Piece> bones = bonePieces(form, matrices, model, prefix);

            if (bones.isEmpty())
            {
                return;
            }

            Map<String, String> welds = RagdollWelds.resolve(config, bones, prefix, model);
            List<CollisionCollector.Piece> pieces = new ArrayList<>(bones.size());
            List<CollisionCollector.Piece> candidates = new ArrayList<>(bones.size());

            for (CollisionCollector.Piece piece : bones)
            {
                if (welds.containsKey(piece.label()))
                {
                    continue;
                }

                /* Every unwelded marked bone is a candidate to hang off — the bones the animation
                 * keeps included, exactly as the scene resolves it: a part whose tree parent is
                 * not falling attaches to that kinematic bone and dangles from the walking body.
                 * Only the falling parts get joints drawn, but the line may end on a kept bone. */
                candidates.add(piece);

                if (config.isPart(piece.label()))
                {
                    pieces.add(piece);
                }
            }

            Matrix4f identity = new Matrix4f();
            Map<String, String> attachment = RagdollAttachment.resolve(config, candidates, model, matrices, identity);

            RenderSystem.disableDepthTest();

            try
            {
                for (CollisionCollector.Piece piece : pieces)
                {
                    draw(stack, piece, candidates, attachment, config, matrices, identity);
                }

                /* And the welds, in their own colour and towards the body the bone has become part
                 * of. Without a line here the interface says exactly nothing about the difference
                 * between a bone welded into a falling head and one standing still on the animation
                 * — and those two do opposite things the moment the character falls. */
                for (CollisionCollector.Piece piece : bones)
                {
                    String owner = welds.get(piece.label());

                    if (owner != null)
                    {
                        drawWeld(stack, piece, bones, owner, matrices, identity);
                    }
                }
            }
            finally
            {
                RenderSystem.enableDepthTest();
            }
        }
        catch (Throwable e)
        {
            /* A model mid-load trips the matrix walk, and a preview is the last thing that should
             * take the editor down with it. */
            BBSPhysics.LOGGER.warn("The ragdoll preview could not be drawn.", e);
        }
    }

    private static void draw(MatrixStack stack, CollisionCollector.Piece piece, List<CollisionCollector.Piece> pieces, Map<String, String> attachment, FormRagdoll config, MatrixCache matrices, Matrix4f identity)
    {
        Vector3f pivot = RagdollAttachment.pivotWorld(piece, matrices, identity);

        if (pivot == null)
        {
            return;
        }

        RagdollJoint joint = config.get(piece.label());
        String parent = attachment.get(piece.label());
        Vector3f target = parent == null ? null : RagdollAttachment.pivotWorld(find(pieces, parent), matrices, identity);

        JointWireframe.draw(stack, pivot, target, colorOf(joint, parent));
    }

    /** A welded bone's line: to the part it is nailed to, in the weld's own colour. */
    private static void drawWeld(MatrixStack stack, CollisionCollector.Piece piece, List<CollisionCollector.Piece> pieces, String owner, MatrixCache matrices, Matrix4f identity)
    {
        Vector3f pivot = RagdollAttachment.pivotWorld(piece, matrices, identity);

        if (pivot == null)
        {
            return;
        }

        JointWireframe.draw(stack, pivot, RagdollAttachment.pivotWorld(find(pieces, owner), matrices, identity), WELD);
    }

    private static CollisionCollector.Piece find(List<CollisionCollector.Piece> pieces, String bone)
    {
        for (CollisionCollector.Piece piece : pieces)
        {
            if (piece.label().equals(bone))
            {
                return piece;
            }
        }

        return null;
    }

    /**
     * The colour says what kind of joint it is, because that is what an author is looking for when
     * they open this: a knee still on the default cone is the single most common reason a ragdoll
     * walks like a drunk.
     */
    private static int colorOf(RagdollJoint joint, String parent)
    {
        if (parent == null)
        {
            /* The trunk. Nothing holds it, and that is correct for exactly one part. */
            return 0xFFFFFFFF;
        }

        if (joint.kind() == RagdollJointKind.HINGE)
        {
            return 0xFFFFAA33;
        }

        if (joint.kind() == RagdollJointKind.FIXED)
        {
            return 0xFF88FF88;
        }

        if (joint.kind() == RagdollJointKind.FREE)
        {
            return 0xFF888888;
        }

        return 0xFF33FFFF;
    }

    /**
     * Every marked bone slot of the model — the raw material the scene divides between falling
     * parts, welds and kinematic bones. Divided the same way here, so what is drawn is what will be
     * built. A bone that ends up in neither list draws nothing, and correctly so: it stays a
     * kinematic body riding the animation, with no joint and nothing to be welded into.
     */
    private static List<CollisionCollector.Piece> bonePieces(Form form, MatrixCache matrices, Model model, String prefix)
    {
        List<CollisionCollector.Piece> pieces = new ArrayList<>();

        for (CollisionCollector.Piece piece : CollisionCollector.collectAll(form, matrices))
        {
            /* Bone slots of the selected model only: a piece whose path is the form's own path is
             * the form's shape, not a bone, and it never becomes a ragdoll part. */
            if (RagdollWelds.isBonePiece(piece, prefix) && model.getGroup(piece.label()) != null)
            {
                pieces.add(piece);
            }
        }

        return pieces;
    }
}
