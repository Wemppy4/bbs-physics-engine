package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CapsuleShape;
import com.github.stephengold.joltjni.Face;
import com.github.stephengold.joltjni.JobSystem;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SoftBodyCreationSettings;
import com.github.stephengold.joltjni.SoftBodyMotionProperties;
import com.github.stephengold.joltjni.SoftBodySharedSettings;
import com.github.stephengold.joltjni.SoftBodyVertex;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.Vertex;
import com.github.stephengold.joltjni.VertexAttributes;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EBendType;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsThreads;
import wemppy.bbs_physics.engine.PhysicsWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Jolt on a pool of threads against Jolt on one, on a scene that gives the threads work of every
 * kind the addon makes: a stack of boxes knocked over by a body driven through it, thrown balls, a
 * jointed strand swinging into it, a sheet of cloth landing on top — every body, every vertex, every
 * tick, bit for bit. On three workers and on seven, switched over halfway and back and forth, two
 * worlds taking turns on the same pool, and a world left to choose for itself. And the choice
 * itself, on made-up timings.
 */
public final class ThreadsCheck
{
    private static final int TICKS = 200;

    public static void main(String[] args) throws Exception
    {
        BBSSettings.recordingTransformOverlays = new ValueInt("overlays", 0);
        StructureDestructionCheck.loadJolt();

        choice();

        try (JobSystemSingleThreaded single = new JobSystemSingleThreaded(2048);
             JobSystemThreadPool three = new JobSystemThreadPool(2048, 8, 3);
             JobSystemThreadPool seven = new JobSystemThreadPool(2048, 8, 7))
        {
            List<double[]> one = run(single, three, (tick) -> false, null);

            require(!Arrays.equals(one.get(0), one.get(TICKS - 1)), "The scene moves, so that there is something to compare");

            same(one, run(single, three, (tick) -> true, null), "three workers");
            same(one, run(single, seven, (tick) -> true, null), "seven workers");
            same(one, run(single, three, (tick) -> tick > TICKS / 2, null), "one thread, then three workers from halfway");
            same(one, run(single, seven, (tick) -> tick / 7 % 2 == 1, null), "one thread and seven workers by turns");

            List<double[]>[] both = interleaved(three);

            same(one, both[0], "three workers, the first of two worlds taking turns");
            same(one, both[1], "three workers, the second of two worlds taking turns");

            boolean[] threaded = new boolean[1];

            same(one, run(null, null, null, threaded), "threads of the world's own choosing");
            System.out.println("ThreadsCheck: left to choose, the scene " + (threaded[0] ? "went to " + PhysicsThreads.workers() + " workers." : "stayed on one thread."));

            BBSPhysicsSettings.threads = new ValueBoolean("threads", false);
            threaded[0] = false;
            same(one, run(null, null, null, threaded), "threads switched off");
            require(!threaded[0], "Switched off, a world never goes to the workers");
            BBSPhysicsSettings.threads = null;
        }

        System.out.println("ThreadsCheck passed: on any number of workers, switched over at any tick, a world steps to the very bit it does on one thread.");
    }

    /** The choice between one thread and the pool, on made-up timings. */
    private static void choice()
    {
        PhysicsThreads.Choice light = new PhysicsThreads.Choice();

        for (int i = 0; i < 100; i++)
        {
            light.learn(i == 50 ? 40D : 0.1D);
        }

        require(!light.pooled(), "A light scene stays on one thread, a hitch or not");

        PhysicsThreads.Choice heavy = new PhysicsThreads.Choice();

        feed(heavy, 2D, PhysicsThreads.Choice.SETTLE - 1);
        require(!heavy.pooled(), "Not before its cost is trusted");
        heavy.learn(2D);
        require(heavy.pooled(), "A heavy scene goes to the pool");
        feed(heavy, 0.8D, 50);
        require(heavy.pooled(), "and stays there while the pool is faster");
        feed(heavy, 0.1D, 50);
        require(!heavy.pooled(), "and comes back once the scene has gone quiet");

        PhysicsThreads.Choice stubborn = new PhysicsThreads.Choice();

        feed(stubborn, 1D, PhysicsThreads.Choice.SETTLE);
        require(stubborn.pooled(), "Tried");
        feed(stubborn, 0.95D, PhysicsThreads.Choice.SETTLE);
        require(!stubborn.pooled(), "and given back when the pool is not a tenth faster");
        feed(stubborn, 1.2D, 100);
        require(!stubborn.pooled(), "and not tried again at about the same cost");
        feed(stubborn, 2D, 2);
        require(stubborn.pooled(), "but tried again once steps grow half as dear again");
        stubborn.reset();
        require(!stubborn.pooled(), "Reset is one thread");
    }

    private static void feed(PhysicsThreads.Choice choice, double milliseconds, int steps)
    {
        for (int i = 0; i < steps; i++)
        {
            choice.learn(milliseconds);
        }
    }

    private static void same(List<double[]> expected, List<double[]> actual, String how)
    {
        for (int tick = 0; tick < expected.size(); tick++)
        {
            if (!Arrays.equals(expected.get(tick), actual.get(tick)))
            {
                throw new AssertionError("Stepped on " + how + ", tick " + (tick + 1) + " differs from one thread");
            }
        }
    }

    /**
     * The scene run for {@link #TICKS} ticks, every body's and vertex's place after each.
     *
     * @param single    one thread, for the ticks {@code pooled} keeps off the pool
     * @param pool      the workers for the ticks {@code pooled} picks; with both null, the world
     *                  chooses for itself
     * @param threaded  set when the world chose the workers at any tick, or null
     */
    private static List<double[]> run(JobSystem single, JobSystem pool, IntPredicate pooled, boolean[] threaded)
    {
        List<double[]> poses = new ArrayList<>(TICKS);

        try (PhysicsWorld world = new PhysicsWorld())
        {
            Scene scene = new Scene(world);

            for (int tick = 1; tick <= TICKS; tick++)
            {
                scene.drive(tick);

                if (pool == null)
                {
                    world.step();
                }
                else
                {
                    world.step(pooled.test(tick) ? pool : single);
                }

                poses.add(scene.pose());

                if (threaded != null && world.isThreaded())
                {
                    threaded[0] = true;
                }
            }
        }

        return poses;
    }

    /** Two copies of the scene stepped by turns on the same workers, as two scenes of a game are. */
    @SuppressWarnings("unchecked")
    private static List<double[]>[] interleaved(JobSystem pool)
    {
        List<double[]>[] poses = new List[] {new ArrayList<>(TICKS), new ArrayList<>(TICKS)};

        try (PhysicsWorld first = new PhysicsWorld(); PhysicsWorld second = new PhysicsWorld())
        {
            Scene a = new Scene(first);
            Scene b = new Scene(second);

            for (int tick = 1; tick <= TICKS; tick++)
            {
                a.drive(tick);
                b.drive(tick);
                first.step(pool);
                second.step(pool);
                poses[0].add(a.pose());
                poses[1].add(b.pose());
            }
        }

        return poses;
    }

    /** A stack of boxes, a body driven through it, thrown balls, a jointed strand and a sheet of cloth. */
    private static final class Scene
    {
        private final PhysicsWorld world;
        private final List<Integer> bodies = new ArrayList<>();
        private final int sweeper;
        private final SoftBodyVertex[] cloth;

        private Scene(PhysicsWorld world)
        {
            this.world = world;

            BodyInterface bodies = world.getBodies();
            Random random = new Random(11);

            bodies.createAndAddBody(new BodyCreationSettings(new BoxShape(30F, 0.5F, 30F), new RVec3(0, -0.5, 0),
                Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC), EActivation.DontActivate);

            for (int x = 0; x < 5; x++)
            {
                for (int y = 0; y < 5; y++)
                {
                    for (int z = 0; z < 5; z++)
                    {
                        RVec3 at = new RVec3(x * 0.85 + random.nextDouble() * 0.05, 0.5 + y * 0.82, z * 0.85 + random.nextDouble() * 0.05);
                        Quat turn = Quat.sEulerAngles(new Vec3(random.nextFloat() * 0.2F, random.nextFloat() * 0.2F, random.nextFloat() * 0.2F));

                        this.bodies.add(bodies.createAndAddBody(new BodyCreationSettings(new BoxShape(0.4F, 0.4F, 0.4F), at, turn,
                            EMotionType.Dynamic, PhysicsLayers.MOVING), EActivation.Activate));
                    }
                }
            }

            /* Driven through the stack the way a rig drives an animated part. */
            this.sweeper = bodies.createAndAddBody(new BodyCreationSettings(new BoxShape(0.3F, 1.5F, 0.3F), new RVec3(-2, 1.5, 1.7),
                Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.MOVING), EActivation.Activate);

            for (int i = 0; i < 6; i++)
            {
                int ball = bodies.createAndAddBody(new BodyCreationSettings(new SphereShape(0.3F), new RVec3(-4 + i * 0.7, 3, 2),
                    Quat.sIdentity(), EMotionType.Dynamic, PhysicsLayers.MOVING), EActivation.Activate);

                bodies.setLinearVelocity(ball, new Vec3(6F, 1F, -1F));
                this.bodies.add(ball);
            }

            /* A strand of fifteen links from a held point, set swinging into the stack. */
            Body anchor = bodies.createBody(new BodyCreationSettings(new SphereShape(0.05F), new RVec3(-3, 8, 1.5),
                Quat.sIdentity(), EMotionType.Kinematic, PhysicsLayers.GHOST));

            bodies.addBody(anchor.getId(), EActivation.Activate);

            Body above = anchor;

            for (int i = 0; i < 15; i++)
            {
                Body link = bodies.createBody(new BodyCreationSettings(new CapsuleShape(0.15F, 0.05F), new RVec3(-3, 7.8 - i * 0.4, 1.5),
                    Quat.sIdentity(), EMotionType.Dynamic, PhysicsLayers.MOVING));

                bodies.addBody(link.getId(), EActivation.Activate);
                bodies.setLinearVelocity(link.getId(), new Vec3(2F + i * 0.2F, 0F, 0.3F));

                SwingTwistConstraintSettings joint = new SwingTwistConstraintSettings();
                RVec3 point = new RVec3(-3, 8 - i * 0.4, 1.5);

                joint.setPosition1(point);
                joint.setPosition2(point);
                joint.setTwistAxis1(new Vec3(0F, -1F, 0F));
                joint.setTwistAxis2(new Vec3(0F, -1F, 0F));
                joint.setPlaneAxis1(new Vec3(1F, 0F, 0F));
                joint.setPlaneAxis2(new Vec3(1F, 0F, 0F));
                joint.setNormalHalfConeAngle(0.6F);
                joint.setPlaneHalfConeAngle(0.6F);

                world.getSystem().addConstraint(joint.create(above, link));
                this.bodies.add(link.getId());
                above = link;
            }

            /* A sheet of cloth, twelve by twelve, dropped flat onto the top of the stack. */
            SoftBodySharedSettings shared = new SoftBodySharedSettings();
            int n = 12;

            for (int r = 0; r < n; r++)
            {
                for (int c = 0; c < n; c++)
                {
                    Vertex vertex = new Vertex();

                    vertex.setPosition(new Vec3(0.2F + c * 0.3F, 6.5F + random.nextFloat() * 0.01F, 0.2F + r * 0.3F));
                    vertex.setInvMass(1F);
                    shared.addVertex(vertex);
                }
            }

            for (int r = 0; r < n - 1; r++)
            {
                for (int c = 0; c < n - 1; c++)
                {
                    int tl = r * n + c;
                    int bl = tl + n;
                    Face first = new Face();
                    Face second = new Face();

                    first.setVertex(0, tl);
                    first.setVertex(1, bl);
                    first.setVertex(2, bl + 1);
                    second.setVertex(0, tl);
                    second.setVertex(1, bl + 1);
                    second.setVertex(2, tl + 1);
                    shared.addFace(first);
                    shared.addFace(second);
                }
            }

            shared.createConstraints(new VertexAttributes(1e-4F, 2e-4F, 1e-3F), 1, EBendType.Distance);
            shared.optimize();

            SoftBodyCreationSettings settings = new SoftBodyCreationSettings(shared, new RVec3(0, 0, 0), Quat.sIdentity(), PhysicsLayers.CLOTH);

            settings.setVertexRadius(0.05F);
            settings.setNumIterations(10);

            Body sheet = bodies.createSoftBody(settings);

            bodies.addBody(sheet.getId(), EActivation.Activate);
            this.cloth = ((SoftBodyMotionProperties) sheet.getMotionProperties()).getVertices();
            world.optimize();
        }

        /** Moves the driven body on to where it is meant to be at {@code tick}. */
        private void drive(int tick)
        {
            RVec3 target = new RVec3(-2 + tick * 0.04, 1.5, 1.7);

            this.world.getBodies().moveKinematic(this.sweeper, target, Quat.sIdentity(), this.world.getDeltaTime());
        }

        private double[] pose()
        {
            BodyInterface bodies = this.world.getBodies();
            double[] pose = new double[this.bodies.size() * 7 + this.cloth.length * 3];
            int at = 0;

            for (int id : this.bodies)
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

            for (SoftBodyVertex vertex : this.cloth)
            {
                Vec3 position = vertex.getPosition();

                pose[at++] = position.getX();
                pose[at++] = position.getY();
                pose[at++] = position.getZ();
            }

            return pose;
        }
    }

    private static void require(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }
}
