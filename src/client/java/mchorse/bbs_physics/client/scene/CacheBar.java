package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.IntUnaryOperator;

/**
 * The strip along the bottom of the timeline that says how much of the film's physics is worked out
 * (Р8.2), and what the recording is doing about the rest.
 *
 * <p>This exists because the recording contract makes a promise the old one did not — "this frame
 * is the truth, and it will be the truth again next time you come here" — and the price of that
 * promise is that some frames are not ready yet. Without a bar, an author scrubbing ahead of the
 * recording sees a shot with no physics in it and has no way to tell "not computed" from "broken".
 * With one, the same moment reads as a progress bar filling, which needs no explanation at all.</p>
 *
 * <p><b>One colour was not enough</b> (Р16): a grey tail meant four different things — still
 * computing, waiting for the author's hand to leave the slider, out of memory, or a body lost to
 * the solver — and the author had to guess which. So the recorded part is cyan while it is
 * growing and while it is done, the whole strip turns amber while the background is deliberately
 * holding back after an edit, a white head marks where the recording is being extended this very
 * frame, and a red notch marks the tick where something went wrong: the recording running out of
 * room, or a body leaving the world.</p>
 *
 * <p>Its own strip rather than a tint on the film's own track, which was the choice Вемпи made: the
 * track already carries clips and keyframes, and a third layer of colour on it would be one thing
 * too many to read at a glance.</p>
 */
public final class CacheBar
{
    /** Thin: it is a status line, not a track, and it must not look like something to click. */
    private static final int HEIGHT = 3;

    /** The head marker and the notches are wider than a tick so they can be seen at any zoom. */
    private static final int MARK = 2;

    private CacheBar()
    {}

    /**
     * Draws the bar along the bottom of {@code area}.
     *
     * @param toX where a tick sits on the screen — the timeline's own mapping, zoom and scroll
     *            included
     */
    public static void render(UIContext context, Area area, SceneStatus status, IntUnaryOperator toX)
    {
        int y2 = area.ey();
        int y1 = y2 - HEIGHT;

        int x0 = toX.applyAsInt(0);
        int x1 = toX.applyAsInt(status.end());
        int left = Math.max(area.x, Math.min(x0, x1));
        int right = Math.min(area.ex(), Math.max(x0, x1));

        if (right <= left)
        {
            return;
        }

        /* The whole span first, in the colour of "not yet", then the recorded part over it. Drawing
         * it as two spans instead would leave a seam that moves as the catch-up runs. */
        context.batcher.box(left, y1, right, y2, Colors.A50 | Colors.GRAY);

        int filled = Math.min(right, Math.max(left, toX.applyAsInt(status.computed() + 1)));

        if (status.waiting())
        {
            /* Held back on purpose: the recording is about to be thrown away, or has just been,
             * and nothing will be computed until the author's hand is off the slider. Amber
             * over the whole span, so that "why is nothing happening" has an answer. */
            context.batcher.box(left, y1, right, y2, Colors.A75 | Colors.ORANGE);
        }
        else if (filled > left)
        {
            context.batcher.box(left, y1, filled, y2, Colors.A100 | Colors.CYAN);
        }

        if (status.computing() && filled > left && filled < right)
        {
            /* The head: where the recording is being extended this frame. */
            context.batcher.box(filled - MARK, y1, filled, y2, Colors.WHITE);
        }

        if (status.full())
        {
            notch(context, area, y1, y2, filled);
        }

        if (status.lostAt() >= 0)
        {
            notch(context, area, y1, y2, toX.applyAsInt(status.lostAt()));
        }
    }

    /** A red mark at {@code x}, one tick tall and a couple of pixels wide, clipped to the area. */
    private static void notch(UIContext context, Area area, int y1, int y2, int x)
    {
        int a = Math.max(area.x, x - MARK);
        int b = Math.min(area.ex(), x + MARK);

        if (b > a)
        {
            context.batcher.box(a, y1 - 1, b, y2, Colors.A100 | Colors.RED);
        }
    }
}
