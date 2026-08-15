package mchorse.bbs_physics.client.scene;

/**
 * What a scene's recording is doing right now, in the few numbers that tell an author why the
 * picture is not what they expected.
 *
 * <p>Every field here answers a complaint that is otherwise indistinguishable from any other. An
 * object landing in the wrong place, an object falling through the floor and an edit that seems not
 * to apply each have several possible causes, and from the viewport they look identical — so the
 * only honest way to work on them is to be told which one fired, rather than to guess and patch.
 * That is what this is for, and it is why it comes before the fixes it is meant to guide.</p>
 *
 * <p>The old "the world stands on a different tick than the film" pair of numbers is gone with the
 * checkpoints: the world is a recorder and the film is drawn from the recording, so they cannot
 * disagree. What replaces it is how far the recording reaches, which is the one thing an author now
 * needs to know — and the same fact the cache bar under the timeline will show (Р8.2).</p>
 *
 * @param filmTick where the film's cursor is
 * @param computed the last tick the recording holds, or -1 when it holds nothing yet
 * @param end      the last tick worth recording — the film's length plus a little
 * @param ready    whether the frame being drawn is recorded. When it is not, the picture is plain
 *                 animation (Р8.1), which is correct but is not physics, and an author who does not
 *                 know that reads it as physics having stopped working
 * @param bodies   everything in the world, the blocks included
 * @param ghosts   physics bodies with nothing marked up inside them. These fall through the world
 *                 on purpose, and that is a common reason for "it fell through"
 * @param outside  physics bodies that have left the region the world's blocks were collected in.
 *                 Beyond it there is no ground, which is the other common reason
 */
public record SceneStatus(
    int filmTick,
    int computed,
    int end,
    boolean ready,
    int bodies,
    int ghosts,
    int outside)
{
    /** Whether anything here is worth saying out loud rather than just reporting. */
    public boolean hasWarnings()
    {
        return !this.ready || this.ghosts > 0 || this.outside > 0;
    }

    /** How much of the film is recorded, 0 to 1 — the number the cache bar draws. */
    public float progress()
    {
        if (this.end <= 0)
        {
            return this.computed >= 0 ? 1F : 0F;
        }

        return Math.min(1F, Math.max(0F, (this.computed + 1F) / (this.end + 1F)));
    }
}
