package mchorse.bbs_physics.client.scene;

/**
 * What a scene's simulation is doing right now, in the few numbers that tell an author why the
 * picture is not what they expected.
 *
 * <p>Every field here answers a complaint that is otherwise indistinguishable from any other. An
 * object landing in the wrong place, an object falling through the floor and an edit that seems not
 * to apply each have several possible causes, and from the viewport they look identical — so the
 * only honest way to work on them is to be told which one fired, rather than to guess and patch.
 * That is what this is for, and it is why it comes before the fixes it is meant to guide.</p>
 *
 * @param physicsTick where the simulated world actually stands
 * @param filmTick    where the film's cursor is. The two differing is the single most useful fact
 *                    about a scene: it means the picture on screen is not the picture of this frame
 * @param behind      whether the last seek ran out of its step budget rather than arriving
 * @param steps       how many steps the last seek had to run — a burst means a rewind or a restart
 * @param checkpoints how many snapshots are kept, which is how far back a scrub can be exact
 * @param bodies      everything in the world, the blocks included
 * @param ghosts      physics bodies with nothing marked up inside them. These fall through the
 *                    world on purpose, and that is a common reason for "it fell through"
 * @param outside     physics bodies that have left the region the world's blocks were collected in.
 *                    Beyond it there is no ground, which is the other common reason
 */
public record SceneStatus(
    int physicsTick,
    int filmTick,
    boolean behind,
    int steps,
    int checkpoints,
    int bodies,
    int ghosts,
    int outside)
{
    /** Whether anything here is worth saying out loud rather than just reporting. */
    public boolean hasWarnings()
    {
        return this.behind || this.physicsTick != this.filmTick || this.ghosts > 0 || this.outside > 0;
    }
}
