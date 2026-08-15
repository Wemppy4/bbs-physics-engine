package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.collision.CollisionShapes;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who hangs off whom in a ragdoll — the three-step answer, in one place.
 *
 * <p>It lived inside the ragdoll builder until the preview needed it too, and a preview that worked
 * out attachment its own way would be a preview that lies. Both callers now read the same answer:
 * the simulation builds joints from it, the viewport draws lines along it.</p>
 *
 * <p>In order of authority:</p>
 * <ol>
 * <li>the author's explicit "attaches to" on the bone;</li>
 * <li>the nearest marked ancestor up the bone tree;</li>
 * <li><b>geometry</b> — whatever is left loose grows onto the skeleton nearest-first, measured from
 * a bone's pivot to another part's shapes.</li>
 * </ol>
 *
 * <p>The third step exists because of Minecraft's own player rig: its arms, head, legs and torso
 * are all children of empty container bones, so no ancestor walk joins any of them to anything, and
 * without it a standard player ragdoll is six parts falling separately. Growing nearest-first
 * cannot make a cycle — a part only ever attaches to something already connected — and when the
 * whole rig is loose the bulkiest part is declared the trunk, which for a humanoid is the torso.
 * </p>
 */
public final class RagdollAttachment
{
    private RagdollAttachment()
    {}

    /**
     * Resolves every marked bone's parent bone.
     *
     * @param pieces the marked bone slots of one form, as the collector found them
     * @param model  the model those bones belong to, for the tree walk
     * @return bone name → parent bone name. A bone missing from the map is the trunk: it hangs off
     *         nothing, which is correct for exactly one part per ragdoll
     */
    public static Map<String, String> resolve(FormRagdoll config, List<CollisionCollector.Piece> pieces, Model model, MatrixCache matrices, Matrix4f actorWorld)
    {
        Map<String, CollisionCollector.Piece> byBone = new LinkedHashMap<>();

        for (CollisionCollector.Piece piece : pieces)
        {
            byBone.put(piece.label(), piece);
        }

        Map<String, String> parents = new HashMap<>();
        List<String> loose = new ArrayList<>();

        for (String bone : byBone.keySet())
        {
            String attachTo = config.get(bone).attachTo();
            String chosen = attachTo.isEmpty() || attachTo.equals(bone) || !byBone.containsKey(attachTo) ? null : attachTo;

            if (chosen == null)
            {
                chosen = nearestMarkedAncestor(model == null ? null : model.getGroup(bone), byBone);
            }

            if (chosen != null)
            {
                parents.put(bone, chosen);
            }
            else
            {
                loose.add(bone);
            }
        }

        if (loose.isEmpty())
        {
            return parents;
        }

        List<String> attached = new ArrayList<>(byBone.keySet());

        attached.removeAll(loose);

        if (attached.isEmpty())
        {
            String trunk = null;
            float best = -1F;

            for (String bone : loose)
            {
                float volume = shapesVolume(byBone.get(bone));

                if (volume > best)
                {
                    best = volume;
                    trunk = bone;
                }
            }

            loose.remove(trunk);
            attached.add(trunk);
        }

        /* Nearest loose bone first, so a hand two steps from the torso can arrive through an arm
         * that gets attached before it. */
        while (!loose.isEmpty())
        {
            String bestBone = null;
            String bestTarget = null;
            float bestDistance = Float.MAX_VALUE;

            for (String bone : loose)
            {
                Vector3f pivot = pivotWorld(byBone.get(bone), matrices, actorWorld);

                if (pivot == null)
                {
                    continue;
                }

                for (String target : attached)
                {
                    float distance = distanceToShapes(pivot, byBone.get(target), matrices, actorWorld);

                    if (distance < bestDistance)
                    {
                        bestDistance = distance;
                        bestBone = bone;
                        bestTarget = target;
                    }
                }
            }

            if (bestBone == null)
            {
                /* Nothing measurable left — bones whose frames the matrix cache does not have. */
                break;
            }

            parents.put(bestBone, bestTarget);
            loose.remove(bestBone);
            attached.add(bestBone);
        }

        return parents;
    }

    /** Walks up the model's bone tree to the first ancestor that is itself a marked part. */
    private static String nearestMarkedAncestor(ModelGroup group, Map<String, CollisionCollector.Piece> byBone)
    {
        ModelGroup parent = group == null ? null : group.parent;

        while (parent != null)
        {
            if (byBone.containsKey(parent.id))
            {
                return parent.id;
            }

            parent = parent.parent;
        }

        return null;
    }

    /**
     * A rough volume, for picking the trunk: every shape taken as its box. Exact volumes would
     * change the answer only when two candidates are within a third of each other, and a rig that
     * close to a tie has no meaningful trunk anyway.
     */
    private static float shapesVolume(CollisionCollector.Piece piece)
    {
        if (piece == null)
        {
            return 0F;
        }

        float volume = 0F;

        for (CollisionShapes.SubShape sub : piece.shapes())
        {
            volume += 8F * sub.half().x * sub.half().y * sub.half().z;
        }

        return volume;
    }

    /** Where a bone's pivot is, in the frame the caller measures in. */
    public static Vector3f pivotWorld(CollisionCollector.Piece piece, MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry entry = piece == null || matrices == null ? null : matrices.get(piece.path());

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        return new Matrix4f(actorWorld).mul(entry.matrix()).getTranslation(new Vector3f());
    }

    /**
     * How far {@code point} is from a part's shapes: through the part's frame into each shape's own
     * space, clamped against its extents. Boxes are exact; capsules and cylinders are treated as
     * their boxes, which for choosing the nearest neighbour is as good as exact.
     */
    private static float distanceToShapes(Vector3f point, CollisionCollector.Piece piece, MatrixCache matrices, Matrix4f actorWorld)
    {
        if (piece == null || piece.shapes().isEmpty() || matrices == null)
        {
            return Float.MAX_VALUE;
        }

        MatrixCacheEntry entry = matrices.get(piece.path());

        if (entry == null || entry.matrix() == null)
        {
            return Float.MAX_VALUE;
        }

        Vector3f local = new Matrix4f(actorWorld).mul(entry.matrix()).invert().transformPosition(new Vector3f(point));
        float best = Float.MAX_VALUE;

        for (CollisionShapes.SubShape sub : piece.shapes())
        {
            Vector3f p = new Vector3f(local).sub(sub.offset());

            new Quaternionf(sub.rotation()).conjugate().transform(p);

            float distance;

            if (sub.kind() == CollisionKind.SPHERE)
            {
                distance = Math.max(0F, p.length() - sub.half().x);
            }
            else
            {
                float dx = Math.max(0F, Math.abs(p.x) - sub.half().x);
                float dy = Math.max(0F, Math.abs(p.y) - sub.half().y);
                float dz = Math.max(0F, Math.abs(p.z) - sub.half().z);

                distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }

            best = Math.min(best, distance);
        }

        return best;
    }
}
