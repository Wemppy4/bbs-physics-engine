package wemppy.bbs_physics.client.collision;

import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CapsuleShape;
import com.github.stephengold.joltjni.CylinderShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RotatedTranslatedShape;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.readonly.ConstShape;
import wemppy.bbs_physics.BBSPhysics;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** The bridge from measured or authored shapes to the ones Jolt actually simulates. */
public final class JoltShapes
{
    /** Jolt's corner rounding, kept small so a thin shape — a finger, a strap — still fits it. */
    private static final float CONVEX_RADIUS = 0.02F;

    private JoltShapes()
    {}

    /**
     * One Jolt shape for a whole list: the single shape directly, a compound of the rest, or null
     * when there is nothing to build.
     *
     * <p>A compound is static rather than mutable because its contents never change once built —
     * and a body's shape may only be swapped wholesale, never edited in place, or Jolt would
     * refuse to restore the checkpoints taken before the edit.</p>
     */
    public static ConstShape build(List<CollisionShapes.SubShape> shapes)
    {
        if (shapes == null || shapes.isEmpty())
        {
            return null;
        }

        if (shapes.size() == 1)
        {
            CollisionShapes.SubShape sub = shapes.get(0);

            return new RotatedTranslatedShape(vec(sub.offset()), quat(sub.rotation()), leaf(sub));
        }

        StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();

        for (CollisionShapes.SubShape sub : shapes)
        {
            compound.addShape(vec(sub.offset()), quat(sub.rotation()), leaf(sub));
        }

        ShapeResult result = compound.create();

        if (result.hasError())
        {
            BBSPhysics.LOGGER.warn("Could not build a compound collider: {}", result.getError());

            return null;
        }

        return result.get();
    }

    /** A single primitive. Convex radii are clamped: Jolt rejects a shape smaller than its rounding. */
    public static ConstShape leaf(CollisionShapes.SubShape sub)
    {
        Vector3f half = sub.half();

        return switch (sub.kind())
        {
            case BOX -> new BoxShape(vec(half), rounding(Math.min(half.x, Math.min(half.y, half.z))));
            case SPHERE -> new SphereShape(half.x);
            case CAPSULE -> new CapsuleShape(half.y, half.x);
            case CYLINDER -> new CylinderShape(half.y, half.x, rounding(Math.min(half.x, half.y)));
        };
    }

    /**
     * The corner rounding for a shape whose smallest half extent is {@code smallest}.
     *
     * <p>Strictly <em>under</em> that extent, not equal to it: a box rounded by exactly its own half
     * thickness has no flat left, and Jolt rejects it outright. That never mattered while every
     * shape was a volume; a face plate is a quarter of a pixel thick and would land exactly on the
     * boundary.</p>
     */
    private static float rounding(float smallest)
    {
        return Math.max(Math.min(CONVEX_RADIUS, smallest * 0.5F), 1.0e-4F);
    }

    /**
     * The smallest shape a body can be given, for a body that has been marked as physical but has
     * nothing marked up to collide as. Jolt has no such thing as a body without a shape, so it gets
     * a speck — and, being in a layer that meets nothing, it falls through the world, which is
     * exactly what a rigid body without a collider does everywhere else (§5.1).
     */
    public static ConstShape speck()
    {
        return new SphereShape(0.05F);
    }

    private static Vec3 vec(Vector3f v)
    {
        return new Vec3(v.x, v.y, v.z);
    }

    private static Quat quat(Quaternionf q)
    {
        return new Quat(q.x, q.y, q.z, q.w);
    }
}
