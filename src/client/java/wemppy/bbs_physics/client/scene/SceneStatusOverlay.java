package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import wemppy.bbs_physics.BBSPhysicsSettings;

/**
 * The layer the scene's numbers are written on, over the film editor's viewport.
 *
 * <p>A registered preview overlay rather than a mixin: BBS 2.6 posts an event for exactly this, and
 * the element it hands back is laid out and drawn with everything else in the editor. Up to 2.4 the
 * readout was injected into the controller's {@code renderHUD} — which was both the only way in and
 * a method whose signature was nobody's contract, and it changed.</p>
 *
 * <p>The area drawn into is the <em>video frame</em>, not this element's own: the readout belongs to
 * the picture being made, so it sits inside the black bars rather than floating over them.</p>
 *
 * <p>Nothing here takes the mouse. The element exists to have a place in the draw order — after the
 * preview has drawn its own overlays, so the readout is never hidden under them.</p>
 */
public class SceneStatusOverlay extends UIElement
{
    private final UIFilmPreview preview;

    public SceneStatusOverlay(UIFilmPreview preview)
    {
        this.preview = preview;

        /* Over the whole preview, so the layer is laid out and drawn like any other child; the
         * readout inside it is placed against the video frame rather than against this. Mouse
         * events pass through by default, so the preview underneath keeps every click and drag. */
        this.relative(preview).full(preview);
        this.noCulling();
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        if (BBSPhysicsSettings.debug == null || !BBSPhysicsSettings.debug.get())
        {
            return;
        }

        SceneStatus status = this.status();

        if (status != null)
        {
            SceneStatusHUD.render(context, this.preview.getViewport(), status);
        }
    }

    private SceneStatus status()
    {
        UIFilmPanel panel = this.getAncestor(UIFilmPanel.class);

        return panel == null ? null : FilmScenes.getStatus(panel.getController().editorController);
    }
}
