package mchorse.bbs_physics.client.collision;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws a form's collision markup over the form itself, in the form editor.
 *
 * <p>Marking up blind is the difference between a tool and a guessing game: a capsule is placed by
 * moving it until it sits on the limb, and that needs the limb and the capsule on screen at the
 * same time. The film's debug overlay shows the finished result, but by then the editor has been
 * closed.</p>
 *
 * <p>Drawn without depth testing on purpose. Almost every collider is <em>inside</em> the model it
 * belongs to, so an overlay that respected depth would be an overlay that is never visible.</p>
 *
 * <p>The shapes are collected at unit scale and then multiplied by the frame they belong to, which
 * is the opposite of what the simulation does — it bakes the scale into the shape and drops it from
 * the frame, because a Jolt shape does not scale. The two agree exactly for a uniform scale, and
 * for a non-uniform one the preview follows what is <em>drawn</em>, which is the more useful of the
 * two while shapes are being placed by eye.</p>
 */
public final class CollisionPreview
{
    private CollisionPreview()
    {}

    /**
     * @param selection the path of the body part the editor has selected, so only its markup is
     *                  drawn; null draws the whole tree (the root is selected, or there is no
     *                  editor at all). See {@code EditorPreview.selection}
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

        List<CollisionCollector.Piece> pieces = CollisionCollector.collectAll(form, null);

        if (pieces.isEmpty())
        {
            return;
        }

        RenderSystem.disableDepthTest();

        try
        {
            MatrixCache matrices = FormUtilsClient.getRenderer(form).collectMatrices(entity, transition);

            for (CollisionCollector.Piece piece : pieces)
            {
                if (!owns(selection, piece.path()))
                {
                    continue;
                }

                MatrixCacheEntry entry = matrices.get(piece.path());

                if (entry == null || entry.matrix() == null)
                {
                    continue;
                }

                stack.push();
                MatrixStackUtils.multiply(stack, entry.matrix());

                for (CollisionShapes.SubShape sub : piece.shapes())
                {
                    Vector3f offset = sub.offset();

                    stack.push();
                    stack.translate(offset.x, offset.y, offset.z);
                    stack.multiply(sub.rotation());

                    CollisionWireframe.draw(stack, sub.kind(), sub.half(), 0.2F, 1F, 0.5F, 1F);

                    stack.pop();
                }

                stack.pop();
            }
        }
        catch (Throwable e)
        {
            /* A model that has not finished loading trips the matrix walk, and a preview is the
             * last thing that should take the editor down with it. */
            BBSPhysics.LOGGER.warn("The collision preview could not be drawn.", e);
        }
        finally
        {
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * Whether a piece belongs to the selected body part — itself, its bones, or a form nested
     * inside it. A piece of the part at {@code 0/2} is {@code 0/2}, {@code 0/2/head} or
     * {@code 0/2/…}; the check is on the separator so that {@code 0/21} is not mistaken for a
     * child of {@code 0/2}.
     */
    static boolean owns(String selection, String path)
    {
        return selection == null || path.equals(selection) || path.startsWith(selection + "/");
    }
}
