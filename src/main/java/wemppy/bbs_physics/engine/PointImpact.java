package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import org.joml.Vector3f;

/** Convert an authored velocity change into a mass-scaled Jolt impulse at a contact point. */
public final class PointImpact
{
    public static void apply(BodyInterface bodies, Body body, RVec3 point, Vector3f velocity)
    {
        if (body.getMotionType() != EMotionType.Dynamic || !velocity.isFinite()
            || !Double.isFinite(point.xx()) || !Double.isFinite(point.yy()) || !Double.isFinite(point.zz())) return;
        float inverseMass = body.getMotionProperties().getInverseMass();
        if (!(inverseMass > 0F)) return;
        Vec3 impulse = new Vec3(velocity.x / inverseMass, velocity.y / inverseMass, velocity.z / inverseMass);
        bodies.addImpulse(body.getId(), impulse, point);
        bodies.activateBody(body.getId());
    }

    private PointImpact() {}
}
