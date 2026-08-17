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
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the blocks around a scene into a single static body, so physics lands on the world the
 * film is actually shot in instead of on an invented floor.
 *
 * <p>Built once, from a box of blocks around <em>every place the film keeps something simulated</em>
 * — the scene's origin, and the starting spot of each actor whose tree carries any physics. One
 * area was not enough, and that was found the hard way rather than reasoned about: a balloon
 * placed ninety blocks from the film's first actor fell through the world, because the world had
 * only ever been collected around that first actor — from the viewport, indistinguishable from
 * collision being broken. Overlapping areas are collected once; the areas are still fixed at
 * assembly, from tick-0 positions, because rebuilding collision every time a body moves would cost
 * far more than it buys.</p>
 *
 * <p>Blocks with every neighbour a full cube are skipped. Nothing can ever touch them, and in a
 * region this size they are the overwhelming majority — everything under the surface. Skipping
 * them is the difference between a few thousand boxes and a few tens of thousands.</p>
 */
public final class WorldCollider
{
    /**
     * The regions of the world a scene collected its collision from, and how many boxes came out.
     * Each area is the block its region is centred on, in the scene's own coordinates.
     *
     * <p>Kept by the scene rather than recomputed from the settings, because the settings can be
     * changed while a film is open and the region a body is being judged against has to be the one
     * that was actually built.</p>
     */
    public record Window(int radius, int below, int above, int boxes, List<int[]> areas)
    {
        /**
         * Whether a point in the scene's own coordinates still has blocks under it. Outside every
         * region there is nothing to land on and a body falls forever, which from the viewport is
         * indistinguishable from a body that fell through the floor — telling the two apart is the
         * whole reason this is asked. Approximate at the world's ceiling and bedrock, where a
         * region is clamped to what exists; a distinction that does not matter to the question.
         */
        public boolean contains(double x, double y, double z)
        {
            for (int[] area : this.areas)
            {
                if (x >= area[0] - this.radius && x <= area[0] + this.radius + 1
                    && z >= area[2] - this.radius && z <= area[2] + this.radius + 1
                    && y >= area[1] - this.below && y <= area[1] + this.above + 1)
                {
                    return true;
                }
            }

            return false;
        }
    }

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
     * Adds the world's collision around {@code centers} to the scene — the first centre is the
     * scene's origin and is always collected; the rest are the starting spots of whatever else the
     * film simulates. A centre already covered by an earlier region, with a quarter-radius to
     * spare, brings nothing of its own and is skipped; a block two overlapping regions both reach
     * is collected once.
     *
     * @return the regions that were collected, with the number of boxes in them — zero boxes
     *         meaning there was nothing solid to add and no body was created. Deliberately not the
     *         body's id: Jolt hands out ids from zero, and the world is the first body a scene
     *         creates, so "0" would be both the id it gets and the answer meaning "nothing" — and
     *         the scene would lay its fallback floor through the real ground
     */
    public static Window build(PhysicsWorld physics, World world, double originX, double originY, double originZ, List<double[]> centers)
    {
        int radius = BBSPhysicsSettings.worldRadius.get();
        int below = BBSPhysicsSettings.worldBelow.get();
        int above = BBSPhysicsSettings.worldAbove.get();

        List<int[]> areas = new ArrayList<>(1);

        if (world == null)
        {
            return new Window(radius, below, above, 0, areas);
        }

        /* Which centres deserve a region of their own: [baseX, baseY, baseZ, minY, maxY] each. */
        List<int[]> accepted = new ArrayList<>(1);

        for (double[] center : centers)
        {
            int baseX = (int) Math.floor(center[0]);
            int baseY = (int) Math.floor(center[1]);
            int baseZ = (int) Math.floor(center[2]);

            if (isCovered(accepted, baseX, baseY, baseZ, radius, below, above))
            {
                continue;
            }

            int minY = Math.max(baseY - below, world.getBottomY());
            int maxY = Math.min(baseY + above, world.getTopY() - 1);

            if (minY <= maxY)
            {
                accepted.add(new int[] {baseX, baseY, baseZ, minY, maxY});
            }
        }

        if (accepted.isEmpty())
        {
            return new Window(radius, below, above, 0, areas);
        }

        StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        int boxes = 0;

        for (int at = 0; at < accepted.size(); at++)
        {
            int[] area = accepted.get(at);
            int baseX = area[0];
            int baseZ = area[2];
            int minY = area[3];
            int maxY = area[4];

            int spanX = radius * 2 + 1;
            int spanY = maxY - minY + 1;
            int spanZ = radius * 2 + 1;

            /* Which blocks of the region are full cubes, measured once. The burial test asks about
             * six neighbours per block, and asking the world for them would re-read and re-shape
             * most of the region six times over — a million lookups for a region this size, every
             * time a scene is built, which in the editor is every change to the cast. */
            boolean[] full = new boolean[spanX * spanY * spanZ];

            for (int y = minY; y <= maxY; y++)
            {
                for (int x = baseX - radius; x <= baseX + radius; x++)
                {
                    for (int z = baseZ - radius; z <= baseZ + radius; z++)
                    {
                        pos.set(x, y, z);

                        BlockState state = world.getBlockState(pos);

                        if (state.isAir())
                        {
                            continue;
                        }

                        full[index(x - baseX, y - minY, z - baseZ, spanX, spanZ, radius)] = Block.isShapeFullCube(state.getCollisionShape(world, pos));
                    }
                }
            }

            for (int y = minY; y <= maxY; y++)
            {
                for (int x = baseX - radius; x <= baseX + radius; x++)
                {
                    for (int z = baseZ - radius; z <= baseZ + radius; z++)
                    {
                        /* An earlier region already collected this block: once is enough. */
                        if (claimedEarlier(accepted, at, x, y, z, radius))
                        {
                            continue;
                        }

                        if (full[index(x - baseX, y - minY, z - baseZ, spanX, spanZ, radius)]
                            && isBuried(world, probe, full, baseX, minY, baseZ, x, y, z, spanX, spanY, spanZ, radius))
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

            areas.add(new int[] {baseX - (int) Math.floor(originX), area[1] - (int) Math.floor(originY), baseZ - (int) Math.floor(originZ)});
        }

        if (boxes == 0)
        {
            return new Window(radius, below, above, 0, areas);
        }

        ShapeResult result = compound.create();

        if (result.hasError())
        {
            BBSPhysics.LOGGER.error("Could not build world collision for a physics scene: {}", result.getError());

            return new Window(radius, below, above, 0, new ArrayList<>(0));
        }

        /* One body at the scene's origin; every block sits inside it as a child shape. */
        BodyCreationSettings settings = new BodyCreationSettings(result.get(), new RVec3(0D, 0D, 0D), Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC);

        settings.setFriction(0.6F);

        physics.getBodies().createAndAddBody(settings, EActivation.DontActivate);

        int[] first = accepted.get(0);

        BBSPhysics.LOGGER.info("World collision built from {} boxes in {} area(s), the first around ({}, {}, {}).",
            boxes, accepted.size(), first[0], first[1], first[2]);

        return new Window(radius, below, above, boxes, areas);
    }

    /**
     * Whether this centre sits inside an already-accepted region with a quarter of the radius to
     * spare — close enough that a region of its own would be almost entirely the same blocks. The
     * margin is what keeps something standing near a region's edge from losing the ground just
     * beyond it.
     */
    private static boolean isCovered(List<int[]> accepted, int baseX, int baseY, int baseZ, int radius, int below, int above)
    {
        int margin = radius / 4;

        for (int[] area : accepted)
        {
            if (Math.abs(baseX - area[0]) <= radius - margin
                && Math.abs(baseZ - area[2]) <= radius - margin
                && baseY - area[1] >= -(below - below / 4)
                && baseY - area[1] <= above - above / 4)
            {
                return true;
            }
        }

        return false;
    }

    /** Whether a block of region {@code at} was already collected by an earlier region. */
    private static boolean claimedEarlier(List<int[]> accepted, int at, int x, int y, int z, int radius)
    {
        for (int i = 0; i < at; i++)
        {
            int[] area = accepted.get(i);

            if (Math.abs(x - area[0]) <= radius && Math.abs(z - area[2]) <= radius && y >= area[3] && y <= area[4])
            {
                return true;
            }
        }

        return false;
    }

    private static int index(int x, int y, int z, int spanX, int spanZ, int radius)
    {
        return ((y * spanX) + (x + radius)) * spanZ + (z + radius);
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
    private static boolean isBuried(World world, BlockPos.Mutable probe, boolean[] full, int baseX, int minY, int baseZ, int x, int y, int z, int spanX, int spanY, int spanZ, int radius)
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

            if (lx < -radius || lx > radius || lz < -radius || lz > radius || ly < 0 || ly >= spanY)
            {
                probe.set(nx, ny, nz);

                neighbour = Block.isShapeFullCube(world.getBlockState(probe).getCollisionShape(world, probe));
            }
            else
            {
                neighbour = full[index(lx, ly, lz, spanX, spanZ, radius)];
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
