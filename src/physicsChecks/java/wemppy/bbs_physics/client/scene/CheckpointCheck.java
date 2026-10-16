package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.PointConstraintSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.FloatType;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.engine.RigMemory;
import wemppy.bbs_physics.engine.SwingWindow;
import wemppy.bbs_physics.forms.ValueData;
import wemppy.bbs_physics.structure.DestructionState;
import wemppy.bbs_physics.structure.IStructurePhysicsForm;
import wemppy.bbs_physics.structure.StructureDestruction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checkpoints, against real Jolt and without a Minecraft client: a world put back to one after a
 * different future ran past it steps on exactly as the run that took it, a rig's memory round-trips,
 * and an edit is placed on the tick it changes.
 */
public final class CheckpointCheck
{
    public static void main(String[] args) throws Exception
    {
        BBSSettings.recordingTransformOverlays = new ValueInt("overlays", 0);
        StructureDestructionCheck.loadJolt();
        memory();
        swing();
        checkpoints();
        changes();
        resume();
        handover();
        startOver();
        layout();
        structure();
        System.out.println("CheckpointCheck passed: memory, swing, checkpoint lookup, edit placement, bit-exact resume past a different future, "
            + "bit-exact resume in a world built afresh on the same ground, starting over as built, recording layout, structure blocks.");
    }

    /** Every kind of value comes back as it went in, and a rig reading too much or too little is caught. */
    private static void memory()
    {
        RigMemory memory = new RigMemory();
        Object kept = new Object();
        Matrix4f matrix = new Matrix4f().rotateXYZ(0.3F, -1.2F, 2.5F).translate(1.5F, -2F, 3.25F);

        memory.mark("a").write(true).write(-7).write(1.0e-7F).write(Math.PI).write(-0F).write(Float.NaN)
            .write(new Vector3f(1, 2, 3)).write(new Quaternionf(0.1F, 0.2F, 0.3F, 0.9F)).write(matrix).keep(kept).mark("b");

        memory.rewind().expect("a");
        require(memory.readBoolean() && memory.readInt() == -7 && memory.readFloat() == 1.0e-7F && memory.readDouble() == Math.PI, "Plain values round-trip");
        require(Float.floatToRawIntBits(memory.readFloat()) == Float.floatToRawIntBits(-0F) && Float.isNaN(memory.readFloat()), "Negative zero and NaN round-trip bit for bit");
        require(memory.read(new Vector3f()).equals(new Vector3f(1, 2, 3)), "Vectors round-trip");
        require(memory.read(new Quaternionf()).equals(new Quaternionf(0.1F, 0.2F, 0.3F, 0.9F)), "Rotations round-trip");
        require(memory.read(new Matrix4f()).equals(matrix), "Matrices round-trip");
        require(memory.kept() == kept, "Kept references come back as themselves");
        memory.expect("b");
        require(memory.isDrained(), "Everything written was read");

        memory.rewind().expect("a");
        memory.readBoolean();

        try
        {
            memory.expect("b");
            throw new AssertionError("A rig reading too little must be caught at the next mark");
        }
        catch (IllegalStateException expected)
        {}
    }

    /** A window picked up from its memory lets go with the swing it had, not the one pushed after. */
    private static void swing()
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            world.setGravity(0F);
            BodyInterface bodies = world.getBodies();
            int first = dynamic(world, new RVec3(0, 5, 0));
            int second = dynamic(world, new RVec3(5, 5, 0));
            SwingWindow straight = new SwingWindow();
            SwingWindow resumed = new SwingWindow();
            Quat turn = new Quat(0F, 0.0998F, 0F, 0.995F);
            RigMemory memory = new RigMemory();

            for (int i = 0; i < 3; i++)
            {
                straight.push(new RVec3(i * 0.3, 0, 0), Quat.sIdentity());
                resumed.push(new RVec3(i * 0.3, 0, 0), Quat.sIdentity());
            }

            resumed.remember(memory);
            resumed.push(new RVec3(9, 9, 9), turn);
            resumed.push(new RVec3(-9, 0, 4), Quat.sIdentity());
            resumed.recall(memory.rewind());

            straight.push(new RVec3(1.2, 0.1, 0), turn);
            resumed.push(new RVec3(1.2, 0.1, 0), turn);
            require(straight.release(bodies, first) && resumed.release(bodies, second), "Both windows let go");
            require(bodies.getLinearVelocity(first).equals(bodies.getLinearVelocity(second))
                && bodies.getAngularVelocity(first).equals(bodies.getAngularVelocity(second)), "The resumed window throws exactly as the straight one");
        }
    }

    /** Where the checkpoints fall and which one an edit picks up from. */
    private static void checkpoints()
    {
        SceneCheckpoints checkpoints = new SceneCheckpoints();

        require(!checkpoints.due(0) && checkpoints.due(SceneCheckpoints.INTERVAL) && !checkpoints.due(SceneCheckpoints.INTERVAL + 1), "One checkpoint a second, none at the opening frame");

        for (int tick = 20; tick <= 60; tick += 20)
        {
            checkpoints.add(new SceneCheckpoints.Checkpoint(tick, new byte[16], new RigMemory(), -1));
        }

        require(checkpoints.before(20) == null && checkpoints.before(21).tick() == 20 && checkpoints.before(1000).tick() == 60, "An edit picks up from the last checkpoint strictly before it");
        checkpoints.dropAfter(30);
        require(checkpoints.at(40) == null && checkpoints.at(20) != null, "Checkpoints after a dropped stretch go with it");
    }

    /** An edited channel is placed two keys back, from the start when one of the first two keys changed. */
    private static void changes()
    {
        List<SceneChanges.Key> keys = keys(0, 20, 40, 60, 80);

        require(SceneChanges.firstChange(keys, keys(0, 20, 40, 60, 80)) == SceneChanges.NONE, "An unchanged channel changes nothing");
        require(SceneChanges.firstChange(keys, keys(5, 20, 40, 60, 80)) == 0, "The first key reaches the start");
        require(SceneChanges.firstChange(keys, keys(0, 25, 40, 60, 80)) == 0, "So does the second");

        List<SceneChanges.Key> moved = new ArrayList<>(keys);

        moved.set(3, new SceneChanges.Key(60F, true, new FloatType(1F)));
        require(SceneChanges.firstChange(keys, moved) == 20, "A changed value reaches back two keys");
        require(SceneChanges.firstChange(keys, keys(0, 20, 40, 60, 10)) == 10, "A key moved back past the keys before it counts from where it now sits");
        require(SceneChanges.firstChange(keys, keys(0, 20, 40, 60, 80, 100)) == 60, "An added key reaches back two keys");
        require(SceneChanges.firstChange(keys, keys(0, 20, 40, 60)) == 40, "A removed key reaches back two keys");

        /* Playback passes over keys switched off, and so does the count back. */
        List<SceneChanges.Key> sleeping = keys(0, 10, 20, 40, 60, 80);

        sleeping.set(3, new SceneChanges.Key(40F, false, new FloatType(40F)));
        sleeping.set(4, new SceneChanges.Key(60F, false, new FloatType(60F)));

        List<SceneChanges.Key> woken = new ArrayList<>(sleeping);

        woken.set(5, new SceneChanges.Key(80F, true, new FloatType(1F)));
        require(SceneChanges.firstChange(sleeping, woken) == 10, "Keys switched off are not counted back");

        woken = new ArrayList<>(sleeping);
        woken.set(4, new SceneChanges.Key(60F, true, new FloatType(60F)));
        require(SceneChanges.firstChange(sleeping, woken) == 10, "Switching a key on reaches back two keys that play");

        KeyframeLoop loop = new KeyframeLoop("loop", 40F, 60F, 200F);

        require(SceneChanges.firstLoopChange(List.of(loop), List.of(loop)) == SceneChanges.NONE, "Unchanged loops change nothing");
        require(SceneChanges.firstLoopChange(List.of(), List.of(loop)) == 40, "An added loop changes the film from its start");
        require(SceneChanges.firstLoopChange(List.of(loop), List.of(loop.withEnd(120F))) == 40, "So does a shortened one");
        require(SceneChanges.firstLoopChange(List.of(loop), List.of()) == 40, "And a removed one");
    }

    /**
     * The film recorded straight through, against the same film picked up from a checkpoint after a
     * different future — a box let go earlier, a joint breaking earlier, another push — had already
     * run past it. Bit for bit, every tick after the checkpoint.
     */
    private static void resume()
    {
        List<double[]> straight = new ArrayList<>();

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world);

            straight.add(scene.pose());

            for (int tick = 1; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                straight.add(scene.pose());
            }
        }

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world);

            for (int tick = 1; tick <= 20; tick++)
            {
                scene.act(tick, false);
                world.step();
            }

            byte[] state = world.saveState();
            boolean held = scene.held;
            boolean joined = scene.joined;

            for (int tick = 21; tick <= 60; tick++)
            {
                scene.act(tick, true);
                world.step();
            }

            require(!Arrays.equals(straight.get(60), scene.pose()), "The edited future must actually differ");
            scene.recall(held, joined);
            require(world.restoreState(state), "Jolt accepts its own checkpoint");
            require(Arrays.equals(straight.get(20), scene.pose()), "The checkpoint puts every body back");

            for (int tick = 21; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                require(Arrays.equals(straight.get(tick), scene.pose()), "Picked up from the checkpoint, tick " + tick + " matches the straight run bit for bit");
            }
        }
    }

    /**
     * A checkpoint taken in one world and put back into another built the same way — standing on the
     * very ground shape the first stood on, the first world long gone — goes on exactly as the first
     * would have. What a scene built after BBS rebuilt the cast relies on (see {@link SceneHandover}).
     */
    private static void handover()
    {
        List<double[]> straight = new ArrayList<>();
        ShapeRefC ground = new BoxShape(20F, 0.5F, 20F).toRefC();

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world, ground);

            straight.add(scene.pose());

            for (int tick = 1; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                straight.add(scene.pose());
            }
        }

        byte[] state;
        boolean held;
        boolean joined;

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world, ground);

            /* Past the box being let go, short of the hinge breaking. */
            for (int tick = 1; tick <= 35; tick++)
            {
                scene.act(tick, false);
                world.step();
            }

            state = world.saveState();
            held = scene.held;
            joined = scene.joined;
        }

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world, ground);

            require(!Arrays.equals(straight.get(35), scene.pose()), "A world built afresh stands on its opening frame");
            scene.recall(held, joined);
            require(world.restoreState(state), "A world built the same way accepts another world's checkpoint");
            require(Arrays.equals(straight.get(35), scene.pose()), "The checkpoint puts every body where the first world had it");

            for (int tick = 36; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                require(Arrays.equals(straight.get(tick), scene.pose()), "Gone on in a world built afresh, tick " + tick + " matches the first world bit for bit");
            }
        }
    }

    /**
     * A world put back to where it stood as built runs the film again exactly as it ran the first
     * time, whatever the run between left in it — what starting over relies on, so that a recording
     * made after edits is the one a scene built afresh makes (see {@code FilmScene#rewind}).
     */
    private static void startOver()
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world);
            byte[] built = world.saveState();
            List<double[]> first = new ArrayList<>();

            for (int tick = 1; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                first.add(scene.pose());
            }

            scene.recall(true, true);
            require(world.restoreState(built), "Jolt accepts the world as built");

            for (int tick = 1; tick <= 60; tick++)
            {
                scene.act(tick, false);
                world.step();
                require(Arrays.equals(first.get(tick - 1), scene.pose()), "Started over as built, tick " + tick + " matches the first run bit for bit");
            }
        }
    }

    /** Two recordings lay a tick out the same way only with the same channels, as wide, in order. */
    private static void layout()
    {
        PhysicsCache a = new PhysicsCache();
        PhysicsCache b = new PhysicsCache();
        PhysicsCache c = new PhysicsCache();
        PhysicsCache d = new PhysicsCache();

        a.addChannel();
        a.addChannel(24);
        b.addChannel();
        b.addChannel(24);
        c.addChannel(24);
        c.addChannel();
        d.addChannel();

        require(a.sameLayout(b), "The same channels lay a tick out the same way");
        require(!a.sameLayout(c), "The same channels in another order do not");
        require(!a.sameLayout(d) && !a.sameLayout(null), "Nor do fewer");
    }

    /** A pushed crate, a box held by the animation and let go, and a hinge that breaks. */
    private static final class Scene
    {
        private final PhysicsWorld world;
        private final int crate;
        private final int box;
        private final int upper;
        private final int lower;
        private final TwoBodyConstraint joint;
        private boolean held = true;
        private boolean joined = true;

        private Scene(PhysicsWorld world)
        {
            this(world, new BoxShape(20F, 0.5F, 20F));
        }

        /** @param ground what the floor is made of — one shape may stand any number of worlds */
        private Scene(PhysicsWorld world, ConstShape ground)
        {
            this.world = world;

            BodyInterface bodies = world.getBodies();

            bodies.createAndAddBody(new BodyCreationSettings(ground, new RVec3(0, -0.5, 0),
                Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC), EActivation.DontActivate);
            this.crate = dynamic(world, new RVec3(0, 2, 0));

            BodyCreationSettings held = new BodyCreationSettings(new BoxShape(0.4F, 0.4F, 0.4F), new RVec3(3, 1, 0),
                Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.BONE);

            held.setAllowDynamicOrKinematic(true);
            this.box = bodies.createAndAddBody(held, EActivation.Activate);

            Body a = bodies.createBody(new BodyCreationSettings(new BoxShape(0.3F, 0.3F, 0.3F), new RVec3(-3, 3, 0),
                Quat.sIdentity(), EMotionType.Dynamic, PhysicsLayers.MOVING));
            Body b = bodies.createBody(new BodyCreationSettings(new BoxShape(0.3F, 0.3F, 0.3F), new RVec3(-3, 2.2, 0),
                Quat.sIdentity(), EMotionType.Dynamic, PhysicsLayers.MOVING));

            bodies.addBody(a.getId(), EActivation.Activate);
            bodies.addBody(b.getId(), EActivation.Activate);
            this.upper = a.getId();
            this.lower = b.getId();

            PointConstraintSettings settings = new PointConstraintSettings();

            settings.setSpace(EConstraintSpace.WorldSpace);
            settings.setPoint1(new RVec3(-3, 2.6, 0));
            settings.setPoint2(new RVec3(-3, 2.6, 0));
            this.joint = settings.create(a, b);
            world.getSystem().addConstraint(this.joint);
            world.optimize();
        }

        /** What the drives would do on {@code tick}: the straight film, or the edited one. */
        private void act(int tick, boolean edited)
        {
            BodyInterface bodies = this.world.getBodies();

            if (this.held && tick >= (edited ? 22 : 30))
            {
                bodies.setMotionType(this.box, EMotionType.Dynamic, EActivation.Activate);
                bodies.setObjectLayer(this.box, PhysicsLayers.MOVING);
                this.held = false;
            }

            if (this.held)
            {
                bodies.moveKinematic(this.box, new RVec3(3 + tick * 0.05, 1 + tick * 0.02, 0), Quat.sIdentity(), this.world.getDeltaTime());
            }

            if (this.joined && tick >= (edited ? 23 : 40))
            {
                this.joint.setEnabled(false);
                this.joined = false;
            }

            if (tick == (edited ? 25 : 10))
            {
                bodies.setLinearVelocity(this.crate, new Vec3(edited ? -4F : 3F, 2F, 0F));
                bodies.activateBody(this.crate);
            }
        }

        /** What a rig's recall does: what the body is, and which joint is on, without waking anything. */
        private void recall(boolean held, boolean joined)
        {
            BodyInterface bodies = this.world.getBodies();

            if (held != this.held)
            {
                bodies.setMotionType(this.box, held ? EMotionType.Kinematic : EMotionType.Dynamic, EActivation.DontActivate);
                bodies.setObjectLayer(this.box, held ? PhysicsLayers.BONE : PhysicsLayers.MOVING);
                this.held = held;
            }

            this.joint.setEnabled(joined);
            this.joined = joined;
        }

        private double[] pose()
        {
            BodyInterface bodies = this.world.getBodies();
            double[] pose = new double[4 * 7];
            int at = 0;

            for (int id : new int[] {this.crate, this.box, this.upper, this.lower})
            {
                RVec3 position = new RVec3();
                Quat rotation = new Quat();

                bodies.getPositionAndRotation(id, position, rotation);
                pose[at++] = position.xx();
                pose[at++] = position.yy();
                pose[at++] = position.zz();
                pose[at++] = rotation.getX();
                pose[at++] = rotation.getY();
                pose[at++] = rotation.getZ();
                pose[at++] = rotation.getW();
            }

            return pose;
        }
    }

    /** Broken-off blocks come back broken off: dynamic, on the moving layer; the rest held. */
    private static void structure()
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            BodyInterface bodies = world.getBodies();
            int[] ids = new int[3];
            PhysicsCache cache = new PhysicsCache();
            int[] channels = {cache.addChannel(), cache.addChannel(), cache.addChannel()};

            cache.seal();

            for (int i = 0; i < 3; i++)
            {
                ids[i] = bodies.createAndAddBody(new BodyCreationSettings(StructureRig.shape(List.of(new Box(0, 0, 0, 1, 1, 1)), new Vector3f(1)),
                    new RVec3(i * 3, 3, 0), Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.BONE), EActivation.DontActivate);
            }

            TestForm form = new TestForm();

            StructureDestruction.setStrength(form, 3F);

            DestructionState state = new DestructionState("test", List.of(BlockPos.ORIGIN, new BlockPos(3, 0, 0), new BlockPos(6, 0, 0)), new Vector3f(), new Vector3f(1));
            StructureRig rig = new StructureRig(form, "", state, ids, channels);
            RigMemory memory = new RigMemory();

            rig.impulse(world, SceneImpulse.of(0, 3, 0, 1, 4, new Vector3f(0, 1, 0)));
            require(bodies.getMotionType(ids[0]) == EMotionType.Dynamic, "The blast breaks the first block off");
            rig.remember(memory);
            rig.impulse(world, SceneImpulse.of(3, 3, 0, 1, 4, new Vector3f(0, 1, 0)));
            require(bodies.getMotionType(ids[1]) == EMotionType.Dynamic, "A later blast breaks the second");
            rig.recall(memory.rewind(), world);
            require(memory.isDrained(), "The structure reads back what it wrote");
            require(bodies.getMotionType(ids[0]) == EMotionType.Dynamic && bodies.getObjectLayer(ids[0]) == PhysicsLayers.MOVING, "A block broken by the checkpoint stays broken");
            require(bodies.getMotionType(ids[1]) == EMotionType.Kinematic && bodies.getObjectLayer(ids[1]) == PhysicsLayers.BONE, "A block broken after it is whole again");
        }
    }

    private static int dynamic(PhysicsWorld world, RVec3 at)
    {
        return world.getBodies().createAndAddBody(new BodyCreationSettings(new BoxShape(0.5F, 0.5F, 0.5F), at,
            Quat.sIdentity(), EMotionType.Dynamic, PhysicsLayers.MOVING), EActivation.Activate);
    }

    private static List<SceneChanges.Key> keys(float... ticks)
    {
        List<SceneChanges.Key> keys = new ArrayList<>();

        for (float tick : ticks)
        {
            keys.add(new SceneChanges.Key(tick, true, new FloatType(tick)));
        }

        return keys;
    }

    private static void require(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestForm extends StructureForm implements IStructurePhysicsForm
    {
        private final ValueData value = new ValueData(StructureDestruction.KEY);
        private DestructionState state;

        @Override public ValueData bbs_physics$getDestruction() { return this.value; }
        @Override public DestructionState bbs_physics$getDestructionState() { return this.state; }
        @Override public void bbs_physics$setDestructionState(DestructionState state) { this.state = state; }
    }
}
