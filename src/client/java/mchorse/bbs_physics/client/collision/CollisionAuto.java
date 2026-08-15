package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionSlot;
import mchorse.bbs_physics.collision.FormCollision;
import org.joml.Vector3f;

import java.util.List;
import java.util.Set;

/**
 * The automatic markup pass: a first draft of what a form collides as.
 *
 * <p><b>Nobody starts from empty</b> (§13, vinding 6): Unreal generates a Physics Asset the moment
 * it is created, Rigid Body Bones builds shapes when you switch it on, Blender takes the shape from
 * the mesh. Markup was a <em>condition of entry</em> here and is now a draft to correct — Р8.4 — so
 * this runs by itself when a modifier is added, not only when a button is found and pressed.</p>
 *
 * <p>What survives of the old default (Р6) is everything it was actually for: the unit is a bone
 * and never a cube, small bones are skipped by a size threshold (a hand full of finger joints adds
 * contacts and changes nothing anyone can see), hand-placed primitives are never overwritten, and
 * the bones BBS's own chain solver drives are excluded outright — see {@link ChainBones}.</p>
 */
public final class CollisionAuto
{
    /**
     * How big a bone has to be, in blocks, to be worth colliding with. Unreal's default pass works
     * the same way and lands on ten to fifteen bodies for a rig of sixty bones.
     */
    public static final float DEFAULT_THRESHOLD = 0.25F;

    private CollisionAuto()
    {}

    /**
     * Marks up {@code form} and returns the result, leaving anything the author did by hand alone.
     *
     * <p>A model is marked bone by bone. Anything else — a block, an item, a group — gets one box
     * the size of what it draws, which is the sensible whole of "what shape is this crate".</p>
     */
    public static FormCollision mark(Form form, FormCollision collision, float threshold)
    {
        ModelInstance instance = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;
        Model model = instance != null && instance.model instanceof Model cubic ? cubic : null;

        if (model == null)
        {
            return markSelf(form, collision);
        }

        Set<String> chains = ChainBones.of(form, model);
        Vector3f scale = instance.getScale();
        FormCollision result = collision;

        for (ModelGroup group : model.getAllGroups())
        {
            String bone = group.id;

            if (chains.contains(bone))
            {
                /* Hair, a cape, a tail. Marked explicitly as "none" rather than skipped, so that a
                 * later pass with a smaller threshold cannot quietly pick it up. */
                result = result.with(bone, CollisionSlot.NONE);

                continue;
            }

            if (result.get(bone).mode() == CollisionMode.SHAPES)
            {
                /* Hand-placed primitives. The pass is a draft to correct, and throwing away the
                 * corrections would make it useless as one. */
                continue;
            }

            boolean big = CollisionShapes.boneSize(model, bone, scale) >= threshold;

            result = result.with(bone, big ? CollisionSlot.AUTO : CollisionSlot.NONE);
        }

        return result;
    }

    /** One box around what the form draws — a block is measured from its real outline. */
    private static FormCollision markSelf(Form form, FormCollision collision)
    {
        if (collision.get(FormCollision.SELF).mode() == CollisionMode.SHAPES)
        {
            return collision;
        }

        return collision.with(FormCollision.SELF, new CollisionSlot(CollisionMode.SHAPES, List.of(FormBounds.of(form))));
    }

    /**
     * Whether a form has nothing marked up at all, which is what makes the pass worth running when
     * a modifier is added. A form the author already marked is left exactly as they left it.
     */
    public static boolean isBlank(FormCollision collision)
    {
        return collision.isEmpty();
    }
}
