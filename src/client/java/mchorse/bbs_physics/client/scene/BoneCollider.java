package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The collision of a single bone, measured from the geometry the bone is actually drawn from: one
 * shape per cube for cubic models, a capsule along the bone for BOBJ ones. This replaces the
 * bead-per-bone first pass — a sphere cloud has gaps a prop can slip through and no flat surface
 * for anything to rest on, while Minecraft models are boxes to begin with, so the honest shape was
 * always right there in the model.
 *
 * <p><b>The frame dance.</b> The shapes are expressed in the frame of the bone's cached matrix,
 * because that matrix is what the physics body is driven by — and that frame is <em>not</em> the
 * model's. {@code ModelInstance.captureMatrices} builds each bone matrix as {@code group × T(pivot)
 * × Ry(π)}: it steps to the bone's pivot and then turns half a circle around Y. A cube measured in
 * model space therefore lands in the body's frame as {@code Ry(π) × (point − pivot)} — X and Z
 * negate — and a cube's own rotation conjugates the same way. Skipping that flip mirrors every
 * collider through its bone's pivot, which looks almost right on symmetric bones and is plainly
 * wrong on a sword arm.</p>
 *
 * <p>For BOBJ the flip sits <em>outside</em> the bone matrix ({@code Ry(π) × bone.mat}), so
 * bone-local directions — like the offset to a child bone — pass through unchanged.</p>
 *
 * <p>The model-level display scale is baked into the shapes at build time: the world matrix the
 * body follows carries that scale in its translation, but a Jolt shape does not scale with its
 * body, so a 2× model would otherwise collide like a 1× one (the exact scale blindness BBS's own
 * bone physics has). Scale animated mid-film is not followed — colliders are built once.</p>
 */
public record BoneCollider(List<SubShape> shapes)
{
    /** Model units per block — cubic models are authored in sixteenths. */
    private static final float PIXELS = 16F;

    /** Nothing thinner than this, in blocks: below it Jolt's contact resolution struggles. */
    private static final float MIN_HALF = 0.03F;

    /** Capsule radius bounds for BOBJ bones, in blocks — a quarter of the bone, within reason. */
    private static final float MIN_CAPSULE_RADIUS = 0.04F;
    private static final float MAX_CAPSULE_RADIUS = 0.12F;

    private static final float EPS = 1.0e-6F;

    public enum Kind
    {
        BOX,

        /** {@code half.y} is the cylinder's half height, {@code half.x} the radius. */
        CAPSULE,

        /** {@code half.x} is the radius. */
        SPHERE;
    }

    /** One collision shape inside the bone's body, in the body's own frame. */
    public record SubShape(Kind kind, Vector3f half, Vector3f offset, Quaternionf rotation)
    {}

    /**
     * Measures {@code bone}, or returns null when there is nothing to measure — an unknown bone, or
     * one with no geometry behind it (a control or pivot bone, which should not collide anyway).
     */
    public static BoneCollider of(IModel model, String bone, Vector3f scale)
    {
        if (model instanceof Model cubic)
        {
            return ofCubic(cubic, bone, scale);
        }

        if (model instanceof BOBJModel bobj)
        {
            return ofBobj(bobj, bone);
        }

        return null;
    }

    private static BoneCollider ofCubic(Model model, String bone, Vector3f scale)
    {
        ModelGroup group = model.getGroup(bone);

        if (group == null || group.cubes.isEmpty())
        {
            return null;
        }

        List<SubShape> shapes = new ArrayList<>(group.cubes.size());
        Vector3f pivot = group.initial.translate;

        for (ModelCube cube : group.cubes)
        {
            shapes.add(ofCube(cube, pivot, scale));
        }

        return new BoneCollider(shapes);
    }

    /**
     * One cube as an oriented box. Everything is measured in model space (blocks), rotated by the
     * cube's own rotation about the cube's pivot — replicating {@code CubicCubeRenderer.rotate}'s
     * Z·Y·X order exactly — and only then carried into the bone-matrix frame by the Ry(π) flip.
     */
    private static SubShape ofCube(ModelCube cube, Vector3f bonePivot, Vector3f scale)
    {
        /* Inflate grows the drawn cube past its size, so it grows the collision too. Corners are
         * min/maxed per component in case of negative sizes. */
        float grow = cube.inflate;

        float ax = (cube.origin.x - grow) / PIXELS;
        float ay = (cube.origin.y - grow) / PIXELS;
        float az = (cube.origin.z - grow) / PIXELS;
        float bx = (cube.origin.x + cube.size.x + grow) / PIXELS;
        float by = (cube.origin.y + cube.size.y + grow) / PIXELS;
        float bz = (cube.origin.z + cube.size.z + grow) / PIXELS;

        Vector3f half = new Vector3f(
            Math.max(Math.abs(bx - ax) * 0.5F * scale.x, MIN_HALF),
            Math.max(Math.abs(by - ay) * 0.5F * scale.y, MIN_HALF),
            Math.max(Math.abs(bz - az) * 0.5F * scale.z, MIN_HALF));

        Vector3f center = new Vector3f((ax + bx) * 0.5F, (ay + by) * 0.5F, (az + bz) * 0.5F);

        /* The cube's own rotation, about its own pivot: center' = pivot + R × (center − pivot). */
        Quaternionf rotation = new Quaternionf();

        if (cube.rotate.x != 0F || cube.rotate.y != 0F || cube.rotate.z != 0F)
        {
            rotation
                .rotateZ(MathUtils.toRad(cube.rotate.z))
                .rotateY(MathUtils.toRad(cube.rotate.y))
                .rotateX(MathUtils.toRad(cube.rotate.x));

            Vector3f cubePivot = new Vector3f(cube.pivot).div(PIXELS);

            center.sub(cubePivot);
            rotation.transform(center);
            center.add(cubePivot);
        }

        /* Into the bone-matrix frame: relative to the bone pivot, model display scale baked in,
         * then the Ry(π) flip — X and Z negate, and the cube rotation conjugates (F × R × F, with
         * F its own inverse as a half turn). */
        center.sub(bonePivot.x / PIXELS, bonePivot.y / PIXELS, bonePivot.z / PIXELS);
        center.mul(scale);

        Vector3f offset = new Vector3f(-center.x, center.y, -center.z);

        flipY180(rotation);

        return new SubShape(Kind.BOX, half, offset, rotation);
    }

    /** Conjugates a rotation by the half turn about Y, in place: R → F·R·F. */
    private static void flipY180(Quaternionf rotation)
    {
        Quaternionf flip = new Quaternionf().rotateY(MathUtils.PI);

        flip.mul(rotation, rotation);
        rotation.mul(flip);
    }

    /**
     * A BOBJ bone as a capsule reaching toward its first child — the bone's rest geometry, same
     * source {@code PhysicsRig} measures rest lengths from. A bone with no offset child keeps a
     * small sphere: better a bead than a guess about geometry that is not there.
     *
     * <p>No flip here: the capsule is expressed in the bone's own local frame, and for BOBJ the
     * capture-time flip multiplies from the <em>outside</em>.</p>
     */
    private static BoneCollider ofBobj(BOBJModel model, String bone)
    {
        BOBJBone bobjBone = model.getArmature().bones.get(bone);

        if (bobjBone == null)
        {
            return null;
        }

        Vector3f direction = null;

        for (BOBJBone candidate : model.getArmature().orderedBones)
        {
            if (candidate != null && candidate.parentBone == bobjBone)
            {
                Vector3f offset = candidate.relBoneMat.getTranslation(new Vector3f());

                if (offset.lengthSquared() > EPS * EPS)
                {
                    direction = offset;

                    break;
                }
            }
        }

        List<SubShape> shapes = new ArrayList<>(1);

        if (direction == null)
        {
            shapes.add(new SubShape(Kind.SPHERE, new Vector3f(0.08F, 0.08F, 0.08F), new Vector3f(), new Quaternionf()));

            return new BoneCollider(shapes);
        }

        float length = direction.length();
        float radius = Math.min(Math.max(length * 0.25F, MIN_CAPSULE_RADIUS), MAX_CAPSULE_RADIUS);
        float halfHeight = Math.max(length * 0.5F - radius, 0.005F);

        Vector3f offset = new Vector3f(direction).mul(0.5F);
        Quaternionf rotation = new Quaternionf().rotationTo(0F, 1F, 0F, direction.x / length, direction.y / length, direction.z / length);

        shapes.add(new SubShape(Kind.CAPSULE, new Vector3f(radius, halfHeight, radius), offset, rotation));

        return new BoneCollider(shapes);
    }
}
