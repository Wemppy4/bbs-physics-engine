package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SoftBodyMotionProperties;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The rigid stand-in that lets one sheet of cloth land on another.
 *
 * <p><b>Why this exists at all.</b> Jolt does not resolve soft bodies against each other — not
 * slowly, not approximately, not at all. Measured rather than assumed: a free sheet dropped onto a
 * pinned hammock with the layer pair switched on passed straight through it and landed on the
 * floor (ClothSmoke round 5). So "make cloth collide with cloth" cannot be a line in the layer
 * table; something rigid has to stand where the cloth is.</p>
 *
 * <p><b>What it is.</b> A grid of thin kinematic slabs, one per patch of cells, teleported every
 * tick to the middle of the patch it stands for and turned to face the way that patch faces. Other
 * sheets collide with the slabs and drape over them; the sheet that owns them is excused by a
 * group filter, because a sheet that met its own proxies would be inflated from inside by them —
 * seen happening before the filter was added, and it looked exactly like the cloth ballooning.</p>
 *
 * <p><b>What it deliberately does not do.</b> The slabs live in {@link PhysicsLayers#CLOTH_PROXY},
 * which pairs with cloth and nothing else: a proxy must never shove a crate or a bone, or a cape
 * would bat props around with a surface the author never described — those already collide with
 * the sheet itself, properly and softly.</p>
 *
 * <p>Known approximations, both cheap to live with: the slabs follow the sheet by one tick (they
 * are placed from where the vertices ended up last step, which is the only thing known before this
 * one), and a patch is represented by a flat slab, so a sharply creased sheet is met by a
 * coarser surface than it looks. Both get finer as the stride drops, at a body per patch.</p>
 */
public class ClothProxy
{
    /** Cells per slab along each axis. Two keeps the body count a quarter of the cell count. */
    private static final int STRIDE = 2;

    /** How many slabs one sheet may ever build, so a 32×32 sheet cannot flood the world. */
    private static final int MAX_SLABS = 128;

    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    /** Half the slab's thickness, in blocks — thin, but not so thin that contact is missed. */
    private static final float THICKNESS = 0.01F;

    private final int columns;
    private final int rows;
    private final int stride;

    private final int[] bodies;

    /**
     * The corner vertices of each slab's patch: four indices per slab, in the order top-left,
     * top-right, bottom-left, bottom-right.
     */
    private final int[] corners;

    private final GroupFilterTable filter;

    private final Vector3f a = new Vector3f();
    private final Vector3f b = new Vector3f();
    private final Vector3f c = new Vector3f();
    private final Vector3f d = new Vector3f();
    private final Vector3f across = new Vector3f();
    private final Vector3f down = new Vector3f();
    private final Vector3f normal = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();

    private final RVec3 scratchPosition = new RVec3();
    private final Quat scratchRotation = new Quat();

    private ClothProxy(int columns, int rows, int stride, int[] bodies, int[] corners, GroupFilterTable filter)
    {
        this.columns = columns;
        this.rows = rows;
        this.stride = stride;
        this.bodies = bodies;
        this.corners = corners;
        this.filter = filter;
    }

    /**
     * Builds the slabs for a sheet of {@code columns} × {@code rows} vertices.
     *
     * @param group a collision group id no other sheet and no actor in this scene uses — two
     *              bodies only consult a filter when their group ids match, so a shared id would
     *              have one sheet asking another's table about subgroups that mean something else
     *              there
     * @return null when the sheet is too coarse to have a patch at all (a single cell)
     */
    public static ClothProxy build(PhysicsWorld physics, int columns, int rows, int group)
    {
        int stride = STRIDE;
        int slabs = slabCount(columns, rows, stride);

        while (slabs > MAX_SLABS)
        {
            stride += 1;
            slabs = slabCount(columns, rows, stride);
        }

        if (slabs <= 0)
        {
            return null;
        }

        /* Two subgroups: the sheet is 0, its slabs are 1, and that pair is off. Everything else
         * about the sheet is untouched — another sheet has a different group id, never consults
         * this table, and therefore meets these slabs normally. */
        GroupFilterTable filter = new GroupFilterTable(2);

        filter.disableCollision(0, 1);

        int[] bodies = new int[slabs];
        int[] corners = new int[slabs * 4];

        BodyInterface bodyInterface = physics.getBodies();
        int i = 0;

        for (int r = 0; r + stride < rows; r += stride)
        {
            for (int c = 0; c + stride < columns; c += stride)
            {
                corners[i * 4] = r * columns + c;
                corners[i * 4 + 1] = r * columns + c + stride;
                corners[i * 4 + 2] = (r + stride) * columns + c;
                corners[i * 4 + 3] = (r + stride) * columns + c + stride;

                /* Sized in the next update, when the sheet's real spacing is known; built as a
                 * unit slab parked out of the way so nothing meets it before it is placed. */
                BodyCreationSettings settings = new BodyCreationSettings(
                    new BoxShape(0.05F, THICKNESS, 0.05F),
                    new RVec3(0D, -1000D, 0D), Quat.sIdentity(),
                    EMotionType.Kinematic, PhysicsLayers.CLOTH_PROXY);

                settings.setCollisionGroup(new CollisionGroup(filter, group, 1));

                bodies[i] = bodyInterface.createAndAddBody(settings, EActivation.Activate);

                i += 1;
            }
        }

        return new ClothProxy(columns, rows, stride, bodies, corners, filter);
    }

    private static int slabCount(int columns, int rows, int stride)
    {
        int across = 0;
        int down = 0;

        for (int c = 0; c + stride < columns; c += stride)
        {
            across += 1;
        }

        for (int r = 0; r + stride < rows; r += stride)
        {
            down += 1;
        }

        return across * down;
    }

    /** What the sheet itself must carry so that it is excused from its own slabs. */
    public CollisionGroup sheetGroup(int group)
    {
        return new CollisionGroup(this.filter, group, 0);
    }

    /**
     * Stands every slab on the patch it represents, for the step about to run. Teleported rather
     * than steered: {@code moveKinematic} sets a velocity that would carry a slab on for as many
     * steps as follow, and a proxy has no business travelling anywhere of its own accord.
     */
    public void update(PhysicsWorld physics, SoftBodyMotionProperties motion)
    {
        BodyInterface bodyInterface = physics.getBodies();

        for (int i = 0; i < this.bodies.length; i++)
        {
            read(motion, this.corners[i * 4], this.a);
            read(motion, this.corners[i * 4 + 1], this.b);
            read(motion, this.corners[i * 4 + 2], this.c);
            read(motion, this.corners[i * 4 + 3], this.d);

            if (!this.a.isFinite() || !this.b.isFinite() || !this.c.isFinite() || !this.d.isFinite())
            {
                /* A sheet the solver has lost takes its proxies out of the way rather than to a
                 * place that is not a place. */
                continue;
            }

            /* The patch's own axes: across its top, down its side. Their cross is which way it
             * faces, and the slab is turned to match — a draped sheet is met by surfaces that lie
             * along it rather than by a floor of flat tiles. */
            this.across.set(this.b).sub(this.a).add(this.d).sub(this.c).mul(0.5F);
            this.down.set(this.c).sub(this.a).add(this.d).sub(this.b).mul(0.5F);
            this.across.cross(this.down, this.normal);

            if (this.normal.lengthSquared() < 1e-12F)
            {
                /* A patch folded flat onto itself has no facing to speak of. */
                continue;
            }

            this.normal.normalize();
            this.rotation.identity().rotateTo(0F, 1F, 0F, this.normal.x, this.normal.y, this.normal.z);

            this.scratchPosition.set(
                (this.a.x + this.b.x + this.c.x + this.d.x) / 4F,
                (this.a.y + this.b.y + this.c.y + this.d.y) / 4F,
                (this.a.z + this.b.z + this.c.z + this.d.z) / 4F);
            this.scratchRotation.set(this.rotation.x, this.rotation.y, this.rotation.z, this.rotation.w);

            bodyInterface.setPositionAndRotation(this.bodies[i], this.scratchPosition, this.scratchRotation, EActivation.Activate);
            bodyInterface.setLinearAndAngularVelocity(this.bodies[i], ZERO, ZERO);
        }
    }

    /**
     * Sizes the slabs to the sheet they stand for. Called once the sheet's spacing is known — the
     * patch is {@code stride} cells across, and a slab that covered less would leave gaps another
     * sheet could slip through.
     */
    public void resize(PhysicsWorld physics, float spacingX, float spacingY)
    {
        BoxShape shape = new BoxShape(
            Math.max(this.stride * spacingX / 2F, 0.001F),
            THICKNESS,
            Math.max(this.stride * spacingY / 2F, 0.001F));

        for (int body : this.bodies)
        {
            physics.getBodies().setShape(body, shape, false, EActivation.Activate);
        }
    }

    public int getCount()
    {
        return this.bodies.length;
    }

    private static void read(SoftBodyMotionProperties motion, int vertex, Vector3f out)
    {
        Vec3 position = motion.getVertex(vertex).getPosition();

        out.set(position.getX(), position.getY(), position.getZ());
    }
}
