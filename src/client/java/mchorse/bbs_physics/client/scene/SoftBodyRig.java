package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SoftBodyMotionProperties;
import com.github.stephengold.joltjni.SoftBodyVertex;
import com.github.stephengold.joltjni.Vec3;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsMath;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;

/**
 * What a sheet of cloth and an inflated ball have in common, which turns out to be almost
 * everything: a soft body whose vertices are the answer, driven towards an animated shape by the
 * authority handle and recorded vertex by vertex.
 *
 * <p><b>The body never moves; its vertices do.</b> It is created at the scene's origin with
 * {@code setUpdatePosition(false)}, so a vertex's local position <em>is</em> its position in scene
 * coordinates — one space fewer to get wrong, and the recording's conversion into the form's frame
 * is a single inverse per tick. Proven semantics, not assumed ones: the ClothSmoke stands
 * established what {@code putVertexLocations} returns, that {@code invMass 0} holds a vertex, that a
 * held vertex is driven by writing its position each tick, and that contact is two-way.</p>
 *
 * <p><b>The authority handle means here what it means everywhere (§4).</b> At 1 every vertex is
 * stood on the shape the author placed — the body is kinematic in everything but name. Below 1 the
 * loose vertices are <em>pulled</em> there: each tick they are given the velocity that would carry
 * them home, blended with what they already have in the handle's proportion — the same velocity mix
 * the rigid bodies use, for the same reason: a fade is a fade, never a switch. At 0 the shape is
 * entirely the solver's.</p>
 *
 * <p>What the two do differently is what is left abstract: where a vertex belongs when the animation
 * owns it, how many there are, and whether any of them are held.</p>
 */
public abstract class SoftBodyRig implements SceneRig
{
    protected final Form form;
    protected final String path;
    protected final int bodyId;
    protected final int channel;

    /**
     * The bone this form hangs on, named the way the pose walk names bones, or null when it hangs on
     * no bone at all. Only used to ask whether that bone is being ragdolled — see {@link #frame}.
     */
    private final String anchor;

    /** How many vertices the body has — the mesh is fixed once the scene is assembled. */
    protected final int count;

    protected final SoftBodyMotionProperties motion;
    protected final SoftBodyVertex[] vertices;

    /** The recording's layout: x y z per vertex, then the marker (§ the cache's contract). */
    private final float[] record;

    private final FloatBuffer locations;

    /** Scene coordinates, read out of the solver — allocated once, this runs per tick. */
    private static final RVec3 SCENE_ORIGIN = new RVec3(0D, 0D, 0D);

    /** Where the form is drawn this tick: the actor's placement times the chain of forms above it. */
    protected final Matrix4f formWorld = new Matrix4f();
    private final Matrix4f formWorldInverse = new Matrix4f();

    protected final Vector3f point = new Vector3f();
    protected final Vec3 scratch = new Vec3();

    /** Where the body last was in scene coordinates — what the readout judges against the window. */
    private final Vector3f recordedCenter = new Vector3f();
    private boolean centered;

    private boolean lost;
    private boolean misfed;

    /**
     * What was last pushed into the body, so an untouched knob is not pushed every tick.
     *
     * <p>NaN rather than the form's value, and set here rather than in the constructor: reading the
     * form would mean asking {@link #getFriction()}, and a subclass answers that out of a field it
     * only assigns <em>after</em> this constructor has returned — the call would land on a null
     * form. Any comparison against NaN is unequal, so the first tick pushes both values; the
     * creation settings baked them in already, which makes that push two setter calls and no
     * change.</p>
     */
    private float lastFriction = Float.NaN;
    private float lastDamping = Float.NaN;

    protected SoftBodyRig(Form form, String path, int bodyId, int channel, int count, SoftBodyMotionProperties motion, String anchor)
    {
        this.form = form;
        this.path = path;
        this.bodyId = bodyId;
        this.channel = channel;
        this.anchor = anchor;
        this.count = count;
        this.motion = motion;
        this.vertices = motion.getVertices();
        this.record = new float[count * 3 + 1];
        this.locations = Jolt.newDirectFloatBuffer(count * 3);
    }

    /* What the two kinds answer differently */

    /** Where vertex {@code i} belongs in the form's own frame when the animation owns the shape. */
    protected abstract Vector3f restPosition(int i, Vector3f out);

    /** Whether vertex {@code i} is held by the author outright — a cloth's pinned edge. */
    protected boolean isHeld(int i)
    {
        return false;
    }

    /** Called for a held vertex on a reset, where a sheet's freed vertices get their mass back. */
    protected void reseed(SoftBodyVertex vertex, int i)
    {}

    protected abstract float getFriction();

    protected abstract float getDamping();

    /** Pushes any further live knobs of this kind — bounce, gravity — into the body. */
    protected void applyOwnSettings(PhysicsWorld physics)
    {}

    /** What this thing is called in a warning — "the cloth", "the balloon". */
    protected abstract String getKind();

    /* The shared half */

    /**
     * Runs before the world steps: stands whatever the animation owns on the authored shape, and
     * pulls the rest towards it by however much of it the animation owns.
     */
    @Override
    public void update(RigUpdate update)
    {
        PhysicsWorld physics = update.physics;

        this.frame(update);
        this.applySettings(physics);

        float authority = PhysicsForms.getAuthority(this.form);
        boolean place = update.reset || authority >= 1F;

        for (int i = 0; i < this.count; i++)
        {
            SoftBodyVertex vertex = this.vertices[i];
            boolean stand = place || this.isHeld(i);

            if (!stand && authority <= 0F)
            {
                /* Nothing to say to a vertex that is entirely on its own. */
                continue;
            }

            this.restPosition(i, this.point);
            this.formWorld.transformPosition(this.point);

            float x = (float) (this.point.x - update.scene.getOriginX());
            float y = (float) (this.point.y - update.scene.getOriginY());
            float z = (float) (this.point.z - update.scene.getOriginZ());

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            {
                /* The pose being broken must not become the solver's problem — one poisoned vertex
                 * takes the whole body out of the world in a step. */
                if (!this.misfed)
                {
                    this.misfed = true;

                    BBSPhysics.LOGGER.warn(
                        "The drive for the {} '{}' at '{}' came out unusable at vertex {} ({}, {}, {}), so it is left to itself. The pose it is pulled towards is broken.",
                        this.getKind(), this.form.getDisplayName(), this.path, i, x, y, z);
                }

                continue;
            }

            if (stand)
            {
                /* Stood, not steered: what the author holds is the animation's, exactly; a reset or
                 * a handle at full puts the whole shape where the author drew it. */
                this.scratch.set(x, y, z);
                vertex.setPosition(this.scratch);
                vertex.setVelocity(PhysicsMath.ZERO);

                if (update.reset)
                {
                    this.reseed(vertex, i);
                }
            }
            else
            {
                /* The same velocity mix the rigid bodies use: the speed that would carry the vertex
                 * home this tick, kept in the authority's proportion. */
                Vec3 position = vertex.getPosition();
                Vec3 velocity = vertex.getVelocity();

                float vx = PhysicsMath.mix(velocity.getX(), (x - position.getX()) / PhysicsWorld.TICK, authority);
                float vy = PhysicsMath.mix(velocity.getY(), (y - position.getY()) / PhysicsWorld.TICK, authority);
                float vz = PhysicsMath.mix(velocity.getZ(), (z - position.getZ()) / PhysicsWorld.TICK, authority);

                if (Float.isFinite(vx) && Float.isFinite(vy) && Float.isFinite(vz))
                {
                    this.scratch.set(vx, vy, vz);
                    vertex.setVelocity(this.scratch);
                }
            }
        }

        /* A body Jolt has put to sleep ignores everything it was just told — including the world
         * moving around a body the handle has let go of entirely. */
        physics.getBodies().activateBody(this.bodyId);

        this.afterUpdate(physics);
    }

    /** Anything a kind does once the vertices are driven — cloth moves its stand-ins here. */
    protected void afterUpdate(PhysicsWorld physics)
    {}

    /**
     * The frame the whole rig works in for this tick.
     *
     * <p>The bone this form hangs on may be falling, and the pose walk cannot say so: it is run with
     * the ragdoll's substitution off, because the simulation must see plain animation. Left at that,
     * a cape pinned to a shoulder was simulated where the shoulder would have been had the character
     * stayed on its feet, while the renderer drew it where the shoulder actually is. Multiplying on
     * the left swaps the animated bone for the simulated one and leaves the form's own transform
     * below it alone — and applying it to the frame the rig works in means the recording, which
     * converts into this very frame, stays consistent with it for free.</p>
     */
    private void frame(RigUpdate update)
    {
        MatrixCacheEntry entry = update.matrices == null ? null : update.matrices.get(this.path);

        if (entry == null || entry.matrix() == null)
        {
            return;
        }

        Matrix4f delta = this.anchor == null ? null : update.deltas.get(this.anchor);

        if (delta == null)
        {
            this.formWorld.set(update.actorWorld).mul(entry.matrix());
        }
        else
        {
            this.formWorld.set(delta).mul(update.actorWorld).mul(entry.matrix());
        }
    }

    /**
     * Pushes the settings that can change on a live body. The mesh's constitution cannot — size,
     * resolution, stiffness, pressure are baked in when the scene is assembled — but the feel can,
     * and a slider that does nothing until the film is reopened reads as a slider that does nothing.
     */
    private void applySettings(PhysicsWorld physics)
    {
        float friction = this.getFriction();

        if (friction != this.lastFriction)
        {
            physics.getBodies().setFriction(this.bodyId, friction);

            this.lastFriction = friction;
        }

        float damping = this.getDamping();

        if (damping != this.lastDamping)
        {
            this.motion.setLinearDamping(damping);

            this.lastDamping = damping;
        }

        this.applyOwnSettings(physics);
    }

    /**
     * An impulse clip's push (Э5), taken vertex by vertex: each loose vertex inside the reach adds
     * the velocity change for where it hangs. Per vertex deliberately — a blast lifts the near corner
     * of a cape before the far one, which is most of what makes cloth read as cloth.
     */
    @Override
    public void impulse(PhysicsWorld physics, SceneImpulse push)
    {
        if (PhysicsForms.getAuthority(this.form) >= 1F)
        {
            /* The animation owns the whole thing, and physics has no business kicking keyframes —
             * the same rule a kinematic rigid body gets for free from its motion type. Without it
             * the shape is not even left alone: a push lands after this tick's drive has already
             * stood every vertex home, so the blast drags it for one step and the next tick snaps it
             * back — a twitch on something the author holds. */
            return;
        }

        boolean pushed = false;

        for (int i = 0; i < this.count; i++)
        {
            if (this.isHeld(i))
            {
                continue;
            }

            SoftBodyVertex vertex = this.vertices[i];
            Vec3 position = vertex.getPosition();

            /* Vertex-local is scene-space here — the body never moves, see the class note. */
            if (!push.velocityAt(position.getX(), position.getY(), position.getZ(), this.point))
            {
                continue;
            }

            Vec3 velocity = vertex.getVelocity();

            this.scratch.set(
                velocity.getX() + this.point.x,
                velocity.getY() + this.point.y,
                velocity.getZ() + this.point.z);
            vertex.setVelocity(this.scratch);

            pushed = true;
        }

        if (pushed)
        {
            physics.getBodies().activateBody(this.bodyId);
        }
    }

    /**
     * Runs right after the world stepped: reads every vertex, carries it into the form's frame, and
     * writes the whole shape into the recording under {@code tick}.
     *
     * <p>A body the solver has lost — any vertex not a number — is recorded as silence, so the frame
     * draws the authored shape rather than nothing, and the loss is a count the readout can say out
     * loud.</p>
     */
    @Override
    public void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {
        this.locations.rewind();
        this.motion.putVertexLocations(SCENE_ORIGIN, this.locations);

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

    /**
     * Hands the form the recorded shape for the frame being drawn, or the news that there is none —
     * in which case the renderer draws what the author placed (Р8.1).
     */
    @Override
    public void readCache(PhysicsCache cache, int tick, boolean teleport)
    {
        if (cache.readFloats(tick, this.channel, this.record))
        {
            this.publish(this.record, teleport);
        }
        else
        {
            this.publishUnsimulated();
        }
    }

    /** Hands the recorded vertices to the form's runtime slot, for the renderer to draw. */
    protected abstract void publish(float[] vertices, boolean teleport);

    /** Tells the form's runtime slot that this frame has no simulation behind it. */
    protected abstract void publishUnsimulated();

    @Override
    public boolean isLost()
    {
        return this.lost;
    }

    @Override
    public boolean readsBoneDeltas()
    {
        return true;
    }

    /** The centre on the last recorded tick, in scene coordinates; false until one exists. */
    @Override
    public boolean getScenePosition(Vector3f out)
    {
        if (this.centered)
        {
            out.set(this.recordedCenter);
        }

        return this.centered;
    }
}
