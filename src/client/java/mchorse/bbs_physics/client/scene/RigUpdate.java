package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * What every rig of one actor is told when the scene stands on a new tick, in one object.
 *
 * <p>Held per actor and refilled rather than built per tick: during a catch-up the scene runs
 * hundreds of ticks inside a single drawn frame, and this is on that path.</p>
 */
public final class RigUpdate
{
    public final PhysicsWorld physics;
    public final FilmScene scene;

    /**
     * How far each of this actor's ragdolled bones has been carried from its animated pose, filled
     * in by the ragdolls at the start of the tick and read by everything pinned to them.
     *
     * <p>Per actor, because the paths inside two actors are the same strings and a shared map would
     * have one character's cape reading another's fall.</p>
     */
    public final Map<String, Matrix4f> deltas = new HashMap<>();

    /** This actor's pose for the tick — the shared {@code collectMatrices} walk, evaluated once. */
    public MatrixCache matrices;

    /** Where the actor stands in the world, anchors resolved. */
    public Matrix4f actorWorld;

    /**
     * Whether the scene itself is starting over at this tick, in which case every body — the
     * simulated ones included — is stood at its animated pose and stopped, rather than steered
     * towards it over the coming tick.
     */
    public boolean reset;

    /**
     * Whether anything on this actor hangs off a ragdolled bone, worked out once when it is
     * assembled — see {@link SceneRig#readsBoneDeltas()}. False means the ragdolls skip publishing
     * their deltas entirely, which is the common case and the one worth not paying for.
     */
    public boolean pinned;

    public RigUpdate(PhysicsWorld physics, FilmScene scene)
    {
        this.physics = physics;
        this.scene = scene;
    }

    /**
     * Points this at a tick's pose.
     *
     * <p>The deltas are emptied rather than left to age: a ragdoll taken back by the animation stops
     * publishing, and a stale delta would keep a cape hanging off a fall that is over.</p>
     */
    public RigUpdate on(MatrixCache matrices, Matrix4f actorWorld, boolean reset)
    {
        this.matrices = matrices;
        this.actorWorld = actorWorld;
        this.reset = reset;

        this.deltas.clear();

        return this;
    }
}
