package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.*;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.structure.StructureManager;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.forms.structure.StructureRenderWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.engine.*;
import wemppy.bbs_physics.structure.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One body per solid block. Intact blocks follow the form; a blast releases them permanently. */
public final class StructureRig implements SceneRig
{
    private final StructureForm form;
    private final String path;
    private final DestructionState state;
    private final int[] bodies;
    private final int[] channels;
    private final boolean[] detached;
    private final Matrix4f frame = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final RVec3 jPosition = new RVec3();
    private final Quat jRotation = new Quat();
    private final KinematicDrive drive = new KinematicDrive();

    StructureRig(StructureForm form, String path, DestructionState state, int[] bodies, int[] channels)
    {
        this.form = form;
        this.path = path;
        this.state = state;
        this.bodies = bodies;
        this.channels = channels;
        this.detached = new boolean[bodies.length];
    }

    public static StructureRig build(PhysicsWorld physics, StructureForm form, String path,
        MatrixCache matrices, Matrix4f actorWorld, FilmScene scene)
    {
        StructureRenderData data = StructureManager.get(form.structure.get());
        var entry = matrices.get(path);
        if (data == null || entry == null || entry.matrix() == null)
        {
            throw new IllegalStateException("Cannot load destructible structure: " + form.structure.get());
        }

        Matrix4f frame = new Matrix4f(actorWorld).mul(entry.matrix());
        Vector3f scale = frame.getScale(new Vector3f());
        if (!scale.isFinite() || Math.min(scale.x, Math.min(scale.y, scale.z)) < 0.01F || frame.determinant3x3() <= 0F)
        {
            throw new IllegalArgumentException("Destruction requires a positive, non-zero structure scale.");
        }
        /* A sheared box cannot be represented by a rotated/scaled voxel collider. */
        Matrix4f rigid = new Matrix4f().translationRotateScale(frame.getTranslation(new Vector3f()),
            frame.getUnnormalizedRotation(new Quaternionf()).normalize(), scale);
        if (!rigid.equals(frame, 0.001F))
        {
            throw new IllegalArgumentException("Destruction does not support a sheared structure transform.");
        }

        Vector3f offset = new Vector3f(-data.size.getX() / 2F, 0F, -data.size.getZ() / 2F).sub(form.origin.get());
        StructureRenderWorld blockWorld = new StructureRenderWorld(data, form.biome.get());
        List<BlockPos> positions = new ArrayList<>();
        List<List<Box>> shapes = new ArrayList<>();
        List<BlockPos> ordered = new ArrayList<>(data.getBlocks().keySet());
        ordered.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos pos : ordered)
        {
            List<Box> boxes = data.getBlockState(pos).getCollisionShape(blockWorld, pos).getBoundingBoxes();
            if (!boxes.isEmpty())
            {
                positions.add(pos);
                shapes.add(boxes);
            }
        }
        if (positions.isEmpty()) return null;
        if (positions.size() > StructureDestruction.MAX_BLOCKS || positions.size() > physics.remainingBodyCapacity())
        {
            throw new IllegalArgumentException("Destructible structure has " + positions.size()
                + " solid blocks; limit per structure is " + StructureDestruction.MAX_BLOCKS
                + ", remaining scene capacity is " + physics.remainingBodyCapacity());
        }

        int[] ids = new int[positions.size()];
        int[] channels = new int[positions.size()];
        for (int i = 0; i < ids.length; i++)
        {
            ConstShape shape = shape(shapes.get(i), scale);
            BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(), Quat.sIdentity(),
                EMotionType.Kinematic, PhysicsLayers.BONE);
            settings.setFriction(0.6F);
            settings.setRestitution(0.05F);
            settings.setMotionQuality(EMotionQuality.LinearCast);
            ids[i] = physics.getBodies().createAndAddBody(settings, EActivation.DontActivate);
            if (ids[i] == -1) throw new IllegalStateException("Jolt could not allocate a structure block body.");
            channels[i] = scene.addChannel();
        }
        DestructionState state = new DestructionState(form.structure.get(), positions, offset, scale);
        ((IStructurePhysicsForm) form).bbs_physics$setDestructionState(state);
        return new StructureRig(form, path, state, ids, channels);
    }

    /** Voxel collision, centred on the block cell rather than the shape's centre of mass. */
    static ConstShape shape(List<Box> boxes, Vector3f scale)
    {
        StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();
        for (Box box : boxes)
        {
            float x = Math.max(0.001F, (float) box.getLengthX() * scale.x * 0.5F);
            float y = Math.max(0.001F, (float) box.getLengthY() * scale.y * 0.5F);
            float z = Math.max(0.001F, (float) box.getLengthZ() * scale.z * 0.5F);
            compound.addShape(new Vec3((float) ((box.minX + box.maxX) * 0.5 - 0.5) * scale.x,
                    (float) ((box.minY + box.maxY) * 0.5 - 0.5) * scale.y,
                    (float) ((box.minZ + box.maxZ) * 0.5 - 0.5) * scale.z),
                Quat.sIdentity(), new BoxShape(new Vec3(x, y, z), Math.min(0.02F, Math.min(x, Math.min(y, z)))));
        }
        ShapeResult result = compound.create();
        if (result.hasError()) throw new IllegalArgumentException(result.getError());
        return result.get();
    }

    @Override public Form getForm() { return this.form; }

    @Override
    public void captureFrame(RigUpdate update)
    {
        var entry = update.matrices.get(this.path);
        if (entry == null || entry.matrix() == null) throw new IllegalStateException("Missing structure frame: " + this.path);
        this.frame.set(update.actorWorld).mul(entry.matrix());
        this.frame.m30((float) (this.frame.m30() - update.scene.getOriginX()));
        this.frame.m31((float) (this.frame.m31() - update.scene.getOriginY()));
        this.frame.m32((float) (this.frame.m32() - update.scene.getOriginZ()));
    }

    @Override
    public void update(RigUpdate update)
    {
        this.drive.setDeltaTime(update.physics.getDeltaTime());

        this.captureFrame(update);
        BodyInterface bodies = update.physics.getBodies();
        if (update.reset) this.reset(bodies);
        this.frame.getUnnormalizedRotation(this.rotation).normalize();
        this.jRotation.set(this.rotation.x, this.rotation.y, this.rotation.z, this.rotation.w);
        for (int i = 0; i < this.bodies.length; i++)
        {
            if (this.detached[i]) continue;
            BlockPos block = this.state.blocks.get(i);
            this.frame.transformPosition(this.position.set(block.getX() + 0.5F, block.getY() + 0.5F, block.getZ() + 0.5F).add(this.state.offset));
            this.jPosition.set(this.position.x, this.position.y, this.position.z);
            if (update.reset) this.drive.place(bodies, this.bodies[i], this.jPosition, this.jRotation);
            else this.drive.move(bodies, this.bodies[i], this.jPosition, this.jRotation);
        }
    }

    void reset(BodyInterface bodies)
    {
        for (int i = 0; i < this.bodies.length; i++)
        {
            this.detached[i] = false;
            bodies.setMotionType(this.bodies[i], EMotionType.Kinematic, EActivation.DontActivate);
            bodies.setObjectLayer(this.bodies[i], PhysicsLayers.BONE);
        }
    }

    @Override
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        BodyInterface bodies = physics.getBodies();
        float strength = StructureDestruction.strength(this.form);
        for (int i = 0; i < this.bodies.length; i++)
        {
            if (!this.detached[i])
            {
                RVec3 center = bodies.getCenterOfMassPosition(this.bodies[i]);
                if (!push.velocityAt((float) center.xx(), (float) center.yy(), (float) center.zz(), this.velocity)
                    || this.velocity.lengthSquared() <= strength * strength) continue;
                this.detached[i] = true;
                bodies.setObjectLayer(this.bodies[i], PhysicsLayers.MOVING);
                bodies.setMotionType(this.bodies[i], EMotionType.Dynamic, EActivation.Activate);
            }
            push.apply(bodies, this.bodies[i]);
        }
    }

    @Override
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        for (int i = 0; i < this.bodies.length; i++)
        {
            physics.getBodies().getPositionAndRotation(this.bodies[i], this.jPosition, this.jRotation);
            this.position.set((float) this.jPosition.xx(), (float) this.jPosition.yy(), (float) this.jPosition.zz());
            this.rotation.set(this.jRotation.getX(), this.jRotation.getY(), this.jRotation.getZ(), this.jRotation.getW());
            if (!this.position.isFinite() || !this.rotation.isFinite()) throw new IllegalStateException("Invalid structure block pose.");
            cache.write(tick, this.channels[i], this.position, this.rotation, this.detached[i] ? 0F : 1F);
        }
        this.state.renderFrame(this.frame);
    }

    @Override public void readCache(PhysicsCache cache, int tick, boolean teleport) { this.state.read(cache, this.channels, tick, teleport); }
    @Override public boolean needsRenderFrame() { return this.state.isBroken(); }
    @Override public void renderFrame(RigUpdate update) { this.captureFrame(update); this.state.renderFrame(this.frame); }
    @Override public void release() { ((IStructurePhysicsForm) this.form).bbs_physics$setDestructionState(null); }
}
