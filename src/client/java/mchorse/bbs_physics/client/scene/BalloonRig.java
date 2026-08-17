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
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.balloon.BalloonState;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Ties one balloon form to its pressurized soft body, in both directions: the animation drives the
 * ball by however much of it the authority handle says it owns, and where the solver puts every
 * vertex is recorded for the renderer.
 *
 * <p>The cloth rig's arrangement throughout — body at the scene's origin with vertices in scene
 * coordinates, the same velocity-blend drive, the same recording contract — with two things of its
 * own. The mesh is <b>closed</b>: a UV sphere of faces wound so their volume comes out positive,
 * which is the orientation Jolt's pressure pushes <em>outwards</em> on (established by the
 * BalloonSmoke stand, where the other winding collapses). And it has <b>pressure</b>: mapped from
 * the 0..1 inflation knob as {@code knob² × 3000 × radius² × mass} — radius <em>squared</em>
 * because the solver's stability limit is step distance against cell size, and cells shrink with
 * the ball; the stand blew a quarter-radius ball up on a linear mapping before this one held.</p>
 *
 * <p>No vertex is ever pinned: a ball has no held edge. At authority 1 every vertex is stood on
 * the perfect sphere; below it they are pulled there by the shared velocity mix; at 0 the ball is
 * the solver's alone. Pressure and gravity factor are baked at creation (Jolt exposes no live
 * setter), so those edits take effect when the form editor closes; friction and damping are pushed
 * to the live body every tick like everywhere else.</p>
 */
public class BalloonRig
{
    private static final Vec3 ZERO = new Vec3(0F, 0F, 0F);

    private final BalloonForm form;
    private final String path;
    private final int bodyId;
    private final int channel;

    /**
     * The bone this ball hangs on, named the way the pose walk names bones, or null when it hangs
     * on no bone at all — the ragdoll delta of Р13, same as cloth.
     */
    private final String anchor;

    private final int count;

    private final SoftBodyMotionProperties motion;
    private final SoftBodyVertex[] vertices;

    /** The recording's layout: x y z per vertex, then the marker (§ the cache's contract). */
    private final float[] record;

    private final FloatBuffer locations;

    private final Matrix4f formWorld = new Matrix4f();
    private final Matrix4f formWorldInverse = new Matrix4f();
    private final Vector3f point = new Vector3f();
    private final Vec3 scratch = new Vec3();

    /** Where the ball last was in scene coordinates — what the readout judges against the window. */
    private final Vector3f recordedCenter = new Vector3f();
    private boolean centered;

    private boolean lost;
    private boolean misfed;

    private float lastFriction;
    private float lastDamping;

    private BalloonRig(BalloonForm form, String path, int bodyId, int channel, SoftBodyMotionProperties motion, String anchor)
    {
        this.form = form;
        this.path = path;
        this.bodyId = bodyId;
        this.channel = channel;
        this.anchor = anchor;
        this.count = form.getVertexCount();
        this.motion = motion;
        this.vertices = motion.getVertices();
        this.record = new float[this.count * 3 + 1];
        this.locations = Jolt.newDirectFloatBuffer(this.count * 3);

        this.lastFriction = form.friction.get();
        this.lastDamping = form.damping.get();
    }

    /** The inflation knob's pressure for this ball — the BalloonSmoke stand's formula. */
    public static float pressure(float inflation, float radius, float mass)
    {
        return inflation * inflation * 3000F * radius * radius * mass;
    }

    /**
     * Builds the soft body for a balloon form found at {@code path} in an actor's tree. Null when
     * the pose has no frame for that path — the scene will be rebuilt when the cast changes.
     */
    public static BalloonRig build(PhysicsWorld physics, BalloonForm form, String path, MatrixCache matrices, Matrix4f actorWorld, FilmScene scene, String anchor)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(path);

        if (entry == null || entry.matrix() == null)
        {
            return null;
        }

        Matrix4f formWorld = new Matrix4f(actorWorld).mul(entry.matrix());

        int segments = form.segments.get();
        int rings = form.rings.get();
        int count = form.getVertexCount();
        float mass = Math.max(form.mass.get(), 0.001F);

        SoftBodySharedSettings shared = new SoftBodySharedSettings();
        Vector3f point = new Vector3f();

        for (int v = 0; v < count; v++)
        {
            form.spherePoint(v, point);
            formWorld.transformPosition(point);

            Vertex vertex = new Vertex();

            vertex.setPosition(new Vec3(
                (float) (point.x - scene.getOriginX()),
                (float) (point.y - scene.getOriginY()),
                (float) (point.z - scene.getOriginZ())));
            vertex.setInvMass(count / mass);

            shared.addVertex(vertex);
        }

        /* Top fan, belts, bottom fan — wound so the pressure pushes out (see the class comment).
         * The edge, shear and bend constraints are derived from these, same as cloth. */
        int south = form.getSouthPole();

        for (int s = 0; s < segments; s++)
        {
            addFace(shared, 0, 1 + (s + 1) % segments, 1 + s);
        }

        for (int r = 0; r < rings - 1; r++)
        {
            for (int s = 0; s < segments; s++)
            {
                int tl = 1 + r * segments + s;
                int tr = 1 + r * segments + (s + 1) % segments;

                addFace(shared, tl, tr + segments, tl + segments);
                addFace(shared, tl, tr, tr + segments);
            }
        }

        for (int s = 0; s < segments; s++)
        {
            addFace(shared, south, 1 + (rings - 1) * segments + s, 1 + (rings - 1) * segments + (s + 1) % segments);
        }

        /* The same one-knob log scale cloth uses — rubber just defaults stiffer. */
        float compliance = (float) Math.pow(10D, -3D - 5D * form.stiffness.get());

        shared.createConstraints(new VertexAttributes(compliance, compliance * 2F, compliance * 10F), 1, EBendType.Distance);

        /* Contact happens at the vertices: a little thickness, scaled to the mesh, so the ball
         * rests on surfaces instead of z-fighting them. */
        shared.setVertexRadius(2F * (float) Math.PI * form.radius.get() / segments / 4F);
        shared.optimize();

        SoftBodyCreationSettings settings = new SoftBodyCreationSettings(shared, new RVec3(0D, 0D, 0D), Quat.sIdentity(), PhysicsLayers.CLOTH);

        settings.setUpdatePosition(false);
        settings.setMakeRotationIdentity(true);
        settings.setPressure(pressure(form.inflation.get(), form.radius.get(), mass));
        settings.setGravityFactor(form.gravity.get());
        settings.setLinearDamping(form.damping.get());
        settings.setFriction(form.friction.get());
        settings.setRestitution(form.restitution.get());
        settings.setNumIterations(5);

        Body body = physics.getBodies().createSoftBody(settings);

        physics.getBodies().addBody(body.getId(), EActivation.Activate);

        form.state = new BalloonState(count);

        return new BalloonRig(form, path, body.getId(), scene.addChannel(count * 3 + 1),
            (SoftBodyMotionProperties) body.getMotionProperties(), anchor);
    }

    private static void addFace(SoftBodySharedSettings shared, int v0, int v1, int v2)
    {
        Face face = new Face();

        face.setVertex(0, v0);
        face.setVertex(1, v1);
        face.setVertex(2, v2);
        shared.addFace(face);
    }

    /**
     * Runs before the world steps: pulls the ball towards the animated sphere by however much of
     * it the animation owns.
     *
     * @param reset whether the scene itself is starting over at this tick, in which case the whole
     *              ball is stood on the sphere and stopped — the film's opening pose
     * @param deltas how far each ragdolled bone of this actor has been carried from its animated
     *               pose — see {@link ActorRagdoll#publish}; the ball's frame follows a falling
     *               bone the same way a sheet does
     */
    public void update(PhysicsWorld physics, FilmScene scene, MatrixCache matrices, Matrix4f actorWorld, boolean reset, Map<String, Matrix4f> deltas)
    {
        MatrixCacheEntry entry = matrices == null ? null : matrices.get(this.path);

        if (entry != null && entry.matrix() != null)
        {
            Matrix4f delta = this.anchor == null || deltas == null ? null : deltas.get(this.anchor);

            if (delta == null)
            {
                this.formWorld.set(actorWorld).mul(entry.matrix());
            }
            else
            {
                this.formWorld.set(delta).mul(actorWorld).mul(entry.matrix());
            }
        }

        this.applySettings(physics);

        float authority = PhysicsForms.getAuthority(this.form);
        boolean place = reset || authority >= 1F;

        if (!place && authority <= 0F)
        {
            /* Nothing to say to a ball that is entirely on its own — but a sleeping one must not
             * ignore the world moving around it either; activation is cheap. */
            physics.getBodies().activateBody(this.bodyId);

            return;
        }

        for (int i = 0; i < this.count; i++)
        {
            SoftBodyVertex vertex = this.vertices[i];

            this.form.spherePoint(i, this.point);
            this.formWorld.transformPosition(this.point);

            float x = (float) (this.point.x - scene.getOriginX());
            float y = (float) (this.point.y - scene.getOriginY());
            float z = (float) (this.point.z - scene.getOriginZ());

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            {
                /* The pose being broken must not become the solver's problem — one poisoned
                 * vertex takes the whole ball out of the world in a step. */
                if (!this.misfed)
                {
                    this.misfed = true;

                    BBSPhysics.LOGGER.warn(
                        "The drive for the balloon '{}' at '{}' came out unusable at vertex {} ({}, {}, {}), so the ball is left to itself. The pose it is pulled towards is broken.",
                        this.form.getDisplayName(), this.path, i, x, y, z);
                }

                continue;
            }

            if (place)
            {
                /* Stood, not steered: a reset or a handle at full puts the ball exactly where the
                 * author drew it. */
                this.scratch.set(x, y, z);
                vertex.setPosition(this.scratch);
                vertex.setVelocity(ZERO);
            }
            else
            {
                /* The same velocity mix every body uses: the speed that would carry the vertex to
                 * its spot on the sphere this tick, kept in the authority's proportion. */
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

        /* A ball Jolt has put to sleep ignores everything it was just told. */
        physics.getBodies().activateBody(this.bodyId);
    }

    /** Pushes the settings that can change on a live body; pressure cannot — see the class comment. */
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
     * and writes the ball into the recording under {@code tick} — the cloth recording contract,
     * silence and all.
     */
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        this.locations.rewind();
        this.motion.putVertexLocations(new RVec3(0D, 0D, 0D), this.locations);

        this.formWorldInverse.set(this.formWorld).invert();

        boolean sound = true;
        double sumX = 0D;
        double sumY = 0D;
        double sumZ = 0D;

        for (int i = 0; i < this.count; i++)
        {
            float x = this.locations.get(i * 3);
            float y = this.locations.get(i * 3 + 1);
            float z = this.locations.get(i * 3 + 2);

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            {
                sound = false;

                break;
            }

            sumX += x;
            sumY += y;
            sumZ += z;

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

        if (sound)
        {
            this.recordedCenter.set((float) (sumX / this.count), (float) (sumY / this.count), (float) (sumZ / this.count));
            this.centered = true;
        }

        this.lost = !sound;
        this.record[this.record.length - 1] = sound ? PhysicsForms.getAuthority(this.form) : PhysicsCache.SILENT;

        cache.writeFloats(tick, this.channel, this.record);
    }

    /** Whether the simulation lost this ball on the tick it last recorded. */
    public boolean isLost()
    {
        return this.lost;
    }

    /** The ball's centre on the last recorded tick, in scene coordinates; false until one exists. */
    public boolean getRecordedCenter(Vector3f out)
    {
        if (this.centered)
        {
            out.set(this.recordedCenter);
        }

        return this.centered;
    }

    /**
     * Hands the form the recorded ball for the frame being drawn, or the news that there is none —
     * in which case the renderer draws the perfect sphere (Р8.1).
     */
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        BalloonState state = this.form.state;

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
     * Lets go of the form, so it draws its perfect sphere again. Called when the scene is closed:
     * the body behind this rig is about to stop existing.
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
