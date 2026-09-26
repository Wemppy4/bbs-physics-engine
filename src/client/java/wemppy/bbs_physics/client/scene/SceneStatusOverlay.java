package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import wemppy.bbs_physics.BBSPhysicsSettings;

/** One quiet badge; detailed diagnostics belong in its hover tooltip, never over the controls. */
public class SceneStatusOverlay extends UIElement
{
    private enum State
    {
        WAITING(0xffe6bc62, "waiting"),
        COMPUTING(0xff6ec8ec, "computing"),
        READY(0xff85d39a, "ready"),
        WARNING(0xffed8181, "warning");

        final int color;
        final String key;

        State(int color, String key)
        {
            this.color = color;
            this.key = key;
        }
    }

    private final UIFilmPreview preview;
    private String details = "";
    private boolean badgeVisible;

    public SceneStatusOverlay(UIFilmPreview preview)
    {
        this.preview = preview;
        this.relative(preview).full(preview);
        this.noCulling();
        this.tooltip(() -> this.details, 280, Direction.RIGHT);
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.badgeVisible && this.area.isInside(context)
            && BBSPhysicsSettings.enabled != null && BBSPhysicsSettings.enabled.get())
        {
            UIFilmPanel panel = this.getAncestor(UIFilmPanel.class);
            if (panel != null && FilmScenes.get(panel.getController().editorController) != null)
            {
                FilmScenes.requestCalculation(panel.getController().editorController);
                mchorse.bbs_mod.ui.utils.UIUtils.playClick();
                return true;
            }
        }
        return super.subMouseClicked(context);
    }

    @Override
    public void render(UIContext context)
    {
        // The hit area is the badge alone, not the whole preview.
        this.badgeVisible = false;
        this.area.set(0, 0, 0, 0);
        if (BBSPhysicsSettings.enabled == null || !BBSPhysicsSettings.enabled.get()) return;
        UIFilmPanel panel = this.getAncestor(UIFilmPanel.class);
        FilmScene scene = panel == null ? null : FilmScenes.get(panel.getController().editorController);
        SceneStatus status = panel == null ? null : FilmScenes.getStatus(panel.getController().editorController);
        if (status == null) return;
        boolean debug = BBSPhysicsSettings.debug != null && BBSPhysicsSettings.debug.get();
        boolean warning = status.full() || status.lostAt() >= 0 || status.ghosts() > 0 || status.outside() > 0 || status.lost() > 0;
        State state = warning ? State.WARNING : status.waiting() ? State.WAITING
            : status.computing() ? State.COMPUTING : status.ready() ? State.READY : State.WAITING;

        var viewport = this.preview.getViewport();
        String label = state == State.WARNING ? "!" : state == State.COMPUTING ? (int) (status.progress() * 100) + "%" : "";
        int width = label.isEmpty() ? 20 : 25 + context.batcher.getFont().getWidth(label);
        if (viewport.w < width + 16 || viewport.h < 40) return;
        int x = viewport.x + 8;
        int y = viewport.y + 8;
        this.area.set(x, y, width, 20);
        this.badgeVisible = true;
        this.details = mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.status." + state.key)
            .format((int) (status.progress() * 100)).get()
            + "\n" + SceneStatusHUD.details(status, debug ? scene : null);
        if (scene.waitingForCalculation())
            this.details = mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.status.manual").get()
                + " (" + wemppy.bbs_physics.client.PhysicsKeybinds.CALCULATE.getKeyCombo() + ")\n" + this.details;
        this.details = mchorse.bbs_mod.l10n.L10n.lang("bbs_physics.status.click_calculate").get()
            + "\n" + this.details;
        boolean hover = this.area.isInside(context);
        int background = hover ? 0xdd20272f : 0xb320272f;
        int accent = state.color;
        // One-pixel inset corners keep the badge compact and match the editor's pixel UI.
        context.batcher.box(x + 1, y, x + width - 1, y + 20, background);
        context.batcher.box(x, y + 1, x + 1, y + 19, background);
        context.batcher.box(x + width - 1, y + 1, x + width, y + 19, background);
        context.batcher.icon(Icons.PHYSICS, accent, x + 10, y + 10, 0.5F, 0.5F);
        if (!label.isEmpty()) context.batcher.text(label, x + 23,
            y + (20 - context.batcher.getFont().getHeight()) / 2F, 0xffc3cdd3, false);
        super.render(context);
    }
}
