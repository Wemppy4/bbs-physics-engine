package mchorse.bbs_physics.client.scene;

import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsWorld;
import org.joml.Vector3f;

/**
 * One simulated thing hanging off an actor: a prop, a ragdoll, a sheet of cloth, an inflated ball,
 * a rope, a strand of hair — anything the scene drives on the way in and reads back on the way out.
 *
 * <p><b>Why this exists.</b> The scene used to keep a list per kind and walk all of them side by
 * side in seven places: driving them, recording them, handing frames out, pushing them, releasing
 * them, counting them for the readout, and tying ropes to them. Adding the sixth kind meant finding
 * and editing all seven loops, and forgetting one of them is a silent bug of exactly the sort that
 * is hardest to see — a strand that is never told about an explosion, a sheet whose form is never
 * handed back at shutdown. Now the scene holds one list and each of these loops is one loop.</p>
 *
 * <p>The order in the list is the order things were built in, and that is load bearing: the
 * ragdolls publish where their bones actually ended up, and everything pinned to a bone — a crate
 * in a fallen hand, a cape on a fallen shoulder, hair on a fallen head — is driven after them so it
 * follows this tick's fall rather than the one before it.</p>
 */
public interface SceneRig
{
    /**
     * Runs before the world steps: drives whatever this rig owns towards the pose the animation has
     * for it, by however much of it the authority handle says the animation owns.
     */
    void update(RigUpdate update);

    /**
     * Runs right after the world stepped: works out where things ended up, in the frame the renderer
     * will substitute them into, and writes that into the recording under {@code tick}.
     *
     * <p>The conversion belongs here rather than at draw time (§6): the frame an answer is expressed
     * in is a function of the tick, and the tick has just been posed to be simulated, so both are at
     * hand for free. Playing a recorded film back therefore evaluates no poses at all.</p>
     *
     * <p>Optional, because one rig has nothing of its own to record: an actor's kinematic bones are
     * the animation, exactly, and what a drawn frame needs of them it already has.</p>
     */
    default void record(PhysicsWorld physics, FilmScene scene, PhysicsCache cache, int tick)
    {}

    /**
     * Hands the form the recorded frame being drawn, or the news that there is not one — in which
     * case it is drawn from its keyframes alone (Р8.1). Optional for the same reason
     * {@link #record} is.
     */
    default void readCache(PhysicsCache cache, int tick, boolean teleport)
    {}

    /**
     * Lets go of the form, so it goes back to being drawn from its keyframes. Called when the scene
     * closes: the bodies behind this rig are about to stop existing. Optional — a rig that never
     * took a form's runtime slot has nothing to hand back.
     */
    default void release()
    {}

    /** An impulse clip's push (Э5). Whatever the animation owns outright takes nothing. */
    default void impulse(PhysicsWorld physics, SceneImpulse push)
    {}

    /**
     * Whether the simulation lost this rig on the tick it last recorded — a place that is not a
     * place. Nothing is drawn for it, which from the viewport is identical to it never having
     * existed, so the readout says the number out loud.
     */
    default boolean isLost()
    {
        return false;
    }

    /**
     * Whether this rig cares where a ragdoll of the same actor has actually carried its bones — a
     * crate in a fallen hand, a cape on a fallen shoulder, a rope tied to a fallen wrist.
     *
     * <p>Asked once when the actor is assembled, and the answer decides whether its ragdolls do the
     * work of publishing those deltas at all. An actor that is only a ragdoll — the common case —
     * skips a matrix inversion per bone per tick, which during a catch-up is a few hundred of them
     * per drawn frame.</p>
     */
    default boolean readsBoneDeltas()
    {
        return false;
    }

    /**
     * Whether nothing inside this rig was marked up as collidable, so it collides with nothing and
     * falls through the world. Deliberate (§5.1) and reported rather than hidden — it is one of the
     * few states that looks exactly like the engine being broken.
     */
    default boolean isGhost()
    {
        return false;
    }

    /**
     * Where this rig last was in the scene's own coordinates, for the "it left the collected world"
     * check the readout makes.
     *
     * @return false when it has no meaningful single place — a ragdoll is a dozen of them — in which
     *         case {@code out} is untouched
     */
    default boolean getScenePosition(Vector3f out)
    {
        return false;
    }
}
