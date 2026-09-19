package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.client.forms.PhysicsKeys;
import wemppy.bbs_physics.forms.PhysicsForms;

import java.util.function.Supplier;

/** Film-editor entry point: bake the shown actor, including its nested forms, in one undo step. */
public final class FilmBake
{
    private FilmBake()
    {}

    public static UIButton button(UIFilmPanel panel, Supplier<Replay> selected)
    {
        UIButton button = new UIButton(PhysicsKeys.BAKE, (b) -> confirm(panel, selected.get(), b.getContext()));

        button.tooltip(PhysicsKeys.BAKE_ACTOR_TOOLTIP);
        button.valueBinding(() ->
        {
            Replay replay = selected.get();

            button.setEnabled(replay != null && replay.form.get() != null && PhysicsForms.isSimulatedTree(replay.form.get()));
        });

        return button;
    }

    private static void confirm(UIFilmPanel panel, Replay replay, UIContext context)
    {
        Film film = panel.getData();

        if (film == null || replay == null)
        {
            return;
        }

        UIOverlay.addOverlay(context, new UIConfirmOverlayPanel(PhysicsKeys.BAKE_TITLE, PhysicsKeys.BAKE_ACTOR_CONFIRM, (confirmed) ->
        {
            if (!confirmed)
            {
                return;
            }

            /* The confirmation targets the actor chosen on click, even if selection changes. */
            FilmScene scene = panel.getController() == null ? null : FilmScenes.get(panel.getController().editorController);

            if (panel.getData() != film || !film.replays.getList().contains(replay) || scene == null)
            {
                message(context, PhysicsKeys.BAKE_NO_SCENE);
                return;
            }

            try
            {
                PhysicsBake.Result result = scene.bake(replay);

                message(context, result == null ? PhysicsKeys.BAKE_NO_ACTOR
                    : PhysicsKeys.BAKE_DONE.format(result.keys(), result.channels(), result.ticks()));
            }
            catch (Throwable e)
            {
                BBSPhysics.LOGGER.error("Baking an actor's physics from the film editor failed.", e);
                message(context, PhysicsKeys.BAKE_FAILED);
            }
        }));
    }

    private static void message(UIContext context, IKey text)
    {
        UIOverlay.addOverlay(context, new UIMessageOverlayPanel(PhysicsKeys.BAKE_TITLE, text));
    }
}
