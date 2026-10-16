package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.DecoratedShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.readonly.ConstShape;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import net.minecraft.util.math.Box;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.client.collision.CollisionShapes;
import wemppy.bbs_physics.client.collision.JoltShapes;
import wemppy.bbs_physics.collision.CollisionKind;
import wemppy.bbs_physics.engine.JoltRelease;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsWorld;

import java.util.List;

/**
 * Native memory, against real Jolt: a shape the addon builds is held by exactly what needs it — the
 * one handle given out, then the body it was given to — and nothing is left holding it once the
 * world is gone. Counted, not guessed: every shape carries the number of holds on it. And a scene's
 * worth of building and letting go, many times over, without a double free.
 */
public final class NativeReleaseCheck
{
    public static void main(String[] args) throws Exception
    {
        BBSSettings.recordingTransformOverlays = new ValueInt("overlays", 0);
        StructureDestructionCheck.loadJolt();

        compound();
        placed();
        structure();
        again();

        System.out.println("NativeReleaseCheck passed: compound and placed shapes held only by their handle, then by their body, then by nobody; structure blocks; repeated builds.");
    }

    /** A compound: one hold once built, the body's once given, none once the world is gone. */
    private static void compound()
    {
        ConstShape shape = JoltShapes.build(List.of(box(0F), box(0.5F)));

        require(shape.getRefCount() == 1, "A compound built holds only the handle given out, not " + shape.getRefCount());

        ShapeRefC watch = shape.toRefC();

        try (PhysicsWorld world = new PhysicsWorld())
        {
            body(world, shape);
            JoltRelease.release(shape);
            require(watch.getRefCount() == 2, "Given to a body and let go of, a compound is held by the body alone (and this check), not " + watch.getRefCount());
        }

        require(watch.getRefCount() == 1, "Once the world is gone, nothing holds the compound but this check, not " + watch.getRefCount());
        JoltRelease.release(watch);
    }

    /** One placed shape: the placement holds the leaf, and only the placement. */
    private static void placed()
    {
        ConstShape shape = JoltShapes.build(List.of(box(0.25F)));

        require(shape.getRefCount() == 1, "A placed shape built holds only the handle given out");

        /* Asking for the leaf hands out a handle of its own, one more hold for as long as it is kept. */
        ConstShape leaf = ((DecoratedShape) shape).getInnerShape();

        require(leaf.getRefCount() == 2, "Its leaf is held by the placement alone (and the handle just asked for), not " + leaf.getRefCount());
        JoltRelease.release(leaf);

        ShapeRefC watch = shape.toRefC();

        try (PhysicsWorld world = new PhysicsWorld())
        {
            body(world, shape);
            JoltRelease.release(shape);
        }

        require(watch.getRefCount() == 1, "Once the world is gone, nothing holds the placed shape but this check");
        JoltRelease.release(watch);
    }

    /** A block of a destructible structure: the same, through its own builder. */
    private static void structure()
    {
        ConstShape shape = StructureRig.shape(List.of(new Box(0, 0, 0, 1, 0.5, 1), new Box(0, 0.5, 0, 0.5, 1, 0.5)), new Vector3f(1F));

        require(shape.getRefCount() == 1, "A structure block built holds only the handle given out, not " + shape.getRefCount());

        ShapeRefC watch = shape.toRefC();

        try (PhysicsWorld world = new PhysicsWorld())
        {
            body(world, shape);
            JoltRelease.release(shape);
        }

        require(watch.getRefCount() == 1, "Once the world is gone, nothing holds a structure block but this check");
        JoltRelease.release(watch);
    }

    /** Scenes' worth of building and letting go, and letting go twice: nothing falls over. */
    private static void again()
    {
        for (int round = 0; round < 200; round++)
        {
            try (PhysicsWorld world = new PhysicsWorld())
            {
                for (int i = 0; i < 8; i++)
                {
                    ConstShape shape = JoltShapes.build(List.of(box(i * 0.1F), box(1F)));

                    body(world, shape);
                    JoltRelease.release(shape);
                    JoltRelease.release(shape);
                }

                world.step();
            }
        }
    }

    private static void body(PhysicsWorld world, ConstShape shape)
    {
        BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(0D, 2D, 0D), Quat.sIdentity(),
            EMotionType.Dynamic, PhysicsLayers.MOVING);

        world.getBodies().createAndAddBody(settings, EActivation.Activate);
        JoltRelease.release(settings);
    }

    private static CollisionShapes.SubShape box(float x)
    {
        return new CollisionShapes.SubShape(CollisionKind.BOX, new Vector3f(0.2F, 0.2F, 0.2F), new Vector3f(x, 0F, 0F), new Quaternionf(), 0F);
    }

    private static void require(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }
}
