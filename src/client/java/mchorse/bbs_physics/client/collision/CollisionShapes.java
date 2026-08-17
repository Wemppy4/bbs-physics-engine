package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_physics.collision.CollisionFace;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionShape;
import mchorse.bbs_physics.collision.CollisionSlot;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns collision markup into the shapes the engine is handed.
 *
 * <p>Two things happen here and they are worth keeping apart. <b>Measuring</b> is the automatic
 * mode: a bone is read off the geometry it is drawn from — one box per cube for cubic models, a
 * capsule along the bone for BOBJ ones. <b>Placing</b> is the manual mode: the author's primitives
 * are carried from the frame they were typed in into the frame the body actually uses.</p>
 *
 * <p><b>The frame dance</b> (§10.1 of the concept, established by scouting and not to be
 * rediscovered). {@code ModelInstance.captureMatrices} builds a cubic bone's matrix as
 * {@code group × T(pivot) × Ry(π)} — the bone's frame is turned half a circle around Y from model
 * space. So a point measured in model space lands in the body's frame as {@code Ry(π) × (p −
 * pivot)}: X and Z negate, and a rotation conjugates as {@code F·R·F}. Skipping that mirrors every
 * shape through its bone's pivot, which looks nearly right on a symmetric bone and plainly wrong
 * on a sword arm. For BOBJ the flip is applied from the <em>outside</em> ({@code Ry(π) × bone.mat}),
 * so bone-local directions pass through untouched.</p>
 *
 * <p>Authoring happens in model space, not in the bone's flipped frame, deliberately: what an
 * author types has to match what they see in the model editor, so the flip is applied here rather
 * than being carried in everybody's head.</p>
 *
 * <p>The model's display scale is baked into the shapes at build time. The matrix a body follows
 * carries that scale in its translation, but a Jolt shape does not scale with its body, so a 2×
 * model would otherwise collide like a 1× one — the exact scale blindness BBS's own bone physics
 * has. Scale animated mid-film is not followed: the shapes are built once.</p>
 */
public final class CollisionShapes
{
    /** Model units per block — cubic models are authored in sixteenths. */
    public static final float PIXELS = 16F;

    /** Nothing thinner than this, in blocks: below it Jolt's contact resolution struggles. */
    private static final float MIN_HALF = 0.03F;

    /** Capsule radius bounds for a measured BOBJ bone, in blocks — a quarter of it, within reason. */
    private static final float MIN_CAPSULE_RADIUS = 0.04F;
    private static final float MAX_CAPSULE_RADIUS = 0.12F;

    /**
     * How thick a face plate is, in blocks — a quarter of a model pixel.
     *
     * <p>A pixel was the first answer and an author's eye rejected it on sight: at that thickness
     * the overlay draws a visibly narrow <em>box</em>, and the mode is called a face. A quarter of
     * a pixel reads as a surface and still behaves as a solid, because what a body may not do is
     * step over the plate between two ticks — and the bodies that could are swept ({@code
     * LinearCast}), which tests the whole path rather than the endpoints.</p>
     */
    private static final float PLATE_THICKNESS = 0.015625F;

    /**
     * The floor under a plate's half thickness, in blocks. Deliberately far below {@link #MIN_HALF},
     * which exists to keep a <em>volume</em> solvable — a plate is not trying to be one.
     */
    private static final float PLATE_MIN_HALF = 0.004F;

    private static final float EPS = 1.0e-6F;

    /**
     * One collision shape inside a body, in that body's own frame.
     *
     * @param half half extents for a box; {@code half.x} is the radius of a sphere, a capsule or a
     *             cylinder, and {@code half.y} the half height of the straight part of a capsule
     *             or the half height of a cylinder
     */
    public record SubShape(CollisionKind kind, Vector3f half, Vector3f offset, Quaternionf rotation)
    {}

    private CollisionShapes()
    {}

    /**
     * The shapes of one of a model's bones, in that bone's frame — measured, placed by hand, or
     * nothing at all when the bone is not marked up.
     */
    public static List<SubShape> ofBone(IModel model, String bone, CollisionSlot slot, Vector3f scale)
    {
        if (slot == null || slot.mode() == CollisionMode.NONE)
        {
            return Collections.emptyList();
        }

        if (slot.mode() == CollisionMode.AUTO)
        {
            return measure(model, bone, scale);
        }

        if (slot.mode() == CollisionMode.FACE)
        {
            return face(model, bone, scale, slot.face());
        }

        /* Cubic bones sit half a turn away from model space; BOBJ ones do not — see the class note. */
        return place(slot, model instanceof Model, scale);
    }

    /** The shapes of a form's own slot, in the form's frame. Never measured — a form has no cubes. */
    public static List<SubShape> ofSelf(CollisionSlot slot, Vector3f scale)
    {
        if (slot == null || slot.mode() != CollisionMode.SHAPES)
        {
            return Collections.emptyList();
        }

        return place(slot, false, scale);
    }

    /* Measuring */

    /**
     * A bone read off its own geometry. Null geometry — a control or pivot bone with nothing drawn
     * from it — measures to nothing, which is the honest answer: it exists to steer other bones,
     * not to be hit.
     */
    public static List<SubShape> measure(IModel model, String bone, Vector3f scale)
    {
        if (model instanceof Model cubic)
        {
            return measureCubic(cubic, bone, scale);
        }

        if (model instanceof BOBJModel bobj)
        {
            return measureBobj(bobj, bone, scale);
        }

        return Collections.emptyList();
    }

    /**
     * A bone as plates on one chosen side of its cubes — the {@link CollisionMode#FACE} mode.
     *
     * <p>Which side is the author's answer, not a guess: a second layer is drawn inside the cube it
     * sits on, and only the author knows which of its sides faces the world. The plate is laid on
     * that side of every cube the bone draws, so a strand made of three cubes gets three plates and
     * keeps its outline.</p>
     *
     * <p>Only cubic models have sides to speak of, so a BOBJ bone falls back to the plain
     * measurement rather than pretending.</p>
     */
    public static List<SubShape> face(IModel model, String bone, Vector3f scale, CollisionFace face)
    {
        if (!(model instanceof Model cubic))
        {
            return measure(model, bone, scale);
        }

        ModelGroup group = cubic.getGroup(bone);

        if (group == null || group.cubes.isEmpty())
        {
            return Collections.emptyList();
        }

        List<SubShape> shapes = new ArrayList<>(group.cubes.size());
        Vector3f pivot = group.initial.translate;

        for (ModelCube cube : group.cubes)
        {
            shapes.add(plateOfCube(cube, pivot, scale, face));
        }

        return shapes;
    }

    /**
     * One cube as a plate lying on one of its sides.
     *
     * <p>The plate keeps the cube's other two dimensions — a face is the size of the face — and is
     * given a thickness that is only as much as the solver needs to see it as a solid. It straddles
     * the surface rather than sitting inside or outside it: half its thickness each way, so what is
     * collided against is the side itself, wherever the author put the cube.</p>
     *
     * <p>The cube's own rotation is applied about the cube's own pivot, exactly as the plain
     * measurement does it, so a tilted layer gets a tilted plate — and the frame's half turn
     * (§10.1) is carried at the end for the same reason.</p>
     */
    private static SubShape plateOfCube(ModelCube cube, Vector3f bonePivot, Vector3f scale, CollisionFace face)
    {
        float grow = cube.inflate;

        float ax = (cube.origin.x - grow) / PIXELS;
        float ay = (cube.origin.y - grow) / PIXELS;
        float az = (cube.origin.z - grow) / PIXELS;
        float bx = (cube.origin.x + cube.size.x + grow) / PIXELS;
        float by = (cube.origin.y + cube.size.y + grow) / PIXELS;
        float bz = (cube.origin.z + cube.size.z + grow) / PIXELS;

        float[] min = {Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz)};
        float[] max = {Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)};
        float[] size = {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
        float[] center = {(min[0] + max[0]) * 0.5F, (min[1] + max[1]) * 0.5F, (min[2] + max[2]) * 0.5F};

        int axis = face.axis;

        /* On the chosen side, straddling it. */
        center[axis] = face.sign > 0F ? max[axis] : min[axis];
        size[axis] = PLATE_THICKNESS;

        /* The plate's own axis escapes the usual floor: MIN_HALF keeps a volume solvable, and
         * applying it here would inflate the plate back into the narrow box this mode exists to
         * stop being. The other two axes keep it — a face is as wide as the face. */
        float[] scales = {scale.x, scale.y, scale.z};
        Vector3f half = new Vector3f();

        for (int i = 0; i < 3; i++)
        {
            float value = size[i] * 0.5F * scales[i];

            half.setComponent(i, Math.max(value, i == axis ? PLATE_MIN_HALF : MIN_HALF));
        }

        Vector3f offset = new Vector3f(center[0], center[1], center[2]);
        Quaternionf rotation = new Quaternionf();

        if (cube.rotate.x != 0F || cube.rotate.y != 0F || cube.rotate.z != 0F)
        {
            rotation
                .rotateZ(MathUtils.toRad(cube.rotate.z))
                .rotateY(MathUtils.toRad(cube.rotate.y))
                .rotateX(MathUtils.toRad(cube.rotate.x));

            Vector3f cubePivot = new Vector3f(cube.pivot).div(PIXELS);

            offset.sub(cubePivot);
            rotation.transform(offset);
            offset.add(cubePivot);
        }

        offset.sub(bonePivot.x / PIXELS, bonePivot.y / PIXELS, bonePivot.z / PIXELS);
        offset.mul(scale);

        flipY180(offset, rotation);

        return new SubShape(CollisionKind.BOX, half, offset, rotation);
    }

    private static List<SubShape> measureCubic(Model model, String bone, Vector3f scale)
    {
        ModelGroup group = model.getGroup(bone);

        if (group == null || group.cubes.isEmpty())
        {
            return Collections.emptyList();
        }

        List<SubShape> shapes = new ArrayList<>(group.cubes.size());
        Vector3f pivot = group.initial.translate;

        for (ModelCube cube : group.cubes)
        {
            shapes.add(ofCube(cube, pivot, scale));
        }

        return shapes;
    }

    /**
     * One cube as an oriented box. Everything is measured in model space (blocks), rotated by the
     * cube's own rotation about the cube's own pivot — replicating {@code CubicCubeRenderer.rotate}'s
     * Z·Y·X order exactly — and only then carried into the bone frame by the half turn.
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

        center.sub(bonePivot.x / PIXELS, bonePivot.y / PIXELS, bonePivot.z / PIXELS);
        center.mul(scale);

        flipY180(center, rotation);

        return new SubShape(CollisionKind.BOX, half, center, rotation);
    }

    /**
     * A BOBJ bone as a capsule reaching toward its first child — the bone's rest geometry, the same
     * source BBS's own chain solver measures rest lengths from. A bone with no offset child keeps a
     * small sphere: better a bead than a guess about geometry that is not there.
     */
    private static List<SubShape> measureBobj(BOBJModel model, String bone, Vector3f scale)
    {
        BOBJBone bobjBone = model.getArmature().bones.get(bone);

        if (bobjBone == null)
        {
            return Collections.emptyList();
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

        /* One number for a round shape, and the model's scale need not be uniform: the widest of
         * the three keeps the capsule from ending up thinner than what is drawn. */
        float uniform = Math.max(scale.x, Math.max(scale.y, scale.z));

        if (direction == null)
        {
            float radius = 0.08F * uniform;

            shapes.add(new SubShape(CollisionKind.SPHERE, new Vector3f(radius, radius, radius), new Vector3f(), new Quaternionf()));

            return shapes;
        }

        float raw = direction.length();
        float length = raw * uniform;
        float radius = Math.min(Math.max(length * 0.25F, MIN_CAPSULE_RADIUS), MAX_CAPSULE_RADIUS);
        float halfHeight = Math.max(length * 0.5F - radius, 0.005F);

        Vector3f offset = new Vector3f(direction).mul(scale).mul(0.5F);
        Quaternionf rotation = new Quaternionf().rotationTo(0F, 1F, 0F, direction.x / raw, direction.y / raw, direction.z / raw);

        shapes.add(new SubShape(CollisionKind.CAPSULE, new Vector3f(radius, halfHeight, radius), offset, rotation));

        return shapes;
    }

    /* Placing */

    /** The author's primitives, scaled and — for a cubic bone — carried across the half turn. */
    private static List<SubShape> place(CollisionSlot slot, boolean flip, Vector3f scale)
    {
        List<SubShape> shapes = new ArrayList<>(slot.shapes().size());

        for (CollisionShape shape : slot.shapes())
        {
            shapes.add(place(shape, flip, scale));
        }

        return shapes;
    }

    /** One authored primitive as a shape in the slot's frame. Public — the preview draws these too. */
    public static SubShape place(CollisionShape shape, boolean flip, Vector3f scale)
    {
        Vector3f size = shape.size();

        /* A round shape has one radius and one height, so a non-uniform scale has to pick: the
         * widest of the two lateral axes, so the shape never ends up narrower than what is drawn. */
        float lateral = Math.max(scale.x, scale.z);

        Vector3f half = switch (shape.kind())
        {
            case BOX -> new Vector3f(
                Math.max(size.x * 0.5F * scale.x, MIN_HALF),
                Math.max(size.y * 0.5F * scale.y, MIN_HALF),
                Math.max(size.z * 0.5F * scale.z, MIN_HALF));
            case SPHERE ->
            {
                float radius = Math.max(size.x * 0.5F * Math.max(lateral, scale.y), MIN_HALF);

                yield new Vector3f(radius, radius, radius);
            }
            case CAPSULE ->
            {
                float radius = Math.max(size.x * 0.5F * lateral, MIN_HALF);

                /* The authored height is the whole capsule, caps included — that is the number
                 * measured off a limb. Jolt wants the straight part, so the caps come off. */
                yield new Vector3f(radius, Math.max(size.y * 0.5F * scale.y - radius, MIN_HALF), radius);
            }
            case CYLINDER ->
            {
                float radius = Math.max(size.x * 0.5F * lateral, MIN_HALF);

                yield new Vector3f(radius, Math.max(size.y * 0.5F * scale.y, MIN_HALF), radius);
            }
        };

        Vector3f offset = new Vector3f(shape.offset()).mul(scale);
        Vector3f euler = shape.rotation();
        Quaternionf rotation = new Quaternionf()
            .rotateZ(MathUtils.toRad(euler.z))
            .rotateY(MathUtils.toRad(euler.y))
            .rotateX(MathUtils.toRad(euler.x));

        if (flip)
        {
            flipY180(offset, rotation);
        }

        return new SubShape(shape.kind(), half, offset, rotation);
    }

    /* Frames */

    /**
     * Carries a shape from the frame it was built in into another one — how a body gathers the
     * shapes of the forms and bones nested under it.
     *
     * <p>{@code relative} may carry scale (a scaled body part, a form scaled by its transform), and
     * a Jolt shape does not scale with the body it is in, so the scale is baked into the shape
     * instead. A non-uniform scale on a rotated shape cannot be expressed as a box at all; it is
     * applied per axis anyway, which is exact for the common cases and an approximation for the
     * awkward one.</p>
     */
    public static SubShape carry(SubShape shape, Matrix4f relative)
    {
        Vector3f translation = relative.getTranslation(new Vector3f());
        Quaternionf rotation = relative.getUnnormalizedRotation(new Quaternionf()).normalize();
        Vector3f scale = relative.getScale(new Vector3f());

        Vector3f offset = new Vector3f(shape.offset()).mul(scale);

        rotation.transform(offset).add(translation);

        Vector3f half = new Vector3f(shape.half());

        if (shape.kind() == CollisionKind.BOX)
        {
            half.mul(scale);
        }
        else
        {
            float lateral = Math.max(scale.x, scale.z);

            half.set(half.x * lateral, half.y * scale.y, half.z * lateral);
        }

        return new SubShape(shape.kind(), half, offset, rotation.mul(shape.rotation(), new Quaternionf()));
    }

    /**
     * Conjugates a frame by the half turn about Y, in place: {@code p → F·p}, {@code R → F·R·F}.
     *
     * <p>Its own inverse, which is what makes it usable in both directions — the editor reads a
     * measured shape (already in the bone's frame) back into the frame an author types in.</p>
     */
    public static void flipY180(Vector3f offset, Quaternionf rotation)
    {
        offset.set(-offset.x, offset.y, -offset.z);

        Quaternionf flip = new Quaternionf().rotateY(MathUtils.PI);

        flip.mul(rotation, rotation);
        rotation.mul(flip);
    }

    /* Auto markup */

    /**
     * How big a bone's own geometry is, in blocks — the number the automatic markup thresholds on.
     * Zero for a bone with nothing drawn from it, so those are never marked up.
     */
    public static float boneSize(IModel model, String bone, Vector3f scale)
    {
        float biggest = 0F;

        for (SubShape shape : measure(model, bone, scale))
        {
            Vector3f half = shape.half();

            biggest = Math.max(biggest, 2F * Math.max(half.x, Math.max(half.y, half.z)));
        }

        return biggest;
    }
}
