package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueLinks;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.client.collision.CollisionShapes;
import wemppy.bbs_physics.collision.CollisionKind;
import wemppy.bbs_physics.engine.RigMemory;
import wemppy.bbs_physics.forms.FormTreeWalk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A film's recording kept on disk, so that a film opened again — after closing it, after leaving the
 * world, after restarting the game — has its physics at once instead of recording it all over.
 *
 * <p>What is kept is what a scene hands to the next one when BBS rebuilds the cast (see
 * {@link SceneHandover}): the recording, the checkpoints, and one more checkpoint of where the world
 * stood as the scene went, so the scene reading it goes on from there. Kept under
 * {@code <game>/bbs_physics/recordings}, one file per film of a world, written whole beside the old
 * one and moved over it, so a game that dies mid-write leaves the old file or the new one, never half
 * of either.</p>
 *
 * <p><b>Trusted only when nothing it was made from has changed</b>, and that is checked, not
 * assumed: the addon, BBS and the game being the same builds; the model and texture files the
 * film's forms use being the same files; the film saying what it said (see
 * {@link SceneChanges#fingerprint}); the same blocks of the world under it; the same bodies built,
 * channel for channel. And then the scene records the film's first second afresh and compares it
 * with the first second kept, bit for bit, before taking anything — see {@link FilmScene}. A
 * recording that fails any of it is simply not taken, and the film is recorded as it always was.</p>
 */
final class RecordingStore
{
    /** What the file says it is, and which layout of it: a file of another layout is not read. */
    private static final int MAGIC = 0x42425052;
    private static final int FORMAT = 1;

    /** Past this many films, the ones opened longest ago are let go of. */
    private static final int MAX_FILES = 64;

    /** A different value every run where the addon is not a jar — a development run — so that no
     * recording made by one is ever trusted by another built from different sources. */
    private static final String SESSION = UUID.randomUUID().toString();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor((runnable) ->
    {
        Thread thread = new Thread(runnable, "BBS Physics recording writer");

        thread.setDaemon(true);

        return thread;
    });

    private static byte[] environment;

    private RecordingStore()
    {}

    /** A film's recording as kept, and everything it is checked against when read back. */
    record Stored(String film, String world, byte[] environment, byte[] assets, byte[] filmData, byte[] ground,
        double originX, double originY, double originZ, float gravity, float speed, int collisionSteps,
        int bodies, Map<String, Integer> channels, int[] widths, int ticks, float[] floats, boolean full,
        List<SceneCheckpoints.Checkpoint> checkpoints, int standing)
    {}

    /** Keeps {@code stored} on disk, off the game's thread. */
    static void save(Stored stored)
    {
        WRITER.execute(() ->
        {
            try
            {
                write(stored, file(stored.film(), stored.world()));
                prune(folder());
            }
            catch (Throwable e)
            {
                BBSPhysics.LOGGER.warn("A physics recording could not be kept on disk; the film will be recorded again when it is opened.", e);
            }
        });
    }

    /** The recording kept for {@code film} of {@code world}, or null when there is none worth reading. */
    static Stored load(String film, String world)
    {
        Path file = file(film, world);

        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            Stored stored = read(file);

            if (!film.equals(stored.film()) || !world.equals(stored.world()))
            {
                return null;
            }

            /* Opened now: the last to be let go of when there are too many. */
            file.toFile().setLastModified(System.currentTimeMillis());

            return stored;
        }
        catch (StaleFormatException e)
        {
            /* Kept by another version of the addon: of no use to this one, and overwritten next. */
            return null;
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.warn("A physics recording kept on disk could not be read and is ignored: {}", file, e);

            return null;
        }
    }

    static Path folder()
    {
        return FabricLoader.getInstance().getGameDir().resolve(BBSPhysics.MOD_ID).resolve("recordings");
    }

    private static Path file(String film, String world)
    {
        return folder().resolve(new Fingerprint().put(world).put(film).hex(20) + ".bin");
    }

    /** Which world a film belongs to: films are stored per world, and two worlds may share an id. */
    static String worldKey()
    {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client == null ? null : client.getServer();

        if (server != null)
        {
            return "local:" + server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        }

        ServerInfo info = client == null ? null : client.getCurrentServerEntry();

        return info != null ? "server:" + info.address : "unknown";
    }

    /**
     * The builds a recording was made by: the game, BBS and the addon, each by version and by the
     * file it was loaded from. The same version rebuilt is a different file, and may simulate
     * differently; a development run, loaded from folders rather than a file, never matches any
     * other run.
     */
    static synchronized byte[] environment()
    {
        if (environment == null)
        {
            Fingerprint print = new Fingerprint().put(FORMAT).put(SharedConstants.getGameVersion().getName());

            for (String mod : new String[] {BBSPhysics.MOD_ID, "bbs"})
            {
                Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(mod);

                print.put(mod);

                if (container.isEmpty())
                {
                    print.put("absent");

                    continue;
                }

                print.put(container.get().getMetadata().getVersion().getFriendlyString());

                try
                {
                    for (Path path : container.get().getOrigin().getPaths())
                    {
                        File source = path.toFile();

                        if (source.isFile())
                        {
                            print.put(source.getName()).put(source.length()).put(source.lastModified());
                        }
                        else
                        {
                            print.put(SESSION);
                        }
                    }
                }
                catch (Throwable e)
                {
                    print.put(SESSION);
                }
            }

            environment = print.digest();
        }

        return environment;
    }

    /**
     * The files the actors' forms are made from, as they lie on disk: every file of every model the
     * forms use, and every picture they name — a picture can be collision, cut to its painted
     * pixels. By name, size and the time it was last written; a file BBS serves from inside a jar has
     * none of those and stands for itself.
     */
    static byte[] assets(List<Form> roots)
    {
        SortedSet<String> models = new TreeSet<>();
        SortedSet<String> links = new TreeSet<>();

        for (Form root : roots)
        {
            FormTreeWalk.walk(root, (form, path, anchor) ->
            {
                if (form instanceof ModelForm model)
                {
                    models.add(model.model.get());
                }

                collectLinks(form, links);

                return true;
            });
        }

        AssetProvider provider = BBSMod.getProvider();
        Fingerprint print = new Fingerprint();

        for (String model : models)
        {
            List<String> files = new ArrayList<>();

            for (Link link : provider.getLinksFromPath(Link.assets("models/" + model), true))
            {
                files.add(link.toString());
            }

            files.sort(Comparator.naturalOrder());
            print.put("model").put(model).put(files.size());

            for (String file : files)
            {
                file(print, provider, file);
            }
        }

        print.put("pictures").put(links.size());

        for (String link : links)
        {
            file(print, provider, link);
        }

        return print.digest();
    }

    /** Every link a form's values name, its own values only — its parts are walked on their own. */
    private static void collectLinks(BaseValue value, SortedSet<String> links)
    {
        if (value instanceof ValueLink link && link.get() != null)
        {
            links.add(link.get().toString());
        }
        else if (value instanceof ValueLinks many && many.get() != null)
        {
            for (Link link : many.get().values())
            {
                if (link != null)
                {
                    links.add(link.toString());
                }
            }
        }
        else if (value instanceof ValueGroup group)
        {
            for (BaseValue child : group.getAll())
            {
                collectLinks(child, links);
            }
        }
    }

    private static void file(Fingerprint print, AssetProvider provider, String link)
    {
        print.put(link);

        File file = null;

        try
        {
            file = provider.getFile(Link.create(link));
        }
        catch (Throwable e)
        {
            /* Not a link BBS can place on disk: it stands for itself. */
        }

        if (file != null && file.isFile())
        {
            print.put(file.length()).put(file.lastModified());
        }
        else
        {
            print.put("not a file");
        }
    }

    /** Writes {@code stored} beside {@code target} and moves it over, so the file is whole or old. */
    static void write(Stored stored, Path target) throws IOException
    {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");

        Files.createDirectories(target.toAbsolutePath().getParent());

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
            new DeflaterOutputStream(Files.newOutputStream(temporary), deflater, 1 << 16), 1 << 16)))
        {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeUTF(stored.film());
            out.writeUTF(stored.world());
            bytes(out, stored.environment());
            bytes(out, stored.assets());
            bytes(out, stored.filmData());
            bytes(out, stored.ground());
            out.writeDouble(stored.originX());
            out.writeDouble(stored.originY());
            out.writeDouble(stored.originZ());
            out.writeFloat(stored.gravity());
            out.writeFloat(stored.speed());
            out.writeInt(stored.collisionSteps());
            out.writeInt(stored.bodies());
            out.writeInt(stored.channels().size());

            for (Map.Entry<String, Integer> channel : stored.channels().entrySet())
            {
                out.writeUTF(channel.getKey());
                out.writeInt(channel.getValue());
            }

            out.writeInt(stored.widths().length);

            for (int width : stored.widths())
            {
                out.writeInt(width);
            }

            out.writeInt(stored.ticks());
            out.writeInt(stored.floats().length);

            for (float value : stored.floats())
            {
                out.writeFloat(value);
            }

            out.writeBoolean(stored.full());
            out.writeInt(stored.checkpoints().size());

            for (SceneCheckpoints.Checkpoint checkpoint : stored.checkpoints())
            {
                out.writeInt(checkpoint.tick());
                out.writeInt(checkpoint.lostAt());
                bytes(out, checkpoint.world());
                memory(out, checkpoint.rigs());
            }

            out.writeInt(stored.standing());
            out.writeInt(MAGIC);
        }
        finally
        {
            deflater.end();
        }

        try
        {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Stored read(Path file) throws IOException
    {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
            new InflaterInputStream(Files.newInputStream(file)), 1 << 16)))
        {
            if (in.readInt() != MAGIC)
            {
                throw new IOException("not a physics recording");
            }

            if (in.readInt() != FORMAT)
            {
                throw new StaleFormatException();
            }

            String film = in.readUTF();
            String world = in.readUTF();
            byte[] environment = bytes(in);
            byte[] assets = bytes(in);
            byte[] filmData = bytes(in);
            byte[] ground = bytes(in);
            double originX = in.readDouble();
            double originY = in.readDouble();
            double originZ = in.readDouble();
            float gravity = in.readFloat();
            float speed = in.readFloat();
            int collisionSteps = in.readInt();
            int bodies = in.readInt();
            int count = count(in, 1 << 20);
            Map<String, Integer> channels = new LinkedHashMap<>();

            for (int i = 0; i < count; i++)
            {
                channels.put(in.readUTF(), in.readInt());
            }

            int[] widths = new int[count(in, 1 << 20)];

            for (int i = 0; i < widths.length; i++)
            {
                widths[i] = in.readInt();
            }

            int ticks = in.readInt();
            float[] floats = new float[count(in, 1 << 28)];

            for (int i = 0; i < floats.length; i++)
            {
                floats[i] = in.readFloat();
            }

            boolean full = in.readBoolean();
            int checkpointCount = count(in, 1 << 16);
            List<SceneCheckpoints.Checkpoint> checkpoints = new ArrayList<>(checkpointCount);

            for (int i = 0; i < checkpointCount; i++)
            {
                int tick = in.readInt();
                int lostAt = in.readInt();
                byte[] state = bytes(in);

                checkpoints.add(new SceneCheckpoints.Checkpoint(tick, state, memory(in), lostAt));
            }

            int standing = in.readInt();

            if (in.readInt() != MAGIC)
            {
                throw new IOException("the recording does not end where it should");
            }

            return new Stored(film, world, environment, assets, filmData, ground, originX, originY, originZ,
                gravity, speed, collisionSteps, bodies, channels, widths, ticks, floats, full, checkpoints, standing);
        }
    }

    /**
     * A rig's memory: its numbers, and the objects it kept by reference — which are only ever the
     * collision shapes of a body (see {@code BodyRig}). Anything else cannot be written, and the
     * recording is not kept.
     */
    private static void memory(DataOutputStream out, RigMemory memory) throws IOException
    {
        long[] numbers = memory.numbers();
        Object[] kept = memory.keptObjects();

        out.writeInt(numbers.length);

        for (long number : numbers)
        {
            out.writeLong(number);
        }

        out.writeInt(kept.length);

        for (Object object : kept)
        {
            if (!(object instanceof List<?> list))
            {
                throw new IOException("A rig keeps something a recording cannot be written with: " + object);
            }

            out.writeInt(list.size());

            for (Object element : list)
            {
                if (!(element instanceof CollisionShapes.SubShape sub))
                {
                    throw new IOException("A rig keeps something a recording cannot be written with: " + element);
                }

                out.writeInt(sub.kind().ordinal());
                vector(out, sub.half());
                vector(out, sub.offset());
                out.writeFloat(sub.rotation().x);
                out.writeFloat(sub.rotation().y);
                out.writeFloat(sub.rotation().z);
                out.writeFloat(sub.rotation().w);
                out.writeFloat(sub.surface());
            }
        }
    }

    private static RigMemory memory(DataInputStream in) throws IOException
    {
        long[] numbers = new long[count(in, 1 << 26)];

        for (int i = 0; i < numbers.length; i++)
        {
            numbers[i] = in.readLong();
        }

        Object[] kept = new Object[count(in, 1 << 20)];
        CollisionKind[] kinds = CollisionKind.values();

        for (int i = 0; i < kept.length; i++)
        {
            int size = count(in, 1 << 16);
            List<CollisionShapes.SubShape> subs = new ArrayList<>(size);

            for (int j = 0; j < size; j++)
            {
                int kind = in.readInt();

                if (kind < 0 || kind >= kinds.length)
                {
                    throw new IOException("unknown collision kind " + kind);
                }

                Vector3f half = vector(in);
                Vector3f offset = vector(in);
                Quaternionf rotation = new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());

                subs.add(new CollisionShapes.SubShape(kinds[kind], half, offset, rotation, in.readFloat()));
            }

            kept[i] = List.copyOf(subs);
        }

        return RigMemory.of(numbers, kept);
    }

    private static void vector(DataOutputStream out, Vector3f vector) throws IOException
    {
        out.writeFloat(vector.x);
        out.writeFloat(vector.y);
        out.writeFloat(vector.z);
    }

    private static Vector3f vector(DataInputStream in) throws IOException
    {
        return new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
    }

    private static void bytes(DataOutputStream out, byte[] bytes) throws IOException
    {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] bytes(DataInputStream in) throws IOException
    {
        byte[] bytes = new byte[count(in, 1 << 28)];

        in.readFully(bytes);

        return bytes;
    }

    /** A count read back, refused when it could only come from a damaged file. */
    private static int count(DataInputStream in, int most) throws IOException
    {
        int count = in.readInt();

        if (count < 0 || count > most)
        {
            throw new IOException("a count of " + count + " cannot be right");
        }

        return count;
    }

    /** Lets go of the films opened longest ago, past {@link #MAX_FILES}. */
    private static void prune(Path folder) throws IOException
    {
        List<Path> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.bin"))
        {
            for (Path file : stream)
            {
                files.add(file);
            }
        }

        if (files.size() <= MAX_FILES)
        {
            return;
        }

        files.sort(Comparator.comparingLong((Path file) -> file.toFile().lastModified()).reversed());

        for (Path file : files.subList(MAX_FILES, files.size()))
        {
            try
            {
                Files.delete(file);
            }
            catch (NoSuchFileException e)
            {
                /* Gone already. */
            }
        }
    }

    /** A recording kept in another layout of the file — by another version of the addon. */
    static final class StaleFormatException extends IOException
    {
        StaleFormatException()
        {
            super("a recording of another layout");
        }
    }

    /** Whether two digests are the same digest. */
    static boolean same(byte[] a, byte[] b)
    {
        return a != null && b != null && Arrays.equals(a, b);
    }
}
