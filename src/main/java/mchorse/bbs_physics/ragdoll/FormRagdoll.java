package mchorse.bbs_physics.ragdoll;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A model form's ragdoll setup: whether it ragdolls at all, and how each bone is jointed.
 *
 * <p>Deliberately <em>only</em> the joints. Which bones become bodies is not decided here — it is
 * the collision markup (§5.2): a bone marked up in the collision tab is a ragdoll part, a bone
 * that is not marked does not exist to physics. Measuring the model twice, once per tab, would
 * mean two markups drifting apart.</p>
 *
 * <p>A bone absent from {@link #joints} uses {@link RagdollJoint#DEFAULT} — the soft cone. So an
 * enabled ragdoll with nothing else configured already works, and the map only records what the
 * author changed.</p>
 */
public record FormRagdoll(boolean enabled, Map<String, RagdollJoint> joints)
{
    public static final FormRagdoll EMPTY = new FormRagdoll(false, Collections.emptyMap());

    public FormRagdoll
    {
        joints = joints == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(joints));
    }

    /** The joint of {@code bone} — the author's, or the default cone when they never touched it. */
    public RagdollJoint get(String bone)
    {
        return this.joints.getOrDefault(bone, RagdollJoint.DEFAULT);
    }

    /** Whether there is anything to store at all. */
    public boolean isEmpty()
    {
        return !this.enabled && this.joints.isEmpty();
    }

    public FormRagdoll withEnabled(boolean enabled)
    {
        return new FormRagdoll(enabled, this.joints);
    }

    /** The same setup with one bone's joint replaced — or dropped, when it is back to the default. */
    public FormRagdoll with(String bone, RagdollJoint joint)
    {
        Map<String, RagdollJoint> joints = new LinkedHashMap<>(this.joints);

        if (joint == null || joint.isDefault())
        {
            joints.remove(bone);
        }
        else
        {
            joints.put(bone, joint);
        }

        return new FormRagdoll(this.enabled, joints);
    }
}
