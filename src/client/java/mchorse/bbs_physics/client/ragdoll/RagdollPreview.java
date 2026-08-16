package mchorse.bbs_physics.client.ragdoll;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.StringUtils;
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
 * <p>Depth testing is off for the same reason the collision preview turns it off: joints live
 * inside the model, and an overlay that respected depth would be an overlay nobody ever sees.</p>
 */
public final class RagdollPreview
{
    private RagdollPreview()
    {}

    public static void render(Form form, IEntity entity, MatrixStack stack, float transition)
    {
        if (form == null || entity == null || stack == null || !(form instanceof ModelForm modelForm))
        {
            return;
        }

        if (BBSPhysicsSettings.collisionPreview == null || !BBSPhysicsSettings.collisionPreview.get())
        {
            return;
        }

        if (!FormRagdolls.isEnabled(form))
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
            FormRagdoll config = FormRagdolls.get(form);
            List<CollisionCollector.Piece> pieces = bonePieces(form, matrices, model, config);

            if (pieces.isEmpty())
            {
                return;
            }

            Matrix4f identity = new Matrix4f();
            Map<String, String> attachment = RagdollAttachment.resolve(config, pieces, model, matrices, identity);

            RenderSystem.disableDepthTest();

            try
            {
                for (CollisionCollector.Piece piece : pieces)
                {
                    draw(stack, piece, pieces, attachment, config, matrices, identity);
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
     * The marked bone slots this ragdoll claims — the parts it would be built from, filtered the
     * same way the scene filters them. A bone left out of the ragdoll draws no joint here because it
     * has none: it stays a kinematic body riding the animation.
     */
    private static List<CollisionCollector.Piece> bonePieces(Form form, MatrixCache matrices, Model model, FormRagdoll config)
    {
        List<CollisionCollector.Piece> pieces = new ArrayList<>();

        for (CollisionCollector.Piece piece : CollisionCollector.collectAll(form, matrices))
        {
            /* Bone slots only: a piece whose path is the form's own path is the form's shape, not a
             * bone, and it never becomes a ragdoll part. */
            if (piece.path().equals(StringUtils.combinePaths("", piece.label())) && model.getGroup(piece.label()) != null && config.isPart(piece.label()))
            {
                pieces.add(piece);
            }
        }

        return pieces;
    }
}
