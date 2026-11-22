package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.data.types.FloatType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.client.collision.CollisionShapes;
import wemppy.bbs_physics.collision.CollisionKind;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.RigMemory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

/**
 * A recording kept on disk, without a game: what is written is what is read, to the bit; a file cut
 * short, a file of something else and a file of another layout are refused, never half read; and a
 * fingerprint says the same for the same data however it was put together.
 */
public final class RecordingStoreCheck
{
    public static void main(String[] args) throws Exception
    {
        roundTrip();
        damaged();
        fingerprints();

        System.out.println("RecordingStoreCheck passed: written and read back to the bit, damaged and foreign files refused, fingerprints by content.");
    }

    /** Everything a recording keeps comes back as it went in. */
    private static void roundTrip() throws IOException
    {
        RecordingStore.Stored stored = sample();
        Path file = Path.of("recording-check.bin");

        RecordingStore.write(stored, file);

        RecordingStore.Stored back = RecordingStore.read(file);

        require(back.film().equals(stored.film()) && back.world().equals(stored.world()), "Which film of which world");
        require(Arrays.equals(back.environment(), stored.environment()) && Arrays.equals(back.assets(), stored.assets())
            && Arrays.equals(back.filmData(), stored.filmData()) && Arrays.equals(back.ground(), stored.ground()), "Every fingerprint");
        require(back.originX() == stored.originX() && back.originY() == stored.originY() && back.originZ() == stored.originZ(), "The origin");
        require(back.gravity() == stored.gravity() && back.speed() == stored.speed() && back.collisionSteps() == stored.collisionSteps(), "The knobs");
        require(back.bodies() == stored.bodies() && back.channels().equals(stored.channels()) && Arrays.equals(back.widths(), stored.widths()), "The bodies and the channels");
        require(back.ticks() == stored.ticks() && Arrays.equals(back.floats(), stored.floats()), "The recording, float for float");
        require(back.full() == stored.full() && back.standing() == stored.standing() && back.locked() == stored.locked(),
            "Whether it is full, where it stands, and whether it was locked");
        require(back.checkpoints().size() == stored.checkpoints().size(), "Every checkpoint");

        for (int i = 0; i < stored.checkpoints().size(); i++)
        {
            SceneCheckpoints.Checkpoint a = stored.checkpoints().get(i);
            SceneCheckpoints.Checkpoint b = back.checkpoints().get(i);

            require(a.tick() == b.tick() && a.lostAt() == b.lostAt() && Arrays.equals(a.world(), b.world()), "A checkpoint's world, tick " + a.tick());
            require(Arrays.equals(a.rigs().numbers(), b.rigs().numbers()), "A checkpoint's rig numbers, tick " + a.tick());
            require(Arrays.equals(a.rigs().keptObjects(), b.rigs().keptObjects()), "A checkpoint's kept shapes, tick " + a.tick());
        }

        /* Read back into a recording laid out as the one it was taken from: the same to the bit. */
        PhysicsCache layout = layout();
        PhysicsCache restored = layout.withRecorded(back.floats(), back.ticks());
        PhysicsCache original = layout.withRecorded(stored.floats(), stored.ticks());

        for (int tick = 0; tick < stored.ticks(); tick++)
        {
            require(restored.firstDifference(original, tick) == -1, "Tick " + tick + " read back is the tick written");
        }

        /* And a rig reads its memory back from a checkpoint read back, as it wrote it. */
        RigMemory memory = back.checkpoints().get(0).rigs().rewind();

        memory.expect("body/0");
        require(memory.readBoolean() && memory.readFloat() == 0.25F, "A rig's numbers, in order");
        require(memory.<List<CollisionShapes.SubShape>>kept().equals(shapes()), "A rig's kept shapes, as they were");
        require(memory.isDrained(), "To the end and no further");

        Files.delete(file);
    }

    /** A file cut short, a file of something else, a file of another layout: none is half read. */
    private static void damaged() throws IOException
    {
        Path file = Path.of("recording-damaged.bin");

        RecordingStore.write(sample(), file);

        byte[] whole = Files.readAllBytes(file);

        Files.write(file, Arrays.copyOf(whole, whole.length / 2));
        refused(file, "A file cut short is refused");

        Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        refused(file, "A file of something else is refused");

        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(file))))
        {
            out.writeInt(0x42425052);
            out.writeInt(Integer.MAX_VALUE);
        }

        try
        {
            RecordingStore.read(file);
            require(false, "A file of another layout is refused");
        }
        catch (RecordingStore.StaleFormatException e)
        {
            /* Refused, and said to be another layout rather than damage. */
        }

        Files.delete(file);
    }

    private static void refused(Path file, String message)
    {
        try
        {
            RecordingStore.read(file);
            require(false, message);
        }
        catch (IOException e)
        {
            /* Refused. */
        }
    }

    /** The same data digests the same however its maps were filled; anything else differs. */
    private static void fingerprints()
    {
        MapType one = new MapType();
        MapType nested = new MapType();
        ListType list = new ListType();

        nested.put("p", new StringType("q"));
        list.add(new IntType(2));
        list.add(new IntType(3));
        one.put("x", new FloatType(1F));
        one.put("y", list);
        one.put("z", nested);

        MapType two = new MapType(false);
        MapType nested2 = new MapType(false);
        ListType list2 = new ListType();

        nested2.put("p", new StringType("q"));
        list2.add(new IntType(2));
        list2.add(new IntType(3));
        two.put("z", nested2);
        two.put("y", list2);
        two.put("x", new FloatType(1F));

        require(Arrays.equals(new Fingerprint().put(one).digest(), new Fingerprint().put(two).digest()), "The same data in another order digests the same");

        two.put("x", new FloatType(1.0000001F));
        require(!Arrays.equals(new Fingerprint().put(one).digest(), new Fingerprint().put(two).digest()), "A number one bit off digests differently");

        two.put("x", new DoubleType(1D));
        require(!Arrays.equals(new Fingerprint().put(one).digest(), new Fingerprint().put(two).digest()), "A number of another kind digests differently");

        require(!Arrays.equals(new Fingerprint().put("ab").put("c").digest(), new Fingerprint().put("a").put("bc").digest()), "Values never run together");
        require(new Fingerprint().put("film").hex(20).length() == 40, "A name for a file");
    }

    private static RecordingStore.Stored sample()
    {
        Random random = new Random(7);
        PhysicsCache cache = layout();
        int ticks = 30;
        float[] floats = new float[ticks * cache.getStride()];

        for (int i = 0; i < floats.length; i++)
        {
            floats[i] = random.nextFloat() * 100F - 50F;
        }

        floats[3] = Float.NaN;
        floats[4] = -0F;

        Map<String, Integer> channels = new LinkedHashMap<>();

        channels.put("actor/body/0", 0);
        channels.put("actor/cloth/0", 1);

        RigMemory first = new RigMemory();

        first.mark("body/0");
        first.write(true).write(0.25F).keep(shapes());

        RigMemory second = new RigMemory();

        second.write(42L).write(new Vector3f(1F, 2F, 3F)).keep(List.of());

        byte[] world = new byte[300];

        random.nextBytes(world);

        List<SceneCheckpoints.Checkpoint> checkpoints = List.of(
            new SceneCheckpoints.Checkpoint(20, world, first.trim(), -1),
            new SceneCheckpoints.Checkpoint(29, Arrays.copyOf(world, 100), second.trim(), 12));

        return new RecordingStore.Stored("film/one", "local:/saves/world", bytes(random), bytes(random), bytes(random), bytes(random),
            12D, 64D, -30D, 9.81F, 1F, 2, 17, channels, new int[] {cache.getWidth(0), cache.getWidth(1)}, ticks, floats, false,
            checkpoints, 29, true);
    }

    private static PhysicsCache layout()
    {
        PhysicsCache cache = new PhysicsCache();

        cache.addChannel();
        cache.addChannel(24);
        cache.seal();

        return cache;
    }

    private static List<CollisionShapes.SubShape> shapes()
    {
        return List.of(
            new CollisionShapes.SubShape(CollisionKind.BOX, new Vector3f(0.5F, 0.25F, 0.5F), new Vector3f(0F, 0.25F, 0F), new Quaternionf(), 0.6F),
            new CollisionShapes.SubShape(CollisionKind.CAPSULE, new Vector3f(0.1F, 0.4F, 0.1F), new Vector3f(1F, 0F, -1F), new Quaternionf().rotateZ(0.5F), 0F));
    }

    private static byte[] bytes(Random random)
    {
        byte[] bytes = new byte[32];

        random.nextBytes(bytes);

        return bytes;
    }

    private static void require(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }
}
