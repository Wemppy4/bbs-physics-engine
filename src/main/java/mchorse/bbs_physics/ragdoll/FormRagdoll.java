package mchorse.bbs_physics.ragdoll;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A model form's ragdoll setup: whether it ragdolls at all, which of the marked bones take part,
 * and how each of them is jointed.
 *
 * <p><b>Shape and participation are two questions.</b> The collision markup says what shape a bone
 * is; it does not say the bone should fall. Those were the same answer until an author asked for
 * the case that proves they are not: a character whose body and head both collide, but only the
 * head comes off. So the markup still decides what a bone <em>is</em>, and {@link #excluded} decides
 * whether the ragdoll claims it — an unclaimed bone stays a kinematic body, riding the animation and
 * shoving what it hits.</p>
 *
 * <p>Exclusions rather than a list of members, so the default needs no writing down and a bone
 * marked up later is a ragdoll part without anyone revisiting this. A bone absent from
 * {@link #joints} likewise uses {@link RagdollJoint#DEFAULT} — the soft cone — so an enabled ragdoll
 * with nothing else configured already works, and both collections only record what was changed.</p>
 */
public record FormRagdoll(boolean enabled, Set<String> excluded, Map<String, RagdollJoint> joints)
{
    public static final FormRagdoll EMPTY = new FormRagdoll(false, Collections.emptySet(), Collections.emptyMap());

    public FormRagdoll
    {
        excluded = excluded == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
        joints = joints == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(joints));
    }

    /** Whether the ragdoll claims {@code bone}, assuming the markup gave it a shape at all. */
    public boolean isPart(String bone)
    {
        return !this.excluded.contains(bone);
    }

    /** The joint of {@code bone} — the author's, or the default cone when they never touched it. */
    public RagdollJoint get(String bone)
    {
        return this.joints.getOrDefault(bone, RagdollJoint.DEFAULT);
    }

    /** Whether there is anything to store at all. */
    public boolean isEmpty()
    {
        return !this.enabled && this.excluded.isEmpty() && this.joints.isEmpty();
    }

    public FormRagdoll withEnabled(boolean enabled)
    {
        return new FormRagdoll(enabled, this.excluded, this.joints);
    }

    /** The same setup with one bone taken into the ragdoll, or left out of it. */
    public FormRagdoll withPart(String bone, boolean part)
    {
        Set<String> excluded = new LinkedHashSet<>(this.excluded);

        if (part)
        {
            excluded.remove(bone);
        }
        else
        {
            excluded.add(bone);
        }

        return new FormRagdoll(this.enabled, excluded, this.joints);
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

        return new FormRagdoll(this.enabled, this.excluded, joints);
    }
}
