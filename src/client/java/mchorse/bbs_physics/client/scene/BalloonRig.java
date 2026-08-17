package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.Face;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SoftBodyCreationSettings;
import com.github.stephengold.joltjni.SoftBodySharedSettings;
import com.github.stephengold.joltjni.SoftBodyMotionProperties;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.Vertex;
import com.github.stephengold.joltjni.VertexAttributes;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EBendType;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.balloon.BalloonState;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Ties one balloon form to its pressurized soft body. The cloth rig's arrangement throughout —
 * everything shared lives in {@link SoftBodyRig} — with two things of its own.
 *
 * <p>The mesh is <b>closed</b>: a UV sphere of faces wound so their volume comes out positive, which
 * is the orientation Jolt's pressure pushes <em>outwards</em> on (established by the BalloonSmoke
 * stand, where the other winding collapses). And it has <b>pressure</b>: mapped from the 0..1
 * inflation knob as {@code knob² × 3000 × radius² × mass} — radius <em>squared</em> because the
 * solver's stability limit is step distance against cell size, and cells shrink with the ball; the
 * stand blew a quarter-radius ball up on a linear mapping before this one held.</p>
 *
 * <p>No vertex is ever pinned: a ball has no held edge. Only the ball's <em>constitution</em> is
 * baked at creation — its size, its mesh and its pressure, which Jolt has no live setter for — so
 * those edits take effect when the form editor closes; everything an author turns while watching
 * (friction, damping, bounce, the gravity factor that turns a football into a helium balloon) is
 * pushed to the live body.</p>
 */
public class BalloonRig extends SoftBodyRig
{
    private final BalloonForm balloon;

    private float lastRestitution;
    private float lastGravity;

    private BalloonRig(BalloonForm form, String path, int bodyId, int channel, SoftBodyMotionProperties motion, String anchor)
    {
        super(form, path, bodyId, channel, form.getVertexCount(), motion, anchor);

        this.balloon = form;
        this.lastRestitution = form.restitution.get();
        this.lastGravity = form.gravity.get();
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
        int rings = form.getRings();
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

        /* Contact happens at the vertices: a little thickness, scaled to the mesh, so the ball rests
         * on surfaces instead of z-fighting them. A quarter of the smaller cell, exactly as cloth
         * sizes it — the equator's spacing alone was wrong for a coarse ball, where the rings are the
         * closer of the two and a skin thicker than the gap between them is a ball hovering over the
         * floor by a visible fraction of its own radius. */
        float around = 2F * (float) Math.PI * form.radius.get() / segments;
        float down = (float) Math.PI * form.radius.get() / (rings + 1);

        shared.setVertexRadius(Math.min(around, down) / 4F);
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

    @Override
    protected Vector3f restPosition(int i, Vector3f out)
    {
        this.balloon.spherePoint(i, out);

        return out;
    }

    @Override
    protected float getFriction()
    {
        return this.balloon.friction.get();
    }

    @Override
    protected float getDamping()
    {
        return this.balloon.damping.get();
    }

    /**
     * Bounce and the gravity factor, which the base class knows nothing about.
     *
     * <p>Both were once left at whatever creation baked in, and both are knobs an author turns while
     * watching: bounce is the whole character of a beach ball, and the gravity factor is the
     * difference between a football and a helium balloon. A slider that only takes effect once the
     * form editor has been closed and reopened reads as a slider that does nothing.</p>
     */
    @Override
    protected void applyOwnSettings(PhysicsWorld physics)
    {
        float restitution = this.balloon.restitution.get();

        if (restitution != this.lastRestitution)
        {
            physics.getBodies().setRestitution(this.bodyId, restitution);

            this.lastRestitution = restitution;
        }

        float gravity = this.balloon.gravity.get();

        if (gravity != this.lastGravity)
        {
            physics.getBodies().setGravityFactor(this.bodyId, gravity);

            this.lastGravity = gravity;
        }
    }

    @Override
    protected String getKind()
    {
        return "balloon";
    }

    @Override
    protected void publish(float[] vertices, boolean teleport)
    {
        BalloonState state = this.balloon.state;

        if (state != null)
        {
            state.set(vertices, teleport);
        }
    }

    @Override
    protected void publishUnsimulated()
    {
        BalloonState state = this.balloon.state;

        if (state != null)
        {
            state.setUnsimulated();
        }
    }

    /**
     * Lets go of the form, so it draws its perfect sphere again. Called when the scene is closed:
     * the body behind this rig is about to stop existing.
     */
    @Override
    public void release()
    {
        this.balloon.state = null;
    }
}
