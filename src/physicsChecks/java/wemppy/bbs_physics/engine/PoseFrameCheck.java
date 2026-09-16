package wemppy.bbs_physics.engine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.ragdoll.RagdollState;

/** Regression: a fallen pose must not move when its animated reference changes. */
public final class PoseFrameCheck
{
    public static void main(String[] args)
    {
        release();
        check(false);
        check(true);
        System.out.println("PoseFrameCheck passed: body/ragdoll, moving and resting poses, detach, rotation, scale, scrub.");
    }

    private static void release()
    {
        var held = new mchorse.bbs_mod.forms.forms.utils.Anchor();
        held.replay = 1;
        held.attachment = "arm";
        var free = new mchorse.bbs_mod.forms.forms.utils.Anchor();
        free.previous = held;
        free.x = 0.4F;
        var target = wemppy.bbs_physics.forms.PhysicsAnchor.release(free, 0.6F);
        if (!target.hasSameTarget(held) || target.previous != null || free.previous != held || free.x != 0.4F)
            throw new AssertionError("Release changed author data or lost the held target");
        if (wemppy.bbs_physics.forms.PhysicsAnchor.release(free, 0F) != free
            || wemppy.bbs_physics.forms.PhysicsAnchor.release(free, 1F) != free)
            throw new AssertionError("Full ownership must keep authored anchoring");
        var other = new mchorse.bbs_mod.forms.forms.utils.Anchor();
        other.replay = 2;
        other.previous = held;
        if (wemppy.bbs_physics.forms.PhysicsAnchor.release(other, 0.6F) != other)
            throw new AssertionError("Transfer between two targets changed");
    }

    private static void check(boolean falling)
    {
        Matrix4f before = new Matrix4f().translation(19.3F, 4.2F, -8.7F).rotateY(1.1F).scale(1.7F);
        Matrix4f after = new Matrix4f().translation(-12.4F, 1.3F, 9.8F).rotateY(-0.8F).scale(0.8F);
        Vector3f worldA = new Vector3f(2.1F, 0.7F, -3.4F);
        Vector3f worldB = new Vector3f(worldA).add(falling ? 0.3F : 0F, falling ? -0.2F : 0F, 0F);
        Quaternionf spinA = new Quaternionf().rotateXYZ(0.3F, -0.7F, 0.9F);
        Quaternionf spinB = new Quaternionf(spinA).rotateX(falling ? 0.2F : 0F);
        Vector3f localA = before.invert(new Matrix4f()).transformPosition(worldA, new Vector3f());
        Vector3f localB = after.invert(new Matrix4f()).transformPosition(worldB, new Vector3f());
        Quaternionf rotA = before.getUnnormalizedRotation(new Quaternionf()).conjugate().mul(spinA);
        Quaternionf rotB = after.getUnnormalizedRotation(new Quaternionf()).conjugate().mul(spinB);
        PhysicsBodyState body = new PhysicsBodyState();
        RagdollState ragdoll = new RagdollState();
        body.frame.set(before, true);
        body.set(localA, rotA, 0F, true);
        ragdoll.frame.set(before, true);
        ragdoll.set("hip", localA, rotA, 0F, true);
        body.frame.set(after, false);
        body.set(localB, rotB, 0F, false);
        ragdoll.frame.set(after, false);
        ragdoll.set("hip", localB, rotB, 0F, false);
        for (int i = 0; i <= 100; i++)
        {
            float t = i / 100F;
            // Deliberately nonlinear: the animation's interpolation need not match the physics cache.
            Matrix4f render = new Matrix4f().translation(19.3F - 31.7F * t * t, 4.2F - 2.9F * t, -8.7F + 18.5F * t)
                .rotateY(1.1F - 1.9F * t).scale(1.7F - 0.9F * t);
            Vector3f expected = worldA.lerp(worldB, t, new Vector3f());
            Quaternionf expectedRotation = spinA.slerp(spinB, t, new Quaternionf());
            body.frame.render(render);
            ragdoll.frame.render(render);
            verify(render, body.getPosition(t, new Vector3f()), body.getRotation(t, new Quaternionf()), expected, expectedRotation);
            Vector3f p = new Vector3f();
            Quaternionf q = new Quaternionf();
            if (!ragdoll.get("hip", t, p, q)) throw new AssertionError("Missing hip");
            verify(render, p, q, expected, expectedRotation);
        }
        body.frame.set(before, true);
        body.set(localA, rotA, 0F, true);
        verify(before, body.getPosition(0.37F, new Vector3f()), body.getRotation(0.37F, new Quaternionf()), worldA, spinA);
        Vector3f oldMiddle = localA.lerp(localB, 0.5F, new Vector3f());
        new Matrix4f(before).lerp(after, 0.5F).transformPosition(oldMiddle);
        if (oldMiddle.distance(worldA.lerp(worldB, 0.5F, new Vector3f())) < 1F)
            throw new AssertionError("Fixture did not reproduce the old drift");
    }

    private static void verify(Matrix4f frame, Vector3f local, Quaternionf rotation, Vector3f expected, Quaternionf expectedRotation)
    {
        frame.transformPosition(local);
        frame.getUnnormalizedRotation(new Quaternionf()).mul(rotation, rotation).normalize();
        if (local.distance(expected) > 0.0001F || Math.abs(rotation.dot(expectedRotation)) < 0.99999F)
            throw new AssertionError("World pose drift: " + local + " expected " + expected);
    }
}
