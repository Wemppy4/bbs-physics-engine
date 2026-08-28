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
 * whether the ragdoll claims it.</p>
 *
 * <p><b>An unclaimed bone is one of two things</b>, and the scene tells them apart by itself, from
 * the bone tree — there is no third setting to get wrong. With no falling bone above it, it stays a
 * kinematic body riding the animation and shoving what it hits: the torso that walks on. Underneath
 * a falling bone, it is welded into that bone's body — headwear on a head — because "I do not fall
 * and my parent does" can only mean nailed to it. Ticking a bone back into the ragdoll is therefore
 * also how an author says "this one <em>does</em> swing free", pompom on a welded hat included.</p>
 *
 * <p>Exclusions rather than a list of members, so the default needs no writing down and a bone
 * marked up later is a ragdoll part without anyone revisiting this. A bone absent from
 * {@link #joints} likewise uses {@link RagdollJoint#DEFAULT} — the soft cone — so an enabled ragdoll
 * with nothing else configured already works, and both collections only record what was changed.</p>
 *
 * <p>The whole-ragdoll knobs live here beside the per-bone joints, and they are what an author
 * turns first: how heavy the body is, how much its limbs resist, whether it fights to hold its
 * pose ({@link #muscles}) or hangs like a puppet with the strings cut.</p>
 *
 * @param mass         the whole ragdoll's weight in kilograms, shared out among its parts by their
 *                     volume; 0 leaves each part weighed by Jolt at water's density
 * @param damping      the share of its spin a part sheds per film tick — 0 windmills, 1 stops dead
 * @param friction     resistance in every joint, in newton-metres. 0 is a wind chime
 * @param gravity      how much of the scene's gravity the parts feel
 * @param selfCollide  whether parts that do not share a joint collide with each other; off is
 *                     cheaper and fine for a body that only ever lies down
 * @param muscles      how hard every joint pulls towards the animated pose while the ragdoll is
 *                     free: 0 is a corpse, 1 a body that all but stands up again. Stunned,
 *                     drunk, staggering — the middle of the range is where those live
 * @param muscleDamping how much the muscles overshoot: 0 springs back and forth, 1 settles once
 */
public record FormRagdoll(boolean enabled, Set<String> excluded, Map<String, RagdollJoint> joints,
    float mass, float damping, float friction, float gravity, boolean selfCollide, float muscles, float muscleDamping)
{
    public static final float DEFAULT_MASS = 0F;
    public static final float DEFAULT_DAMPING = 0.3F;
    public static final float DEFAULT_FRICTION = 3F;
    public static final float DEFAULT_GRAVITY = 1F;
    public static final float DEFAULT_MUSCLES = 0F;
    public static final float DEFAULT_MUSCLE_DAMPING = 0.5F;

    public static final FormRagdoll EMPTY = new FormRagdoll(false, Collections.emptySet(), Collections.emptyMap(),
        DEFAULT_MASS, DEFAULT_DAMPING, DEFAULT_FRICTION, DEFAULT_GRAVITY, true, DEFAULT_MUSCLES, DEFAULT_MUSCLE_DAMPING);

    public FormRagdoll
    {
        excluded = excluded == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
        joints = joints == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(joints));
    }

    /** The older shape of the record: a ragdoll with every whole-body knob at its default. */
    public FormRagdoll(boolean enabled, Set<String> excluded, Map<String, RagdollJoint> joints)
    {
        this(enabled, excluded, joints, DEFAULT_MASS, DEFAULT_DAMPING, DEFAULT_FRICTION, DEFAULT_GRAVITY, true, DEFAULT_MUSCLES, DEFAULT_MUSCLE_DAMPING);
    }

    private FormRagdoll knobs(boolean enabled, Set<String> excluded, Map<String, RagdollJoint> joints)
    {
        return new FormRagdoll(enabled, excluded, joints, this.mass, this.damping, this.friction, this.gravity, this.selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withMass(float mass)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, mass, this.damping, this.friction, this.gravity, this.selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withDamping(float damping)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, damping, this.friction, this.gravity, this.selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withFriction(float friction)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, this.damping, friction, this.gravity, this.selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withGravity(float gravity)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, this.damping, this.friction, gravity, this.selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withSelfCollide(boolean selfCollide)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, this.damping, this.friction, this.gravity, selfCollide, this.muscles, this.muscleDamping);
    }

    public FormRagdoll withMuscles(float muscles)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, this.damping, this.friction, this.gravity, this.selfCollide, muscles, this.muscleDamping);
    }

    public FormRagdoll withMuscleDamping(float muscleDamping)
    {
        return new FormRagdoll(this.enabled, this.excluded, this.joints, this.mass, this.damping, this.friction, this.gravity, this.selfCollide, this.muscles, muscleDamping);
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
        return this.equals(EMPTY);
    }

    public FormRagdoll withEnabled(boolean enabled)
    {
        return this.knobs(enabled, this.excluded, this.joints);
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

        return this.knobs(this.enabled, excluded, this.joints);
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

        return this.knobs(this.enabled, this.excluded, joints);
    }
}
