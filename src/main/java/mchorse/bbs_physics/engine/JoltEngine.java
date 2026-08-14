package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.Jolt;
import mchorse.bbs_physics.BBSPhysics;

/**
 * The process-wide Jolt context: the native library, the object factory and the registered shape
 * types. All of it is global state inside Jolt itself, so it is set up once and never torn down —
 * the game keeps running until it exits, and there is nothing to gain by giving it back earlier.
 *
 * <p>Physics worlds are built on top of this per model; this class only answers whether there is
 * anything to build them on.</p>
 *
 * <p>Failure is a normal outcome here, not a crash: an exotic platform, a disk that can't be
 * written to, a security policy that forbids loading libraries. In all of those the addon reports
 * it once and marks itself unavailable, and BBS goes on animating without physics — a missing
 * feature is a far better outcome than a game that won't start.</p>
 */
public final class JoltEngine
{
    private static boolean attempted;
    private static boolean available;

    private JoltEngine()
    {}

    /**
     * Whether Jolt is usable, initializing it on the first call.
     *
     * <p>Everything that touches Jolt has to ask this first — the bindings answer a call made
     * before {@link Jolt#registerTypes()} by crashing the JVM, not by throwing.</p>
     */
    public static boolean available()
    {
        if (!attempted)
        {
            attempted = true;

            try
            {
                initialize();

                available = true;
            }
            catch (Throwable e)
            {
                BBSPhysics.LOGGER.error("Jolt failed to start, so physics is unavailable!", e);
            }
        }

        return available;
    }

    private static void initialize() throws Exception
    {
        JoltNatives.load();

        Jolt.registerDefaultAllocator();

        /* Jolt reports its own assertion failures and diagnostics through callbacks that are
         * empty until something is installed into them. The stock ones print, which is exactly
         * what is wanted: an assertion inside the engine is otherwise silent. */
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();

        if (!Jolt.newFactory())
        {
            throw new IllegalStateException("Jolt refused to create its object factory.");
        }

        /* Teaches the factory every shape and constraint type Jolt ships with. Until this runs,
         * creating any of them is undefined behaviour down in the native code. */
        Jolt.registerTypes();

        BBSPhysics.LOGGER.info("Jolt Physics {} ({}) is up.", Jolt.versionString(), Jolt.buildType());
    }
}
