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
 * <p>Two kinds of line. The first is always there and states the plain facts. The rest appear only
 * when something is wrong and are written in the colour of a warning, because each of them is a
 * specific, known reason for a specific complaint: the simulation standing on a different tick than
 * the cursor is why a frame does not look the way it did last time, a body with nothing marked up
 * is why it fell through the floor, and a body past the collected blocks is why it fell through
 * <em>everything</em>.</p>
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

        lines.add(PhysicsKeys.HUD_TICK.format(status.physicsTick(), status.bodies(), status.checkpoints()).get());
        colors.add(Colors.WHITE);

        if (status.physicsTick() != status.filmTick())
        {
            /* The one number an author has to be able to trust. While these differ, the viewport is
             * showing a moment the film is not on — usually a seek still catching up, and the frame
             * is simply not finished. */
            lines.add(status.behind()
                ? PhysicsKeys.HUD_CATCHING_UP.format(status.filmTick(), status.steps()).get()
                : PhysicsKeys.HUD_OFF_TICK.format(status.filmTick()).get());
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
