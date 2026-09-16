package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import wemppy.bbs_physics.engine.PhysicsWorld;
import org.joml.Matrix4f;


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

    /** This rig's target pose for the tick — the shared {@code collectMatrices} walk, with physical parents. */
    public MatrixCache matrices;

    /** Where the actor stands in the world, anchors resolved. */
    public Matrix4f actorWorld;

    /**
     * Whether the scene itself is starting over at this tick, in which case every body — the
     * simulated ones included — is stood at its animated pose and stopped, rather than steered
     * towards it over the coming tick.
     */
    public boolean reset;

    public RigUpdate(PhysicsWorld physics, FilmScene scene)
    {
        this.physics = physics;
        this.scene = scene;
    }

    /**
     * Points this at a tick's pose.
     *
     */
    public RigUpdate on(MatrixCache matrices, Matrix4f actorWorld, boolean reset)
    {
        this.matrices = matrices;
        this.actorWorld = actorWorld;
        this.reset = reset;

        return this;
    }
}
