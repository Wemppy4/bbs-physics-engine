package wemppy.bbs_physics.client.ragdoll;

import com.github.stephengold.joltjni.FixedConstraintSettings;
import com.github.stephengold.joltjni.HingeConstraintSettings;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TwoBodyConstraintSettings;
import com.github.stephengold.joltjni.Vec3;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.utils.StringUtils;
import wemppy.bbs_physics.client.scene.FilmScene;
import wemppy.bbs_physics.engine.PhysicsMath;
import wemppy.bbs_physics.ragdoll.RagdollJoint;
import wemppy.bbs_physics.ragdoll.RagdollJointKind;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Describing one joint of a ragdoll to Jolt: where it sits, which way it bends, and how far.
 *
 * <p>Set up in <b>world space at the build pose</b> — the bodies are already standing on it, so Jolt
 * converts to each body's local frame correctly on its own. "World space" here means the
 * <em>scene's</em> coordinates, which are the world's minus the scene origin: a point left in raw
 * world coordinates sits hundreds of blocks from the bodies it is meant to join, and the lever arm of
 * that mistake is the whole distance to the origin. The parts scatter as if they were never jointed,
 * which is exactly how that bug presented.</p>
 *
 * <p>Split out of the ragdoll itself because it is the one part of it that is pure description: it
 * reads the pose and the author's settings and hands back a settings object, touching nothing. The
 * viewport preview needs the same answers to draw the joints an author is about to get, and two
 * separate derivations of "which way does this bone run" would be a preview that lies.</p>
 */
public final class RagdollJoints
{
    /**
     * A joint as built: its settings, and — for a cone — the rotation of its constraint space in
     * the world, which is what a muscle needs to name a target in that space. Null for a hinge or
     * a weld, which have no muscle.
     */
    public record Built(TwoBodyConstraintSettings settings, Quaternionf frame)
    {}

    private RagdollJoints()
    {}

    /**
     * The joint a bone hangs from its parent by, or null when it has none — the author asked for a
     * free bone, or the pose has no frame to build one against.
     *
     * @param bone       the falling bone
     * @param bonePath   its path in the matrix cache
     * @param parentBone what it hangs from
     * @param parentPath that bone's path
     * @param formPath   the model form's own path, which bone paths are built from
     * @param scene      the scene, for the origin every point handed to Jolt is measured from
     */
    public static Built build(RagdollJoint joint, String bone, String bonePath, String parentBone, String parentPath,
        String formPath, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, float friction)
    {
        MatrixCacheEntry entry = matrices.get(bonePath);

        if (entry == null || entry.matrix() == null || joint.kind() == RagdollJointKind.FREE)
        {
            return null;
        }

        Matrix4f world = new Matrix4f(actorWorld).mul(entry.matrix());
        Vector3f pivot = world.getTranslation(new Vector3f());

        /* The joint sits at the child bone's pivot: the elbow is where the forearm turns. */
        RVec3 point = new RVec3(
            pivot.x - scene.getOriginX(),
            pivot.y - scene.getOriginY(),
            pivot.z - scene.getOriginZ());

        return switch (joint.kind())
        {
            case FIXED -> new Built(weld(world, parentPath, matrices, actorWorld), null);
            case HINGE -> new Built(hinge(joint, world, point, friction), null);
            default -> cone(joint, bone, parentBone, pivot, point, formPath, groups, matrices, actorWorld, friction);
        };
    }

    /**
     * A weld, holding both bones in the pose they were built in.
     *
     * <p>Both bones' own axes are handed over as they stand, which is what tells Jolt the pose the
     * weld is meant to hold. Left at their defaults — the world's own X and Y for both sides — the
     * pair says "these two bones face the same way as the world", and that is a pose no bone of a
     * character is ever in: a cubic bone's frame is turned half a circle to begin with (§10.1), and
     * the two ends of a weld rarely agree even before that. A constraint between two kinematic
     * bodies does nothing, so the violation is invisible while the animation is in charge and the
     * whole of it comes due on the one tick the parts are released — the weld hauling both bones
     * round to the world's axes with however much force that takes.</p>
     */
    private static TwoBodyConstraintSettings weld(Matrix4f world, String parentPath, MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry parentEntry = matrices.get(parentPath);

        if (parentEntry == null || parentEntry.matrix() == null)
        {
            return null;
        }

        FixedConstraintSettings fixed = new FixedConstraintSettings();

        fixed.setAutoDetectPoint(true);

        Quaternionf child = world.getUnnormalizedRotation(new Quaternionf());
        Quaternionf above = new Matrix4f(actorWorld).mul(parentEntry.matrix()).getUnnormalizedRotation(new Quaternionf());

        /* One is the parent: the joint is created as create(parent.body, part.body). */
        fixed.setAxisX1(axis(above, 1F, 0F, 0F));
        fixed.setAxisY1(axis(above, 0F, 1F, 0F));
        fixed.setAxisX2(axis(child, 1F, 0F, 0F));
        fixed.setAxisY2(axis(child, 0F, 1F, 0F));

        return fixed;
    }

    /**
     * A hinge around one of the bone's own axes, taken from its world frame as it stands — the frame
     * of a cubic bone carries the Ry(π) flip (§10.1), consistently for every bone, so the author
     * picks the axis that looks right in the preview and it stays right.
     */
    private static TwoBodyConstraintSettings hinge(RagdollJoint joint, Matrix4f world, RVec3 point, float friction)
    {
        HingeConstraintSettings hinge = new HingeConstraintSettings();
        Vector3f axis = boneAxis(world, joint.hingeAxis());
        Vector3f normal = PhysicsMath.perpendicular(axis);

        hinge.setPoint1(point);
        hinge.setPoint2(point);
        hinge.setHingeAxis1(new Vec3(axis.x, axis.y, axis.z));
        hinge.setHingeAxis2(new Vec3(axis.x, axis.y, axis.z));
        hinge.setNormalAxis1(new Vec3(normal.x, normal.y, normal.z));
        hinge.setNormalAxis2(new Vec3(normal.x, normal.y, normal.z));
        hinge.setLimitsMin((float) Math.toRadians(joint.hingeMin()));
        hinge.setLimitsMax((float) Math.toRadians(joint.hingeMax()));
        hinge.setMaxFrictionTorque(friction);

        return hinge;
    }

    /**
     * The soft cone every bone gets until it is told otherwise. It leans around the bone's rest
     * direction — see {@link #boneDirection}.
     */
    private static Built cone(RagdollJoint joint, String bone, String parentBone, Vector3f pivot, RVec3 point,
        String formPath, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld, float friction)
    {
        SwingTwistConstraintSettings cone = new SwingTwistConstraintSettings();
        Vector3f axis = boneDirection(bone, parentBone, pivot, formPath, groups, matrices, actorWorld);
        Vector3f plane = PhysicsMath.perpendicular(axis);

        cone.setPosition1(point);
        cone.setPosition2(point);
        cone.setTwistAxis1(new Vec3(axis.x, axis.y, axis.z));
        cone.setTwistAxis2(new Vec3(axis.x, axis.y, axis.z));
        cone.setPlaneAxis1(new Vec3(plane.x, plane.y, plane.z));
        cone.setPlaneAxis2(new Vec3(plane.x, plane.y, plane.z));
        /* Two half-angles: a round cone when they agree, an ellipse — an elbow, a knee — when
         * they do not. */
        cone.setNormalHalfConeAngle((float) Math.toRadians(joint.swing()));
        cone.setPlaneHalfConeAngle((float) Math.toRadians(joint.swingPlane()));

        /* The twist range the author gives is min..max; Jolt wants it symmetric around the rest
         * twist only in sign convention, so it is passed straight through. */
        cone.setTwistMinAngle((float) Math.toRadians(joint.twistMin()));
        cone.setTwistMaxAngle((float) Math.toRadians(joint.twistMax()));
        cone.setMaxFrictionTorque(friction);

        /* The constraint's own frame, as Jolt builds it from world-space axes: X the twist axis, Y
         * the normal (plane × twist), Z the plane axis. A muscle's target is named in this frame
         * — see RagdollRig — and it has to be built exactly the way Jolt builds its own, or the
         * target is a rotation of the right amount about the wrong axis. */
        Vector3f normal = new Vector3f(plane).cross(axis).normalize();
        Quaternionf frame = new Matrix3f(axis, normal, plane).getNormalizedRotation(new Quaternionf());

        return new Built(cone, frame);
    }

    /** One of the bone's local axes (0=X, 1=Y, 2=Z), in world space as the bone stands. */
    private static Vector3f boneAxis(Matrix4f world, int axis)
    {
        Quaternionf rotation = world.getUnnormalizedRotation(new Quaternionf());
        Vector3f result = new Vector3f(axis == 0 ? 1F : 0F, axis == 1 ? 1F : 0F, axis == 2 ? 1F : 0F);

        return rotation.transform(result).normalize();
    }

    /**
     * The direction the bone runs, in world space: its pivot towards its first child's pivot, which
     * is the direction the limb visibly runs — positions only, so the frame flip cancels out of it.
     * A leaf bone continues onward from its parent. Degenerate cases — stacked pivots — fall back to
     * world up, which at least never crashes a build.
     */
    private static Vector3f boneDirection(String bone, String parentBone, Vector3f pivot,
        String formPath, Map<String, ModelGroup> groups, MatrixCache matrices, Matrix4f actorWorld)
    {
        Vector3f from = new Vector3f(pivot);
        ModelGroup group = groups.get(bone);

        if (group != null)
        {
            for (ModelGroup child : group.children)
            {
                Vector3f to = pivotOf(child.id, formPath, matrices, actorWorld);

                if (to != null && to.distanceSquared(from) > 1.0e-6F)
                {
                    return to.sub(from).normalize();
                }
            }
        }

        Vector3f parentPivot = pivotOf(parentBone, formPath, matrices, actorWorld);

        if (parentPivot != null && parentPivot.distanceSquared(from) > 1.0e-6F)
        {
            return from.sub(parentPivot).normalize();
        }

        return new Vector3f(0F, 1F, 0F);
    }

    private static Vector3f pivotOf(String bone, String formPath, MatrixCache matrices, Matrix4f actorWorld)
    {
        MatrixCacheEntry entry = matrices.get(StringUtils.combinePaths(formPath, bone));

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        return new Matrix4f(actorWorld).mul(entry.matrix()).getTranslation(new Vector3f());
    }

    /** One of a frame's own axes, in world space — what Jolt is handed to read a rest pose from. */
    private static Vec3 axis(Quaternionf rotation, float x, float y, float z)
    {
        Vector3f result = rotation.transform(new Vector3f(x, y, z)).normalize();

        return new Vec3(result.x, result.y, result.z);
    }
}
