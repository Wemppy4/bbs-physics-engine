package wemppy.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.Face;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SoftBodyCreationSettings;
import com.github.stephengold.joltjni.SoftBodyMotionProperties;
import com.github.stephengold.joltjni.SoftBodySharedSettings;
import com.github.stephengold.joltjni.SoftBodyVertex;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.Vertex;
import com.github.stephengold.joltjni.VertexAttributes;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EBendType;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import wemppy.bbs_physics.cloth.ClothEdge;
import wemppy.bbs_physics.cloth.ClothForm;
import wemppy.bbs_physics.cloth.ClothState;
import wemppy.bbs_physics.engine.PhysicsLayers;
import wemppy.bbs_physics.engine.PhysicsMath;
import wemppy.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Ties one cloth form to its soft body in the simulation. Everything about driving it, recording it
 * and handing it back lives in {@link SoftBodyRig}; what is here is what makes it cloth — a grid of
 * vertices, an edge the author holds, and the rigid stand-ins other sheets land on.
 *
 * <p>Known limit, named rather than hidden: the sheet's constitution — size, resolution, held edge,
 * stiffness — is baked into the soft body when the scene is assembled, so those edits take effect
 * when the form editor is closed (which rebuilds the cast), not live.</p>
 */
public class ClothRig extends SoftBodyRig
{
    private final ClothForm cloth;

    private final int columns;

    /** Which vertices are held, precomputed — asked per vertex per tick. */
    private final boolean[] held;

    /** The free vertex's inverse mass, restored when the scene resets a released sheet. */
    private final float freeInvMass;

    /**
     * The rigid stand-ins other sheets land on, or null when this sheet was not asked to collide
     * with cloth. See {@link ClothProxy} for why sheet-on-sheet cannot be a layer pair.
     */
    private final ClothProxy proxy;

    private ClothRig(ClothForm form, String path, int bodyId, int channel, boolean[] held, float freeInvMass, SoftBodyMotionProperties motion, ClothProxy proxy, String anchor)
    {
        super(form, path, bodyId, channel, form.getColumns() * form.getRows(), motion, anchor);

        this.cloth = form;
        this.columns = form.getColumns();
        this.held = held;
        this.freeInvMass = freeInvMass;
        this.proxy = proxy;
    }

    /**
     * Builds the soft body for a cloth form found at {@code path} in an actor's tree. Null when the
     * pose has no frame for that path — the scene will be rebuilt when the cast changes.
     */
    public static ClothRig build(PhysicsWorld physics, ClothForm form, String path, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, int group, String anchor)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        Matrix4f formWorld = new Matrix4f(actorWorld).mul(entry.matrix());

        int columns = form.getColumns();
        int rows = form.getRows();
        ClothEdge edge = form.getEdge();

        boolean[] held = new boolean[columns * rows];

        int free = 0;

        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < columns; c++)
            {
                held[r * columns + c] = edge.holds(c, r, columns, rows);

                if (!held[r * columns + c])
                {
                    free += 1;
                }
            }
        }

        /* The whole sheet's mass, spread over the vertices that actually carry any. A sheet held
         * everywhere (one cell, all corners) has no free vertices; a gram keeps the arithmetic
         * finite and the sheet effectively weightless, which is what it behaves as anyway. */
        float freeInvMass = free == 0 ? 1000F : free / Math.max(form.mass.get(), 0.001F);

        SoftBodySharedSettings shared = new SoftBodySharedSettings();
        Vector3f point = new Vector3f();

        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < columns; c++)
            {
                point.set(form.flatX(c), form.flatY(r), 0F);
                formWorld.transformPosition(point);

                Vertex vertex = new Vertex();

                vertex.setPosition(new Vec3(
                    (float) (point.x - scene.getOriginX()),
                    (float) (point.y - scene.getOriginY()),
                    (float) (point.z - scene.getOriginZ())));
                vertex.setInvMass(held[r * columns + c] ? 0F : freeInvMass);

                shared.addVertex(vertex);
            }
        }

        /* Two triangles per cell; the edge, shear and bend constraints are derived from them. */
        for (int r = 0; r < rows - 1; r++)
        {
            for (int c = 0; c < columns - 1; c++)
            {
                int tl = r * columns + c;
                int bl = tl + columns;

                Face one = new Face();

                one.setVertex(0, tl);
                one.setVertex(1, bl);
                one.setVertex(2, bl + 1);
                shared.addFace(one);

                Face two = new Face();

                two.setVertex(0, tl);
                two.setVertex(1, bl + 1);
                two.setVertex(2, tl + 1);
                shared.addFace(two);
            }
        }

        /* One author knob, three compliances. Compliance is stiffness inverted (how much an edge
         * gives per unit of force), on a log scale because the useful range spans decades: 1 is
         * rope-taut, 0 is knit fabric. Shear and bend are progressively softer than stretch, which
         * is how real cloth works — it resists pulling far more than folding. */
        float compliance = (float) Math.pow(10D, -3D - 5D * form.stiffness.get());
        VertexAttributes attributes = new VertexAttributes(compliance, compliance * 2F, compliance * 10F);

        shared.createConstraints(attributes, 1, EBendType.Distance);

        /* Contact happens at the vertices, and a vertex is a point: give it a little thickness so
         * the sheet rests on surfaces instead of z-fighting them, scaled to the mesh so a fine sheet
         * does not look inflated. */
        shared.setVertexRadius(Math.min(form.width.get() / (columns - 1), form.height.get() / (rows - 1)) / 4F);
        shared.optimize();

        SoftBodyCreationSettings settings = new SoftBodyCreationSettings(shared, new RVec3(0D, 0D, 0D), Quat.sIdentity(), PhysicsLayers.CLOTH);

        /* The body stays at the origin; only vertices move. That makes vertex-local and scene-space
         * the same thing, which the drive and the recording both lean on. */
        settings.setUpdatePosition(false);
        settings.setMakeRotationIdentity(true);
        settings.setLinearDamping(PhysicsMath.softDamping(form.damping.get(), physics.getCollisionSteps(), SoftBodyRig.SOLVER_ITERATIONS));
        settings.setFriction(form.friction.get());

        /* Five is Jolt's own default and it is a default for a solver running at 60 Hz or better.
         * A sheet solved five times against a fifty-millisecond step is a sheet whose constraints
         * are still visibly unsatisfied when the step ends: the vertices arrive late, overshoot,
         * and the cloth cracks about like a flag rather than falling like fabric. */
        settings.setNumIterations(SoftBodyRig.SOLVER_ITERATIONS);

        /* Built before the sheet, because the sheet has to be told to consult the proxies' filter —
         * that is the whole of how it is excused from its own stand-ins. */
        ClothProxy proxy = form.selfCollision.get() ? ClothProxy.build(physics, columns, rows, group) : null;

        if (proxy != null)
        {
            settings.setCollisionGroup(proxy.sheetGroup(group));
        }

        Body body = physics.getBodies().createSoftBody(settings);

        physics.getBodies().addBody(body.getId(), EActivation.Activate);

        SoftBodyMotionProperties motion = (SoftBodyMotionProperties) body.getMotionProperties();

        if (proxy != null)
        {
            proxy.resize(physics, form.width.get() / (columns - 1), form.height.get() / (rows - 1));
        }

        form.state = new ClothState(columns, rows);

        return new ClothRig(form, path, body.getId(), scene.addChannel(columns * rows * 3 + 1), held, freeInvMass, motion, proxy, anchor);
    }

    @Override
    protected Vector3f restPosition(int i, Vector3f out)
    {
        return out.set(this.cloth.flatX(i % this.columns), this.cloth.flatY(i / this.columns), 0F);
    }

    @Override
    protected boolean isHeld(int i)
    {
        return this.held[i];
    }

    @Override
    protected void reseed(SoftBodyVertex vertex, int i)
    {
        if (!this.held[i])
        {
            /* A re-seeded sheet gets its mass back — an author may have toggled the held edge while
             * the film was open, and the scene reset is where the sheet starts over from the
             * author's description. */
            vertex.setInvMass(this.freeInvMass);
        }
    }

    @Override
    protected void afterUpdate(PhysicsWorld physics)
    {
        /* The stand-ins go where the sheet ended up last step — the only place known before this one
         * runs, hence the tick of lag named in ClothProxy. */
        if (this.proxy != null)
        {
            this.proxy.update(physics, this.motion);
        }
    }

    @Override
    protected float getFriction()
    {
        return this.cloth.friction.get();
    }

    @Override
    protected float getDamping()
    {
        return this.cloth.damping.get();
    }

    @Override
    protected String getKind()
    {
        return "cloth";
    }

    @Override
    protected void publish(float[] vertices, boolean teleport)
    {
        ClothState state = this.cloth.state;

        if (state != null)
        {
            state.set(vertices, teleport);
        }
    }

    @Override
    protected void publishUnsimulated()
    {
        ClothState state = this.cloth.state;

        if (state != null)
        {
            state.setUnsimulated();
        }
    }

    /**
     * Lets go of the form, so it draws its flat sheet again. Called when the scene is closed: the
     * body behind this rig is about to stop existing.
     */
    @Override
    public void release()
    {
        this.cloth.state = null;
    }
}
