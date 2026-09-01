package wemppy.bbs_physics.chain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A model form's chain modifier: which of its bones are driven as hanging strands — hair, a tail,
 * a belt, a rope of bones — and what those strands are like.
 *
 * <p><b>Bones are listed, not excluded</b> — the opposite of the ragdoll ({@link
 * wemppy.bbs_physics.ragdoll.FormRagdoll}), and for a plain reason: a ragdoll claims a body that
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
    float mass,
    boolean selfCollision,
    float falloff,
    float bend)
{
    public static final float DEFAULT_STIFFNESS = 0.15F;
    public static final float DEFAULT_DAMPING = 0.25F;
    public static final float DEFAULT_GRAVITY = 1F;
    public static final float DEFAULT_MASS = 1F;

    /**
     * How much softer the tip of a strand is than its root: the tip keeps {@code 1 − falloff} of
     * the root's stiffness. The default is BBS's own chain solver's gradient (the tip at 0.4),
     * which is what gives a strand a living, whip-like tail rather than a stiff, lifeless one.
     */
    public static final float DEFAULT_FALLOFF = 0.6F;

    /**
     * How far one bone may lean away from the next, in degrees — the cone every joint of a strand
     * swings in. Wide by default, because a strand's shape is the spring's job; narrow for a braid
     * or a tail that must not fold in half.
     */
    public static final float DEFAULT_BEND = 80F;

    public static final FormChain EMPTY = new FormChain(
        false, Collections.emptySet(),
        DEFAULT_STIFFNESS, DEFAULT_DAMPING, DEFAULT_GRAVITY, DEFAULT_MASS, false,
        DEFAULT_FALLOFF, DEFAULT_BEND);

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
        return new FormChain(enabled, this.bones, this.stiffness, this.damping, this.gravity, this.mass, this.selfCollision, this.falloff, this.bend);
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

        return new FormChain(this.enabled, bones, this.stiffness, this.damping, this.gravity, this.mass, this.selfCollision, this.falloff, this.bend);
    }

    /** The same setup with a whole set of bones claimed — what "take the chains from the model" does. */
    public FormChain withBones(Set<String> bones)
    {
        return new FormChain(this.enabled, bones, this.stiffness, this.damping, this.gravity, this.mass, this.selfCollision, this.falloff, this.bend);
    }

    public FormChain withStiffness(float stiffness)
    {
        return new FormChain(this.enabled, this.bones, stiffness, this.damping, this.gravity, this.mass, this.selfCollision, this.falloff, this.bend);
    }

    public FormChain withDamping(float damping)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, damping, this.gravity, this.mass, this.selfCollision, this.falloff, this.bend);
    }

    public FormChain withGravity(float gravity)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, gravity, this.mass, this.selfCollision, this.falloff, this.bend);
    }

    public FormChain withMass(float mass)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, mass, this.selfCollision, this.falloff, this.bend);
    }

    public FormChain withFalloff(float falloff)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, this.mass, this.selfCollision, falloff, this.bend);
    }

    public FormChain withBend(float bend)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, this.mass, this.selfCollision, this.falloff, bend);
    }

    public FormChain withSelfCollision(boolean selfCollision)
    {
        return new FormChain(this.enabled, this.bones, this.stiffness, this.damping, this.gravity, this.mass, selfCollision, this.falloff, this.bend);
    }
}
