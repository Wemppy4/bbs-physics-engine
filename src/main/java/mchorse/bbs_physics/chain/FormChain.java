package mchorse.bbs_physics.chain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A model form's chain modifier: which of its bones are driven as hanging strands — hair, a tail,
 * a belt, a rope of bones — and what those strands are like.
 *
 * <p><b>Bones are listed, not excluded</b> — the opposite of the ragdoll ({@link
 * mchorse.bbs_physics.ragdoll.FormRagdoll}), and for a plain reason: a ragdoll claims a body that
 * was marked up to collide anyway, so its default is "all of it", while a chain claims bones
 * <em>nobody</em> marked up and there is no sensible default for which strand of hair should swing.
 * An empty list is a modifier that does nothing yet, which is exactly what it should do before the
 * author has said what the hair is.</p>
 *
 * <p><b>The strands are worked out from the bone tree</b>, not stored: a listed bone whose parent is
 * not listed starts a strand, and it runs down through listed children. So ticking bones on and off
 * cannot produce a broken description, and a strand that branches (two braids off one root) is two
 * strands sharing an anchor — which is what it looks like on the model too.</p>
 *
 * <p>The one animation handle (§4) covers this modifier like every other: at 1 the bones stand on
 * their keyframes, at 0 they hang and swing. What decides how much a strand keeps its hairstyle is
 * {@link #stiffness}, the spring in every joint — the same knob the chain form has, and the same
 * meaning BBS's own chain physics gives the word.</p>
 */
public record FormChain(
    boolean enabled,
    Set<String> bones,
    float stiffness,
    float damping,
    float gravity,
    float radius,
    float mass,
    boolean selfCollision)
{
    public static final float DEFAULT_STIFFNESS = 0.15F;
    public static final float DEFAULT_DAMPING = 0.25F;
    public static final float DEFAULT_GRAVITY = 1F;
    public static final float DEFAULT_RADIUS = 0.06F;
    public static final float DEFAULT_MASS = 1F;

    public static final FormChain EMPTY = new FormChain(
        false, Collections.emptySet(),
        DEFAULT_STIFFNESS, DEFAULT_DAMPING, DEFAULT_GRAVITY, DEFAULT_RADIUS, DEFAULT_MASS, false);

    public FormChain
    {
        bones = bones == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(bones));
    }

    /** A freshly added modifier: on, with nothing claimed until the author says what the hair is. */
    public static FormChain added()
    {
        return EMPTY.withEnabled(true);
    }

    /** Whether this modifier drives {@code bone}. */
    public boolean claims(String bone)
    {
        return this.enabled && this.bones.contains(bone);
    }

    /** Whether there is anything to store at all. */
    public boolean isEmpty()
    {
        return !this.enabled && this.bones.isEmpty();
    }

    public FormChain withEnabled(boolean enabled)
    {
        return new FormChain(enabled, this.bones, this.stiffness, this.damping, this.gravity, this.radius, this.mass, this.selfCollision);
    }

    /** The same setup with one bone taken into the chain, or left out of it. */
    public FormChain withBone(String bone, boolean part)
    {
        Set<String> bones = new LinkedHashSet<>(this.bones);

        if (part)
        {
            bones.add(bone);
        }
        else
        {
            bones.remove(bone);
        }

        return new FormChain(this.enabled, bones, this.stiffness, this.damping, this.gravity, this.radius, this.mass, this.selfCollision);
    }

    /** The same setup with a whole set of bones claimed — what "take the chains from the model" does. */
    public FormChain withBones(Set<String> bones)
    {
        return new FormChain(this.enabled, bones, this.stiffness, this.damping, this.gravity, this.radius, this.mass, this.selfCollision);
    }

    public FormChain withStiffness(float stiffness)
    {
        return new FormChain(this.enabled, this.bones, stiffness, this.damping, this.gravity, this.radius, this.mass, this.selfCollision);
    }

    public FormChain withDamping(float damping)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, damping, this.gravity, this.radius, this.mass, this.selfCollision);
    }

    public FormChain withGravity(float gravity)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, gravity, this.radius, this.mass, this.selfCollision);
    }

    public FormChain withRadius(float radius)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, radius, this.mass, this.selfCollision);
    }

    public FormChain withMass(float mass)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, this.radius, mass, this.selfCollision);
    }

    public FormChain withSelfCollision(boolean selfCollision)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, this.radius, this.mass, selfCollision);
    }
}
