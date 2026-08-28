package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A bone's rotation as a quaternion, whatever the model happens to be holding.
 *
 * <p>BBS answers this itself, with {@code ModelGroup.evaluatedRotation()}; CML has the bone's
 * {@code orient} — the slot every constraint stage writes its result into — but no reader for it,
 * so the reader is here.</p>
 *
 * <p>The other difference is the fallback. BBS's bone can carry its pose as a quaternion outright
 * ({@code rotationMode}), and CML's cannot: there a pose is always three Euler angles. So the
 * fallback has one branch instead of two, and it is the same one BBS takes for an Euler bone —
 * the channels read as a ZYX rotation in degrees, which is how cubic models store theirs.</p>
 */
public final class PhysicsRotations
{
    private PhysicsRotations()
    {}

    /**
     * The bone's evaluated local rotation at this point in the pipeline: what a stage before this
     * one composed, or else what the renderer would rebuild from the bone's own channels.
     *
     * @return a fresh quaternion, safe to mutate
     */
    public static Quaternionf evaluatedRotation(ModelGroup group)
    {
        if (group.orient != null)
        {
            return new Quaternionf(group.orient);
        }

        return toLocalRotationZYXDegrees(group.current.rotate);
    }

    /** Three Euler angles in degrees, applied Z then Y then X, as one rotation. */
    public static Quaternionf toLocalRotationZYXDegrees(Vector3f rotateDeg)
    {
        float x = (float) Math.toRadians(rotateDeg.x);
        float y = (float) Math.toRadians(rotateDeg.y);
        float z = (float) Math.toRadians(rotateDeg.z);

        return new Quaternionf().rotationZYX(z, y, x);
    }
}
