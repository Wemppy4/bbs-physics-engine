package mchorse.bbs_physics.engine;

import mchorse.bbs_physics.BBSPhysics;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Puts jolt-jni's native library where the JVM can load it, and loads it.
 *
 * <p>The library ships inside the mod jar, one copy per platform, and a nested jar is not a file
 * the operating system can open — so the right one has to be written to disk first. It lands in
 * {@code <game>/bbs_physics/natives} under a name carrying a hash of its content, which makes the
 * write a one-off: the same build finds its file already there on every later launch, and a new
 * build of Jolt simply gets a new name instead of overwriting one that another copy of the game
 * may have open.</p>
 */
final class JoltNatives
{
    private JoltNatives()
    {}

    /**
     * Loads the native library for the platform the game is running on.
     *
     * @throws IOException                   if the library cannot be read out of the jar or written to disk
     * @throws UnsupportedOperationException if the addon carries no library for this platform
     */
    static void load() throws IOException
    {
        String directory = directory();
        String name = name();
        String resource = directory + "/com/github/stephengold/" + name;
        byte[] library = read(resource);

        Path file = extract(library, hash(library) + "-" + name);

        System.load(file.toAbsolutePath().toString());

        BBSPhysics.LOGGER.info("Loaded the Jolt native library for {} from {}.", directory, file);
    }

    /**
     * The directory jolt-jni keeps this platform's library under, as {@code <system>/<architecture>}.
     *
     * <p>Unknown systems and architectures are refused rather than guessed at: loading a library
     * built for something else fails inside the JVM's linker, where the reason is far less obvious
     * than it is here.</p>
     */
    private static String directory()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String system;

        if (os.contains("win"))
        {
            system = "windows";
        }
        else if (os.contains("mac") || os.contains("darwin"))
        {
            system = "osx";
        }
        else if (os.contains("nux") || os.contains("nix"))
        {
            system = "linux";
        }
        else
        {
            throw new UnsupportedOperationException("There is no Jolt library for the \"" + os + "\" system.");
        }

        String cpu = switch (architecture)
        {
            case "amd64", "x86_64", "x86-64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsupportedOperationException("There is no Jolt library for the \"" + architecture + "\" architecture.");
        };

        return system + "/" + cpu;
    }

    private static String name()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win"))
        {
            return "joltjni.dll";
        }

        return os.contains("mac") || os.contains("darwin") ? "libjoltjni.dylib" : "libjoltjni.so";
    }

    private static byte[] read(String resource) throws IOException
    {
        /* The addon's own class loader rather than the system one: under Fabric the library
         * arrives in a jar nested inside the mod jar, which only the mod's loader can see. */
        try (InputStream stream = JoltNatives.class.getClassLoader().getResourceAsStream(resource))
        {
            if (stream == null)
            {
                throw new IOException("The Jolt library \"" + resource + "\" is missing from the addon.");
            }

            return stream.readAllBytes();
        }
    }

    private static Path extract(byte[] library, String name) throws IOException
    {
        Path directory = FabricLoader.getInstance().getGameDir().resolve(BBSPhysics.MOD_ID).resolve("natives");
        Path file = directory.resolve(name);

        if (Files.isRegularFile(file) && Files.size(file) == library.length)
        {
            return file;
        }

        Files.createDirectories(directory);

        /* Written beside the target and then moved onto it, so a half-written library is never
         * there to be loaded: two copies of the game starting at once is an ordinary thing, and
         * one of them reading what the other is still writing is not a race worth taking. */
        Path partial = Files.createTempFile(directory, name, ".part");

        try
        {
            Files.write(partial, library);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            /* Another copy of the game has this library open and the system won't let it be
             * replaced — Windows locks a loaded one. That is not a problem: the name carries a
             * hash of the content, so a file of the right size under this name IS this library. */
            if (!Files.isRegularFile(file) || Files.size(file) != library.length)
            {
                throw e;
            }
        }
        finally
        {
            Files.deleteIfExists(partial);
        }

        return file;
    }

    private static String hash(byte[] library)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(library);
            byte[] head = new byte[8];

            System.arraycopy(digest, 0, head, 0, head.length);

            return HexFormat.of().formatHex(head);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is missing from this Java runtime.", e);
        }
    }
}
