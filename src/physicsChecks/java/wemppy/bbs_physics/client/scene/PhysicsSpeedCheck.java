package wemppy.bbs_physics.client.scene;

import java.nio.file.*;
import java.util.Locale;
import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.*;
import wemppy.bbs_physics.engine.*;

/** Native integration checks: film ticks stay fixed while physical time changes. */
public final class PhysicsSpeedCheck
{
    public static void main(String[] args) throws Exception
    {
        loadJolt();
        double normal = fall(1F, 40);
        double slow = fall(0.5F, 40);
        double fast = fall(2F, 40);
        require(slow > normal && normal > fast, "Speed must change distance fallen at the same film tick");
        require(Math.abs(normal - fall(2F, 20)) < 0.01, "Equal physical time must match at 2x");
        require(Math.abs(normal - fall(0.5F, 80)) < 0.2, "Equal physical time must match at 0.5x within integration tolerance");
        for (float speed : new float[] {0.1F, 0.5F, 1F, 2F, 4F}) follow(speed);
        System.out.println("PhysicsSpeedCheck passed: time scaling, kinematic following, dynamic drive and release at 0.1x–4x.");
    }

    private static double fall(float speed, int ticks)
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            world.setSpeed(speed);
            int id = body(world, EMotionType.Dynamic);
            PhysicsTimeline timeline = new PhysicsTimeline(world);
            for (int i = 0; i < ticks; i++) timeline.step(tick -> {});
            require(timeline.getTick() == ticks, "Film tick must not be scaled");
            return world.getBodies().getPosition(id).yy();
        }
    }

    private static void follow(float speed)
    {
        try (PhysicsWorld world = new PhysicsWorld())
        {
            world.setSpeed(speed);
            world.setGravity(0F);
            require(world.getDeltaTime() / world.getIntegrationSteps() <= PhysicsWorld.TICK / 3F + 1e-6F, "Sub-step must not grow");
            int id = body(world, EMotionType.Kinematic);
            KinematicDrive move = new KinematicDrive();
            move.setDeltaTime(world.getDeltaTime());
            Quat rotation = Quat.sIdentity();
            move.move(world.getBodies(), id, new RVec3(0.1, 0, 0), rotation);
            world.step();
            require(Math.abs(world.getBodies().getPosition(id).xx() - 0.1) < 1e-4, "Kinematic body must reach the film pose");
            world.getBodies().setMotionType(id, EMotionType.Dynamic, EActivation.Activate);
            BodyDrive drive = new BodyDrive();
            drive.setDeltaTime(world.getDeltaTime());
            drive.apply(world.getBodies(), id, new RVec3(0.2, 0, 0), rotation, 1F);
            world.step();
            require(Math.abs(world.getBodies().getPosition(id).xx() - 0.2) < 1e-4, "Driven body must reach the film pose");
            SwingWindow swing = new SwingWindow();
            swing.setDeltaTime(world.getDeltaTime());
            swing.push(new RVec3(0, 0, 0), rotation);
            swing.push(new RVec3(0.1, 0, 0), rotation);
            require(swing.release(world.getBodies(), id), "Release should retain animated motion");
            world.step();
            require(Math.abs(world.getBodies().getPosition(id).xx() - 0.3) < 1e-4, "Release must use the same physical time as the drive");
        }
    }

    private static int body(PhysicsWorld world, EMotionType type)
    {
        BodyCreationSettings settings = new BodyCreationSettings(new BoxShape(0.1F, 0.1F, 0.1F), new RVec3(), Quat.sIdentity(), type, PhysicsLayers.MOVING);
        settings.setLinearDamping(0F);
        settings.setAngularDamping(0F);
        return world.getBodies().createAndAddBody(settings, EActivation.Activate);
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
    static void loadJolt() throws Exception
    {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String system = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86-64";
        String file = system.equals("windows") ? "joltjni.dll" : system.equals("osx") ? "libjoltjni.dylib" : "libjoltjni.so";
        Path target = Path.of(file).toAbsolutePath();
        try (var stream = PhysicsSpeedCheck.class.getClassLoader().getResourceAsStream(system + "/" + cpu + "/com/github/stephengold/" + file))
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

}
