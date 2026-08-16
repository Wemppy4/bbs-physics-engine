package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.forms.PhysicsKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene's own numbers, written over the film editor's viewport.
 *
 * <p>Shown with the debug overlay, alongside the shapes it draws — the shapes say where the
 * simulation thinks things are, and this says whether the simulation is where the film is. Together
 * they are the difference between "physics is being weird" and a named cause.</p>
 *
 * <p>Two kinds of line. The first is always there and states the plain facts, the recording's reach
 * among them. The rest appear only when something is wrong and are written in the colour of a
 * warning, because each of them is a specific, known reason for a specific complaint: a frame the
 * recording has not reached is why the picture is animation rather than physics, a body with
 * nothing marked up is why it fell through the floor, and a body past the collected blocks is why
 * it fell through <em>everything</em>.</p>
 *
 * <p>This is the readout's shape until the cache bar lands under the timeline (Р8.2), which is
 * where "how far is it computed" really belongs — visible without the debug overlay, and legible at
 * a glance instead of as a number.</p>
 */
public final class SceneStatusHUD
{
    private static final int LINE = 12;

    private SceneStatusHUD()
    {}

    public static void render(UIContext context, Area area, SceneStatus status)
    {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        lines.add(PhysicsKeys.HUD_TICK.format(status.filmTick(), status.bodies(), Math.max(0, status.computed()), status.end()).get());
        colors.add(Colors.WHITE);

        if (!status.ready())
        {
            /* The frame on screen is animation, not simulation — correct, and deliberately so
             * (Р8.1), but an author who is not told reads it as physics having stopped. */
            lines.add(PhysicsKeys.HUD_NOT_RECORDED.format(status.filmTick()).get());
            colors.add(Colors.A100 | Colors.YELLOW);
        }

        if (status.ghosts() > 0)
        {
            lines.add(PhysicsKeys.HUD_GHOSTS.format(status.ghosts()).get());
            colors.add(Colors.A100 | Colors.NEGATIVE);
        }

        if (status.outside() > 0)
        {
            lines.add(PhysicsKeys.HUD_OUTSIDE.format(status.outside()).get());
            colors.add(Colors.A100 | Colors.NEGATIVE);
        }

        if (status.lost() > 0)
        {
            /* The one warning about the overlay itself: these bodies are drawn nowhere because
             * there is nowhere to draw them, and without a line saying so the overlay silently
             * emptying out looks like the overlay being broken. */
            lines.add(PhysicsKeys.HUD_LOST.format(status.lost()).get());
            colors.add(Colors.A100 | Colors.NEGATIVE);
        }

        /* Stacked upwards from the bottom left corner, so that adding a warning never moves the
         * line above it — a readout whose lines jump around is read as flickering rather than as
         * information. */
        int y = area.ey() - 5 - context.batcher.getFont().getHeight();

        for (int i = lines.size() - 1; i >= 0; i--)
        {
            context.batcher.textCard(lines.get(i), area.x + 5, y, colors.get(i), Colors.A50);

            y -= LINE;
        }
    }
}
