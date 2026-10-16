package wemppy.bbs_physics.engine;

import com.github.stephengold.joltjni.JoltPhysicsObject;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.readonly.ConstShape;

/**
 * Letting go of the Jolt objects the addon makes.
 *
 * <p>Every shape, settings object, result and lock jolt-jni hands out holds native memory, and lets
 * go of it only when closed: nothing on the native side is garbage collected, and jolt-jni's
 * optional cleaner is not running. Unclosed, each held on to its memory for as long as the game
 * ran — a scene's world of blocks, megabytes of it; every collider of every body; every settings
 * object a body or a joint was made from — on every build of a scene, and on every body reshaped as
 * its parts moved, which can be every tick.</p>
 *
 * <p>Letting go is safe the moment Jolt has what it needs. A body keeps its own hold on its shape, a
 * shape on the shapes it is built from, the physics system on its constraints, a collision group on
 * its filter: jolt-jni's handle on such an object is one counted reference among them, and closing
 * it drops only that one. A settings object is read when the body or joint is made, and never
 * again.</p>
 *
 * <p>Some of jolt-jni's getters hand out a handle of their own, which has to be let go of as well:
 * {@code Body.getShape()} wraps the body's shape in a new co-owning handle on every call, and so
 * does {@code ShapeResult.get()} inside the reference it returns (see {@link #take}).</p>
 */
public final class JoltRelease
{
    private JoltRelease()
    {}

    /**
     * The shape a successful result holds, with one hold on it — the one handed out — and the result
     * let go of. {@code result.get()} returns a reference that wraps a second, co-owning handle of
     * the same shape, and closing the reference lets go of the first only: the second is the one
     * kept here, the reference is closed.
     */
    public static ConstShape take(ShapeResult result)
    {
        ShapeRefC reference = result.get();
        ConstShape shape = reference.getPtr();

        release(reference, result);

        return shape;
    }

    /** Lets go of the addon's own hold on each of {@code objects}; anything else is passed over. */
    public static void release(Object... objects)
    {
        for (Object object : objects)
        {
            if (object instanceof JoltPhysicsObject jolt)
            {
                jolt.close();
            }
        }
    }
}
