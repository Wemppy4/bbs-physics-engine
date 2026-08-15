package mchorse.bbs_physics.ragdoll;

/**
 * How a ragdoll bone is allowed to move against its parent.
 *
 * <p>The default for every bone is a soft {@link #CONE}: a joystick that can lean anywhere within
 * a cone and turn a little around its own axis. It is anatomically wrong for a knee and right for
 * almost everything else, which is exactly what a default should be — the ragdoll works with
 * nothing configured, and looks drunk rather than broken until the elbows and knees are told they
 * are hinges.</p>
 */
public enum RagdollJointKind
{
    /** Leans in a cone around the bone's own direction and twists a little. Shoulders, hips, neck. */
    CONE("cone"),

    /** A door hinge: bends around one axis only, between two angles. Knees, elbows. */
    HINGE("hinge"),

    /** Welded to the parent: no movement at all. How chest + belly become one stiff torso. */
    FIXED("fixed"),

    /** No joint: the bone is a body but nothing ties it to the parent. A chain link, a satchel. */
    FREE("free");

    public final String id;

    RagdollJointKind(String id)
    {
        this.id = id;
    }

    public static RagdollJointKind byId(String id, RagdollJointKind fallback)
    {
        for (RagdollJointKind kind : values())
        {
            if (kind.id.equals(id))
            {
                return kind;
            }
        }

        return fallback;
    }
}
