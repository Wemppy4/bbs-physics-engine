package mchorse.bbs_physics.client.scene;

/**
 * What a film's simulation is doing right now, for the readout and the bar under the timeline.
 *
 * @param filmTick the tick the film is standing on
 * @param computed the last recorded tick, -1 for none
 * @param end      the last tick worth recording
 * @param ready    whether the tick being drawn is recorded
 * @param waiting  whether the recording is held back on purpose: the film was just edited and the
 *                 background keeps clear until the author's hand is off the slider
 * @param full     whether the recording hit its memory ceiling, so it will never reach {@code end}
 * @param lostAt   the first tick on which something left the world, or -1 when nothing has
 * @param bodies   how many bodies the world holds, blocks included
 * @param ghosts   rigs with nothing marked up, which fall through everything
 * @param outside  rigs past the collected blocks, with no ground under them
 * @param lost     rigs the solver lost to an impossible push
 */
public record SceneStatus(
    int filmTick,
    int computed,
    int end,
    boolean ready,
    boolean waiting,
    boolean full,
    int lostAt,
    int bodies,
    int ghosts,
    int outside,
    int lost)
{
    public boolean hasWarnings()
    {
        return !this.ready || this.ghosts > 0 || this.outside > 0 || this.lost > 0;
    }

    /** Whether the recording is being extended right now, as opposed to done or held back. */
    public boolean computing()
    {
        return !this.waiting && !this.full && this.computed < this.end;
    }

    public float progress()
    {
        if (this.end <= 0)
        {
            return this.computed >= 0 ? 1F : 0F;
        }

        return Math.min(1F, Math.max(0F, (this.computed + 1F) / (this.end + 1F)));
    }
}
