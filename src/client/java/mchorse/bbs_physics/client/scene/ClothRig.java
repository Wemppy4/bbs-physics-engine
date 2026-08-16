package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.Face;
import com.github.stephengold.joltjni.Jolt;
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
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.cloth.ClothEdge;
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.cloth.ClothState;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;

/**
 * Ties one cloth form to its soft body in the simulation, in both directions: the held edge (and,
 * through the authority handle, the rest of the sheet) is driven from the animation every tick,
 * and where the solver puts every vertex is recorded for the renderer to drape.
 *
 * <p><b>The body never moves; its vertices do.</b> The soft body is created at the scene's origin
 * with {@code setUpdatePosition(false)}, so a vertex's local position <em>is</em> its position in
 * scene coordinates — one space fewer to get wrong, and the recording's conversion into the form's
 * frame is a single inverse per tick. Proven semantics, not assumed ones: the ClothSmoke stands
 * established what {@code putVertexLocations} returns, that {@code invMass 0} holds a vertex,
 * that a held vertex is driven by writing its position each tick, and that contact is two-way —
 * after the layer-table fix those same stands flushed out.</p>
 *
 * <p><b>The authority handle covers cloth with the same meaning it has everywhere (§4).</b> At 1
 * every vertex is stood on the flat rectangle the author placed — the sheet is kinematic in
 * everything but name. Below 1 the loose vertices are <em>pulled</em> flat: each tick they are
 * given the velocity that would carry them to their flat spot, blended with what they already have
 * in the authority's proportion — the same velocity mix the rigid bodies use, for the same reason:
 * a fade is a fade, never a switch. At 0 the sheet is entirely the solver's. The held edge is
 * driven regardless — being held is what the edge setting <em>means</em>, and the handle decides
 * how much of the rest of the sheet the animation owns.</p>
 *
 * <p>Known limits, named rather than hidden: the sheet's constitution — size, resolution, held
 * edge, stiffness — is baked into the soft body when the scene is assembled, so those edits take
 * effect when the form editor is closed (which rebuilds the cast), not live; friction and damping
 * are pushed to the live body every tick like any body setting.</p>
 */
public class ClothRig
{
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    private final ClothForm form;
    private final String path;
    private final int bodyId;
    private final int channel;

    private final int columns;
    private final int rows;

    /** Which vertices are held, precomputed — asked per vertex per tick. */
    private final boolean[] held;

    /** The free vertex's inverse mass, restored when the scene resets a released sheet. */
    private final float freeInvMass;

    private final SoftBodyMotionProperties motion;
    private final SoftBodyVertex[] vertices;

    /** The recording's layout: x y z per vertex, then the marker (§ the cache's contract). */
    private final float[] record;

    private final FloatBuffer locations;

    private final Matrix4f formWorld = new Matrix4f();
    private final Matrix4f formWorldInverse = new Matrix4f();
    private final Vector3f point = new Vector3f();
    private final Vec3 scratch = new Vec3();

    private boolean lost;
    private boolean misfed;

    private float lastFriction;
    private float lastDamping;

    private ClothRig(ClothForm form, String path, int bodyId, int channel, boolean[] held, float freeInvMass, SoftBodyMotionProperties motion)
    {
        this.form = form;
        this.path = path;
        this.bodyId = bodyId;
        this.channel = channel;
        this.columns = form.getColumns();
        this.rows = form.getRows();
        this.held = held;
        this.freeInvMass = freeInvMass;
        this.motion = motion;
        this.vertices = motion.getVertices();
        this.record = new float[this.columns * this.rows * 3 + 1];
        this.locations = Jolt.newDirectFloatBuffer(this.columns * this.rows * 3);

        this.lastFriction = form.friction.get();
        this.lastDamping = form.damping.get();
    }

    /**
     * Builds the soft body for a cloth form found at {@code path} in an actor's tree. Null when
     * the pose has no frame for that path — the scene will be rebuilt when the cast changes.
     */
    public static ClothRig build(PhysicsWorld physics, ClothForm form, String path, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene)
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
         * the sheet rests on surfaces instead of z-fighting them, scaled to the mesh so a fine
         * sheet does not look inflated. */
        shared.setVertexRadius(Math.min(form.width.get() / (columns - 1), form.height.get() / (rows - 1)) / 4F);
        shared.optimize();

        SoftBodyCreationSettings settings = new SoftBodyCreationSettings(shared, new RVec3(0D, 0D, 0D), Quat.sIdentity(), PhysicsLayers.CLOTH);

        /* The body stays at the origin; only vertices move. That makes vertex-local and
         * scene-space the same thing, which the drive and the recording both lean on. */
        settings.setUpdatePosition(false);
        settings.setMakeRotationIdentity(true);
        settings.setLinearDamping(form.damping.get());
        settings.setFriction(form.friction.get());
        settings.setNumIterations(5);

        Body body = physics.getBodies().createSoftBody(settings);

        physics.getBodies().addBody(body.getId(), EActivation.Activate);

        SoftBodyMotionProperties motion = (SoftBodyMotionProperties) body.getMotionProperties();

        form.state = new ClothState(columns, rows);

        return new ClothRig(form, path, body.getId(), scene.addChannel(columns * rows * 3 + 1), held, freeInvMass, motion);
    }

    /**
     * Runs before the world steps: stands the held edge on the animated form, and pulls the loose
     * vertices flat by however much of the sheet the animation owns.
     *
     * @param reset whether the scene itself is starting over at this tick, in which case the whole
     *              sheet is stood flat and stopped — the film's opening pose
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean reset)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.path);

        if (entry != null && entry.matrix() != null)
        {
            this.formWorld.set(actorWorld).mul(entry.matrix());
        }

        this.applySettings(physics);

        float authority = PhysicsForms.getAuthority(this.form);
        boolean flatten = reset || authority >= 1F;

        for (int r = 0; r < this.rows; r++)
        {
            for (int c = 0; c < this.columns; c++)
            {
                int i = r * this.columns + c;
                SoftBodyVertex vertex = this.vertices[i];

                boolean place = flatten || this.held[i];

                if (!place && authority <= 0F)
                {
                    /* Nothing to say to a vertex that is entirely on its own. */
                    continue;
                }

                this.point.set(this.form.flatX(c), this.form.flatY(r), 0F);
                this.formWorld.transformPosition(this.point);

                float x = (float) (this.point.x - scene.getOriginX());
                float y = (float) (this.point.y - scene.getOriginY());
                float z = (float) (this.point.z - scene.getOriginZ());

                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                {
                    /* The pose being broken must not become the solver's problem — one poisoned
                     * vertex takes the whole sheet out of the world in a step. */
                    if (!this.misfed)
                    {
                        this.misfed = true;

                        BBSPhysics.LOGGER.warn(
                            "The drive for the cloth '{}' at '{}' came out unusable at vertex {} ({}, {}, {}), so the sheet is left to itself. The pose it is pulled towards is broken.",
                            this.form.getDisplayName(), this.path, i, x, y, z);
                    }

                    continue;
                }

                if (place)
                {
                    /* Stood, not steered: the held edge is the animation's, exactly; a reset or a
                     * handle at full puts the whole sheet where the author drew it. */
                    this.scratch.set(x, y, z);
                    vertex.setPosition(this.scratch);
                    vertex.setVelocity(ZERO);

                    if (reset && !this.held[i])
                    {
                        /* A re-seeded sheet gets its mass back — an author may have toggled the
                         * held edge while the film was open, and the scene reset is where the
                         * sheet starts over from the author's description. */
                        vertex.setInvMass(this.freeInvMass);
                    }
                }
                else
                {
                    /* The same velocity mix the rigid bodies use: the speed that would carry the
                     * vertex to its flat spot this tick, kept in the authority's proportion. */
                    Vec3 position = vertex.getPosition();
                    Vec3 velocity = vertex.getVelocity();

                    float vx = mix(velocity.getX(), (x - position.getX()) / PhysicsWorld.TICK, authority);
                    float vy = mix(velocity.getY(), (y - position.getY()) / PhysicsWorld.TICK, authority);
                    float vz = mix(velocity.getZ(), (z - position.getZ()) / PhysicsWorld.TICK, authority);

                    if (Float.isFinite(vx) && Float.isFinite(vy) && Float.isFinite(vz))
                    {
                        this.scratch.set(vx, vy, vz);
                        vertex.setVelocity(this.scratch);
                    }
                }
            }
        }

        /* A sheet Jolt has put to sleep ignores everything it was just told. */
        physics.getBodies().activateBody(this.bodyId);
    }

    /**
     * Pushes the settings that can change on a live body. The sheet's constitution cannot — see
     * the class comment — but fabric feel can, and a slider that does nothing until the film is
     * reopened reads as a slider that does nothing.
     */
    private void applySettings(PhysicsWorld physics)
    {
        float friction = this.form.friction.get();

        if (friction != this.lastFriction)
        {
            physics.getBodies().setFriction(this.bodyId, friction);

            this.lastFriction = friction;
        }

        float damping = this.form.damping.get();

        if (damping != this.lastDamping)
        {
            this.motion.setLinearDamping(damping);

            this.lastDamping = damping;
        }
    }

    /**
     * Runs right after the world stepped: reads every vertex, carries it into the form's frame,
     * and writes the sheet into the recording under {@code tick}.
     *
     * <p>The conversion happens here for the reason it does everywhere (§6): the form's frame is a
     * function of the tick, the tick has just been posed, so playback reads numbers the renderer
     * substitutes directly. A sheet the solver has lost — any vertex not a number — is recorded as
     * silence, so the frame draws the flat sheet rather than nothing, and the loss is a count the
     * readout can say out loud.</p>
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        this.locations.rewind();
        this.motion.putVertexLocations(new RVec3(0D, 0D, 0D), this.locations);

        this.formWorldInverse.set(this.formWorld).invert();

        boolean sound = true;

        for (int i = 0; i < this.columns * this.rows; i++)
        {
            float x = this.locations.get(i * 3);
            float y = this.locations.get(i * 3 + 1);
            float z = this.locations.get(i * 3 + 2);

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            {
                sound = false;

                break;
            }

            /* Scene coordinates → world → the form's own frame. */
            this.point.set(
                (float) (x + scene.getOriginX()),
                (float) (y + scene.getOriginY()),
                (float) (z + scene.getOriginZ()));
            this.formWorldInverse.transformPosition(this.point);

            this.record[i * 3] = this.point.x;
            this.record[i * 3 + 1] = this.point.y;
            this.record[i * 3 + 2] = this.point.z;
        }

        this.lost = !sound;
        this.record[this.record.length - 1] = sound ? PhysicsForms.getAuthority(this.form) : PhysicsCache.SILENT;

        cache.writeFloats(tick, this.channel, this.record);
    }

    /** Whether the simulation lost this sheet on the tick it last recorded. */
    public boolean isLost()
    {
        return this.lost;
    }

    /**
     * Hands the form the recorded sheet for the frame being drawn, or the news that there is none
     * — in which case the renderer draws the flat sheet (Р8.1).
     */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        ClothState state = this.form.state;

        if (state == null)
        {
            return;
        }

        if (cache.readFloats(tick, this.channel, this.record))
        {
            state.set(this.record, teleport);
        }
        else
        {
            state.setUnsimulated();
        }
    }

    /**
     * Lets go of the form, so it draws its flat sheet again. Called when the scene is closed: the
     * body behind this rig is about to stop existing.
     */
    public void release()
    {
        this.form.state = null;
    }

    private static float mix(float physics, float animated, float authority)
    {
        return physics + (animated - physics) * authority;
    }
}
