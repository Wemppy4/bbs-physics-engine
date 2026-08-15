package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * The strip along the bottom of the timeline that says how much of the film's physics is worked out
 * (Р8.2): filled is recorded, grey is not yet.
 *
 * <p>This exists because the recording contract makes a promise the old one did not — "this frame
 * is the truth, and it will be the truth again next time you come here" — and the price of that
 * promise is that some frames are not ready yet. Without a bar, an author scrubbing ahead of the
 * recording sees a shot with no physics in it and has no way to tell "not computed" from "broken".
 * With one, the same moment reads as a progress bar filling, which needs no explanation at all.</p>
 *
 * <p>Its own strip rather than a tint on the film's own track, which was the choice Вемпи made: the
 * track already carries clips and keyframes, and a third layer of colour on it would be one thing
 * too many to read at a glance.</p>
 */
public final class CacheBar
{
    /** Thin: it is a status line, not a track, and it must not look like something to click. */
    private static final int HEIGHT = 3;

    private CacheBar()
    {}

    /**
     * Draws the bar along the bottom of {@code area}.
     *
     * @param x0  where tick 0 sits on the screen
     * @param x1  where the last tick worth recording sits
     * @param xUp where the recording currently reaches
     */
    public static void render(UIContext context, Area area, int x0, int x1, int xUp)
    {
        int y2 = area.ey();
        int y1 = y2 - HEIGHT;

        int left = Math.max(area.x, Math.min(x0, x1));
        int right = Math.min(area.ex(), Math.max(x0, x1));

        if (right <= left)
        {
            return;
        }

        /* The whole span first, in the colour of "not yet", then the recorded part over it. Drawing
         * it as two spans instead would leave a seam that moves as the catch-up runs. */
        context.batcher.box(left, y1, right, y2, Colors.A50 | Colors.GRAY);

        int filled = Math.min(right, Math.max(left, xUp));

        if (filled > left)
        {
            context.batcher.box(left, y1, filled, y2, Colors.A100 | Colors.CYAN);
        }
    }
}
