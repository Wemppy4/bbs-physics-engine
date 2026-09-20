package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.*;
import mchorse.bbs_mod.forms.forms.StructureForm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.forms.ValueData;
import wemppy.bbs_physics.structure.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Exercises actual Jolt block release and cached playback without an interactive Minecraft client. */
public final class StructureDestructionCheck
{
    public static void main(String[] args) throws Exception
    {
        BBSSettings.recordingTransformOverlays = new ValueInt("overlays", 0);
        loadJolt();
        nativeRelease();
        playback();
        System.out.println("StructureDestructionCheck passed: threshold, radius, release, falling, ground, slab, reset, scrub, moving/scaled parent and interpolation.");
    }

    private static void nativeRelease()
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            var bodies = world.getBodies();
            int floor = bodies.createAndAddBody(new BodyCreationSettings(new BoxShape(20F, 0.5F, 20F),
                new RVec3(0, -0.5, 0), Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC), EActivation.DontActivate);
            int[] ids = new int[3];
            PhysicsCache cache = new PhysicsCache();
            int[] channels = {cache.addChannel(), cache.addChannel(), cache.addChannel()};
            cache.seal();
            for (int i = 0; i < 3; i++)
            {
                var shape = StructureRig.shape(List.of(new Box(0, 0, 0, 1, i == 2 ? 0.5 : 1, 1)), new Vector3f(1));
                ids[i] = bodies.createAndAddBody(new BodyCreationSettings(shape, new RVec3(i * 3, 3, 0),
                    Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.BONE), EActivation.DontActivate);
            }
            TestForm form = new TestForm();
            StructureDestruction.setStrength(form, 3F);
            require(StructureDestruction.strength(form) == 3F, "Strength must round-trip");
            var state = new DestructionState("test", List.of(BlockPos.ORIGIN, new BlockPos(3, 0, 0), new BlockPos(6, 0, 0)), new Vector3f(), new Vector3f(1));
            StructureRig rig = new StructureRig(form, "", state, ids, channels);
            rig.impulse(world, SceneImpulse.of(0, 3, 0, 1, 3, new Vector3f(0, 1, 0)));
            require(bodies.getMotionType(ids[0]) == EMotionType.Kinematic, "Threshold equality must not break a block");
            rig.impulse(world, SceneImpulse.of(0, 3, 0, 1, 4, new Vector3f(0, 1, 0)));
            require(bodies.getMotionType(ids[0]) == EMotionType.Dynamic, "Strong blast must release block");
            require(bodies.getMotionType(ids[1]) == EMotionType.Kinematic, "Outside-radius block must stay attached");
            require(bodies.getLinearVelocity(ids[0]).getY() > 3.9F, "Released block must receive velocity");
            rig.impulse(world, SceneImpulse.of(0, 3, 0, 1, 1, new Vector3f(0, 1, 0)));
            require(bodies.getLinearVelocity(ids[0]).getY() > 4.9F, "Debris takes subsequent weak impulses");
            rig.impulse(world, SceneImpulse.of(6, 2.75F, 0, 1, 4, new Vector3f(0, 1, 0)));
            world.optimize();
            for (int tick = 0; tick < 200; tick++) world.step();
            double y = bodies.getPosition(ids[0]).yy();
            require(y > 0.4 && y < 0.65, "Cube must land on floor: " + y);
            double slab = bodies.getPosition(ids[2]).yy();
            require(slab > 0.4 && slab < 0.65, "Slab cell origin must remain distinct from centre of mass: " + slab);
            require(Math.abs(bodies.getPosition(ids[1]).yy() - 3) < 0.001, "Unbroken block must not fall");
            rig.record(world, null, cache, 0);
            cache.commit(0);
            rig.readCache(cache, 0, true);
            require(state.detached().cardinality() == 2, "Cache must retain detached identities");
            rig.reset(bodies);
            rig.record(world, null, cache, 1);
            cache.commit(1);
            rig.readCache(cache, 1, true);
            require(!state.isBroken() && bodies.getMotionType(ids[0]) == EMotionType.Kinematic, "Reset must reattach blocks");
            rig.readCache(cache, 0, true);
            require(state.detached().cardinality() == 2, "Scrub must restore earlier destruction");
        }
    }

    private static void playback()
    {
        PhysicsCache cache = new PhysicsCache();
        int channel = cache.addChannel();
        cache.seal();
        Vector3f offset = new Vector3f(-4, -1, -2);
        BlockPos block = new BlockPos(2, 3, 1);
        Vector3f center = new Vector3f(2.5F, 3.5F, 1.5F).add(offset);
        Matrix4f parent = new Matrix4f().translate(11.25F, 4.1F, -9.3F).rotateY(0.73F).scale(2F);
        Vector3f position = parent.transformPosition(center, new Vector3f());
        Quaternionf rotation = parent.getUnnormalizedRotation(new Quaternionf());
        cache.write(0, channel, position, rotation, 1F); cache.commit(0);
        cache.write(1, channel, new Vector3f(position).add(0, 2, 0), rotation, 0F); cache.commit(1);
        DestructionState state = new DestructionState("test", List.of(block), offset, new Vector3f(2));
        state.read(cache, new int[]{channel}, 0, true);
        require(!state.isBroken(), "Intact recording must use original renderer");
        state.read(cache, new int[]{channel}, 1, false);
        Matrix4f movedParent = new Matrix4f().translate(-20, 15, 8).rotateZ(-0.43F).scale(3F, 2F, 1F);
        state.renderFrame(movedParent);
        Matrix4f delta = state.transform(0, 0.5F, new Matrix4f());
        Vector3f actual = movedParent.mul(delta, new Matrix4f()).transformPosition(center, new Vector3f());
        require(actual.distance(new Vector3f(position).add(0, 1, 0)) < 0.0001, "Debris must interpolate in scene coordinates under a moving/scaled parent");
        state.read(cache, new int[]{channel}, 1, true);
        actual = movedParent.mul(state.transform(0, 0F, new Matrix4f()), new Matrix4f()).transformPosition(center, new Vector3f());
        require(actual.distance(new Vector3f(position).add(0, 2, 0)) < 0.0001, "Scrub must not interpolate from stale pose");
        state.read(cache, new int[]{channel}, 0, true);
        require(!state.isBroken(), "Rewind before blast must restore intact structure");
        state.read(cache, new int[]{channel}, 50, true);
        require(!state.isBroken(), "Unrecorded frame must show original structure");
    }

    static void loadJolt() throws Exception
    {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String system = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86-64";
        String file = system.equals("windows") ? "joltjni.dll" : system.equals("osx") ? "libjoltjni.dylib" : "libjoltjni.so";
        Path target = Path.of(file).toAbsolutePath();
        try (var stream = StructureDestructionCheck.class.getClassLoader().getResourceAsStream(system + "/" + cpu + "/com/github/stephengold/" + file))
        {
            if (stream == null) throw new IllegalStateException("Missing Jolt native for checks");
            Files.write(target, stream.readAllBytes());
        }
        System.load(target.toString());
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        require(Jolt.newFactory(), "Jolt factory");
        Jolt.registerTypes();
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class TestForm extends StructureForm implements IStructurePhysicsForm
    {
        private final ValueData value = new ValueData(StructureDestruction.KEY);
        private DestructionState state;
        @Override public ValueData bbs_physics$getDestruction() { return this.value; }
        @Override public DestructionState bbs_physics$getDestructionState() { return this.state; }
        @Override public void bbs_physics$setDestructionState(DestructionState state) { this.state = state; }
    }
}
