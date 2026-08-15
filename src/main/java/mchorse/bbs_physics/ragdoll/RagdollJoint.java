package mchorse.bbs_physics.ragdoll;

/**
 * One bone's joint in the ragdoll: what it is and how far it bends. All angles are degrees, the
 * unit every other angle in BBS's editors speaks.
 *
 * <p>The limits are the whole point. A model already knows <em>where</em> its joints are — every
 * bone's pivot is one — but nothing in it says a knee only bends forward. Without limits a fallen
 * character is a sack: knees fold backwards, the head turns all the way round. These few numbers
 * are what make it fall like a person.</p>
 *
 * @param kind      what kind of joint this is
 * @param swing     the cone's half-angle: how far the bone may lean away from its rest direction
 * @param twistMin  how far it may twist around its own axis, one way...
 * @param twistMax  ...and the other
 * @param hingeAxis which local axis a hinge bends around: 0 = X, 1 = Y, 2 = Z
 * @param hingeMin  the hinge's reach, one way...
 * @param hingeMax  ...and the other
 * @param attachTo  the bone this one is jointed to, or empty for automatic — the marked ancestor,
 *                  and failing that the nearest marked body by geometry. The escape hatch for rigs
 *                  whose skeleton runs through container bones: Minecraft's own player has the
 *                  arms and the torso as <em>siblings</em>, so no ancestor walk can ever join them
 */
public record RagdollJoint(RagdollJointKind kind, float swing, float twistMin, float twistMax, int hingeAxis, float hingeMin, float hingeMax, String attachTo)
{
    /**
     * The joint every bone gets until the author says otherwise: a soft cone. Wide enough that the
     * ragdoll moves freely, narrow enough that limbs do not wrap around themselves.
     */
    public static final RagdollJoint DEFAULT = new RagdollJoint(RagdollJointKind.CONE, 45F, -30F, 30F, 0, 0F, 120F, "");

    public RagdollJoint
    {
        kind = kind == null ? RagdollJointKind.CONE : kind;
        swing = clamp(swing, 0F, 180F);
        twistMin = clamp(twistMin, -180F, 180F);
        twistMax = clamp(twistMax, twistMin, 180F);
        hingeAxis = Math.floorMod(hingeAxis, 3);
        hingeMin = clamp(hingeMin, -180F, 180F);
        hingeMax = clamp(hingeMax, hingeMin, 180F);
        attachTo = attachTo == null ? "" : attachTo;
    }

    public RagdollJoint withKind(RagdollJointKind kind)
    {
        return new RagdollJoint(kind, this.swing, this.twistMin, this.twistMax, this.hingeAxis, this.hingeMin, this.hingeMax, this.attachTo);
    }

    public RagdollJoint withSwing(float swing)
    {
        return new RagdollJoint(this.kind, swing, this.twistMin, this.twistMax, this.hingeAxis, this.hingeMin, this.hingeMax, this.attachTo);
    }

    public RagdollJoint withTwist(float min, float max)
    {
        return new RagdollJoint(this.kind, this.swing, min, max, this.hingeAxis, this.hingeMin, this.hingeMax, this.attachTo);
    }

    public RagdollJoint withHingeAxis(int axis)
    {
        return new RagdollJoint(this.kind, this.swing, this.twistMin, this.twistMax, axis, this.hingeMin, this.hingeMax, this.attachTo);
    }

    public RagdollJoint withHinge(float min, float max)
    {
        return new RagdollJoint(this.kind, this.swing, this.twistMin, this.twistMax, this.hingeAxis, min, max, this.attachTo);
    }

    public RagdollJoint withAttachTo(String attachTo)
    {
        return new RagdollJoint(this.kind, this.swing, this.twistMin, this.twistMax, this.hingeAxis, this.hingeMin, this.hingeMax, attachTo);
    }

    /** Whether this is exactly the default — and therefore not worth a line in the file. */
    public boolean isDefault()
    {
        return this.equals(DEFAULT);
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }
}
