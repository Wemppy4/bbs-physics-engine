package wemppy.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.utils.StringUtils;
import wemppy.bbs_physics.client.collision.CollisionCollector;
import wemppy.bbs_physics.ragdoll.FormRagdoll;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which bones are welded into which falling part — the third thing a bone can be.
 *
 * <p>"Does this bone fall?" looked like a yes-or-no question, and it is not: a bone can be still
 * for two different reasons. A torso is still because the animation is driving it, and it should go
 * on shoving what it walks into. A piece of headwear on a falling head is still <em>relative to the
 * head</em> — it is not driven by anything, it is nailed on, exactly as it would be with no physics
 * at all. Answered the first way, as it was, the hat's collider stayed standing on the animation's
 * shoulders while the hat itself flew off with the head: the mesh in one place and the shape it
 * collides as in another.</p>
 *
 * <p>So a bone left out of the ragdoll that has a falling <b>ancestor</b> gets no body of its own.
 * Its shapes are carried into that ancestor's body and become part of its compound — one body, two
 * lumps. Which is better than the obvious alternative of a body held on by a fixed joint in every
 * way that matters: a joint is a spring and would let the hat shift under a hard landing, whereas
 * this cannot; the hat's mass joins the head's, as a hat's does; and a rig full of trinkets costs
 * the solver nothing extra instead of a body and a constraint apiece.</p>
 *
 * <p><b>The bone tree decides, and only the bone tree</b> — not the three-step attachment search
 * that joints use ({@link RagdollAttachment}). Welding says "you are a part of that thing", and the
 * one place that claim can be read off honestly is parenthood: geometry would happily weld an
 * excluded torso into a falling head for standing next to it. A bone with no falling ancestor keeps
 * the old behaviour and stays a kinematic body riding the animation.</p>
 *
 * <p>The walk passes straight through bones that are themselves welded, and through unmarked ones,
 * so a badge on a hat on a head is one body — the head's. The author draws the line wherever they
 * like by ticking a bone back into the ragdoll: a pompom marked as falling gets its own body and a
 * joint to the head, while the hat it sits on stays part of the head.</p>
 */
public final class RagdollWelds
{
    private RagdollWelds()
    {}

    /**
     * Whether {@code piece} is one of the bone slots of the form at {@code formPath}. The form's own
     * slot has the form's path and is a shape rather than a bone; a bone slot is the form's path
     * plus the bone name, which is the convention the matrix walk uses.
     */
    public static boolean isBonePiece(CollisionCollector.Piece piece, String formPath)
    {
        return !piece.path().equals(formPath) && piece.path().equals(StringUtils.combinePaths(formPath, piece.label()));
    }

    /** As {@link #resolve(FormRagdoll, List, String, Model)}, for callers holding the form. */
    public static Map<String, String> resolve(FormRagdoll config, List<CollisionCollector.Piece> pieces, String formPath, ModelForm form)
    {
        ModelInstance instance = form == null ? null : ModelFormRenderer.getModel(form);

        return resolve(config, pieces, formPath, instance != null && instance.model instanceof Model cubic ? cubic : null);
    }

    /**
     * Every welded bone of one form and the falling part it belongs to.
     *
     * @return welded bone name → owning part's bone name. A bone absent from the map is either a
     *         falling part itself or a kinematic bone; both are somebody else's business
     */
    public static Map<String, String> resolve(FormRagdoll config, List<CollisionCollector.Piece> pieces, String formPath, Model model)
    {
        if (model == null || pieces == null || pieces.isEmpty())
        {
            return Collections.emptyMap();
        }

        Set<String> marked = new LinkedHashSet<>();

        for (CollisionCollector.Piece piece : pieces)
        {
            if (isBonePiece(piece, formPath))
            {
                marked.add(piece.label());
            }
        }

        Map<String, String> welds = new LinkedHashMap<>();

        for (String bone : marked)
        {
            if (config.isPart(bone))
            {
                continue;
            }

            String owner = nearestPart(model.getGroup(bone), marked, config);

            if (owner != null)
            {
                welds.put(bone, owner);
            }
        }

        return welds;
    }

    /**
     * The first ancestor up the bone tree that is a falling part: marked up, and not ticked out of
     * the ragdoll. Ancestors that are neither — unmarked bones, and welded ones, which are about to
     * end up in this same body — are walked straight through.
     */
    private static String nearestPart(ModelGroup group, Set<String> marked, FormRagdoll config)
    {
        ModelGroup parent = group == null ? null : group.parent;

        while (parent != null)
        {
            if (marked.contains(parent.id) && config.isPart(parent.id))
            {
                return parent.id;
            }

            parent = parent.parent;
        }

        return null;
    }
}
