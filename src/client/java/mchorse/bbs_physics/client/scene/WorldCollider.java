package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.List;

/**
 * Turns the blocks around a scene into a single static body, so physics lands on the world the
 * film is actually shot in instead of on an invented floor.
 *
 * <p>Built once, from a box of blocks around the scene's origin. That is a deliberate limit rather
 * than an oversight: a film is shot in one place, and rebuilding collision every time a body moves
 * would cost far more than it buys. When a scene needs to travel, this is what gets a moving window
 * — but not before there is a scene that travels.</p>
 *
 * <p>Blocks with every neighbour a full cube are skipped. Nothing can ever touch them, and in a
 * region this size they are the overwhelming majority — everything under the surface. Skipping
 * them is the difference between a few thousand boxes and a few tens of thousands.</p>
 */
public final class WorldCollider
{
    /**
     * Horizontal reach around the origin, in blocks. Sized for a thrown object: at a hard throw's
     * ~20 blocks a second this is over a second and a half of flight before the edge. A moving
     * window is deliberately NOT attempted — swapping world bodies changes the body set, and Jolt
     * refuses to restore a checkpoint whose bodies no longer match, which would break every rewind.
     */
    private static final int RADIUS = 32;

    /** How far down to look — enough for the ground the scene stands on and a drop off a ledge. */
    private static final int BELOW = 12;

    /** And up, for ceilings, overhangs and anything dropped from a height. */
    private static final int ABOVE = 24;

    /**
     * Jolt rounds a box's corners by this much to make contacts cheaper, and the radius may not
     * exceed the box's smallest half extent — thin blocks like pressure plates are thinner than
     * the default, so it is clamped per shape.
     */
    private static final float CONVEX_RADIUS = 0.05F;

    /** Thinner than this and the box is not worth handing to the solver. */
    private static final float MIN_HALF_EXTENT = 0.005F;

    private WorldCollider()
    {}

    /**
     * Adds the world's collision around {@code origin} to the scene.
     *
     * @return how many boxes went in; zero means there was nothing solid to add and no body was
     *         created. Deliberately not the body's id: Jolt hands out ids from zero, and the world
     *         is the first body a scene creates, so "0" would be both the id it gets and the
     *         answer meaning "nothing" — and the scene would lay its fallback floor through the
     *         real ground
     */
    public static int build(PhysicsWorld physics, World world, double originX, double originY, double originZ)
    {
        if (world == null)
        {
            return 0;
        }

        StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        int baseX = (int) Math.floor(originX);
        int baseY = (int) Math.floor(originY);
        int baseZ = (int) Math.floor(originZ);

        int minY = Math.max(baseY - BELOW, world.getBottomY());
        int maxY = Math.min(baseY + ABOVE, world.getTopY() - 1);

        if (minY > maxY)
        {
            return 0;
        }

        int spanX = RADIUS * 2 + 1;
        int spanY = maxY - minY + 1;
        int spanZ = RADIUS * 2 + 1;

        /* Which blocks of the region are full cubes, measured once. The burial test asks about six
         * neighbours per block, and asking the world for them would re-read and re-shape most of
         * the region six times over — a million lookups for a region this size, every time a scene
         * is built, which in the editor is every change to the cast. */
        boolean[] full = new boolean[spanX * spanY * spanZ];
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int boxes = 0;

        for (int y = minY; y <= maxY; y++)
        {
            for (int x = baseX - RADIUS; x <= baseX + RADIUS; x++)
            {
                for (int z = baseZ - RADIUS; z <= baseZ + RADIUS; z++)
                {
                    pos.set(x, y, z);

                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    full[index(x - baseX, y - minY, z - baseZ, spanX, spanZ)] = Block.isShapeFullCube(state.getCollisionShape(world, pos));
                }
            }
        }

        for (int y = minY; y <= maxY; y++)
        {
            for (int x = baseX - RADIUS; x <= baseX + RADIUS; x++)
            {
                for (int z = baseZ - RADIUS; z <= baseZ + RADIUS; z++)
                {
                    if (full[index(x - baseX, y - minY, z - baseZ, spanX, spanZ)]
                        && isBuried(world, probe, full, baseX, minY, baseZ, x, y, z, spanX, spanY, spanZ))
                    {
                        continue;
                    }

                    pos.set(x, y, z);

                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    VoxelShape shape = state.getCollisionShape(world, pos);

                    if (shape.isEmpty())
                    {
                        continue;
                    }

                    List<Box> parts = shape.getBoundingBoxes();

                    for (Box box : parts)
                    {
                        if (addBox(compound, box, x - originX, y - originY, z - originZ))
                        {
                            boxes += 1;
                        }
                    }
                }
            }
        }

        if (boxes == 0)
        {
            return 0;
        }

        ShapeResult result = compound.create();

        if (result.hasError())
        {
            BBSPhysics.LOGGER.error("Could not build world collision for a physics scene: {}", result.getError());

            return 0;
        }

        /* One body at the scene's origin; every block sits inside it as a child shape. */
        BodyCreationSettings settings = new BodyCreationSettings(result.get(), new RVec3(0D, 0D, 0D), Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC);

        settings.setFriction(0.6F);

        physics.getBodies().createAndAddBody(settings, EActivation.DontActivate);

        BBSPhysics.LOGGER.info("World collision built from {} boxes around ({}, {}, {}).", boxes, baseX, baseY, baseZ);

        return boxes;
    }

    private static int index(int x, int y, int z, int spanX, int spanZ)
    {
        return ((y * spanX) + (x + RADIUS)) * spanZ + (z + RADIUS);
    }

    /**
     * Whether every side of this block is covered by a full cube, which makes it unreachable and
     * so not worth simulating. Checked only for blocks that are full cubes themselves — a slab or
     * a fence is part of the surface however it is surrounded.
     *
     * <p>Answered from the measured grid, and only from the world for a neighbour just outside it
     * — the region's own outer shell, a few per cent of it. Treating those as open instead would
     * wall the whole region in boxes nothing can ever reach.</p>
     */
    private static boolean isBuried(World world, BlockPos.Mutable probe, boolean[] full, int baseX, int minY, int baseZ, int x, int y, int z, int spanX, int spanY, int spanZ)
    {
        for (Direction direction : Direction.values())
        {
            int nx = x + direction.getOffsetX();
            int ny = y + direction.getOffsetY();
            int nz = z + direction.getOffsetZ();

            int lx = nx - baseX;
            int ly = ny - minY;
            int lz = nz - baseZ;

            boolean neighbour;

            if (lx < -RADIUS || lx > RADIUS || lz < -RADIUS || lz > RADIUS || ly < 0 || ly >= spanY)
            {
                probe.set(nx, ny, nz);

                neighbour = Block.isShapeFullCube(world.getBlockState(probe).getCollisionShape(world, probe));
            }
            else
            {
                neighbour = full[index(lx, ly, lz, spanX, spanZ)];
            }

            if (!neighbour)
            {
                return false;
            }
        }

        return true;
    }

    private static boolean addBox(StaticCompoundShapeSettings compound, Box box, double blockX, double blockY, double blockZ)
    {
        float halfX = (float) (box.maxX - box.minX) * 0.5F;
        float halfY = (float) (box.maxY - box.minY) * 0.5F;
        float halfZ = (float) (box.maxZ - box.minZ) * 0.5F;

        if (halfX < MIN_HALF_EXTENT || halfY < MIN_HALF_EXTENT || halfZ < MIN_HALF_EXTENT)
        {
            return false;
        }

        float radius = Math.min(CONVEX_RADIUS, Math.min(halfX, Math.min(halfY, halfZ)));

        float centerX = (float) (blockX + (box.minX + box.maxX) * 0.5D);
        float centerY = (float) (blockY + (box.minY + box.maxY) * 0.5D);
        float centerZ = (float) (blockZ + (box.minZ + box.maxZ) * 0.5D);

        compound.addShape(new Vec3(centerX, centerY, centerZ), Quat.sIdentity(), new BoxShape(new Vec3(halfX, halfY, halfZ), radius));

        return true;
    }
}
