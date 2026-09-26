package wemppy.bbs_physics.client.scene;

import wemppy.bbs_physics.client.forms.PhysicsKeys;

import java.util.ArrayList;
import java.util.List;

/** Detailed physics status, shown only in the compact badge tooltip. */
public final class SceneStatusHUD
{
    private SceneStatusHUD() {}

    public static String details(SceneStatus status, FilmScene scene)
    {
        List<String> lines = new ArrayList<>();

        lines.add(PhysicsKeys.HUD_TICK.format(status.filmTick(), status.bodies(), Math.max(0, status.computed()), status.end()).get());

        if (scene != null && scene.getFilm() != null)
        {
            for (var replay : scene.getFilm().replays.getList())
            {
                if (!replay.enabled.get()) continue;
                var clip = wemppy.bbs_physics.ragdoll.DeathReplay.at(replay, Integer.MAX_VALUE);
                if (clip == null || clip.baked.get()) continue;
                var hit = scene.getDeathImpact(clip);
                if (hit == null) continue;
                lines.add(mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.death.debug_part").format(hit.bone()).get());
            }
            boolean hasDeath = scene.getFilm().replays.getList().stream().anyMatch(replay ->
            {
                var clip = wemppy.bbs_physics.ragdoll.DeathReplay.at(replay, Integer.MAX_VALUE);
                return replay.enabled.get() && clip != null && !clip.baked.get();
            });
            if (hasDeath)
            {
                lines.add(mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.death.debug_point").get());
                lines.add(mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.death.debug_arrow").get());
            }
        }

        if (!status.ready())
        {
            /* The frame on screen is animation, not simulation — correct, and deliberately so
             * (Р8.1), but an author who is not told reads it as physics having stopped. */
            lines.add(PhysicsKeys.HUD_NOT_RECORDED.format(status.filmTick()).get());
        }

        if (status.ghosts() > 0)
        {
            lines.add(PhysicsKeys.HUD_GHOSTS.format(status.ghosts()).get());
        }

        if (status.outside() > 0)
        {
            lines.add(PhysicsKeys.HUD_OUTSIDE.format(status.outside()).get());
        }

        if (status.lost() > 0)
        {
            /* The one warning about the overlay itself: these bodies are drawn nowhere because
             * there is nowhere to draw them, and without a line saying so the overlay silently
             * emptying out looks like the overlay being broken. */
            lines.add(PhysicsKeys.HUD_LOST.format(status.lost()).get());
        }

        if (status.full()) lines.add(mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.status.full").get());
        else if (status.waiting()) lines.add(mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.status.waiting").get());
        return String.join("\n", lines);
    }
}
