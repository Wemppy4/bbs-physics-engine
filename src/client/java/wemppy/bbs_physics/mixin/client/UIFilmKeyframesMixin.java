package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.UIContext;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.client.scene.CacheBar;
import wemppy.bbs_physics.client.scene.FilmScenes;
import wemppy.bbs_physics.client.scene.SceneStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the cache bar along the keyframe editor's timeline — the one an author actually works in.
 *
 * <p>The camera's clip timeline got it first and that was the wrong screen: a film's physics is
 * authored in the replay editor, where the "animation strength" track lives, so that is where "how
 * far is this computed" has to be visible. Both carry it now, since the two timelines show the same
 * clock and the bar is the same three pixels either way.</p>
 *
 * <p><b>The keyframe editor's X axis is not the film's.</b> A replay's keyframes are drawn relative
 * to the clip they belong to, so a tick of the film becomes a position here only after the clip's
 * own offset is taken off it — the same correction {@code getOffset()} makes for the cursor. Miss it
 * and the bar is right only for clips that happen to start at zero.</p>
 */
@Mixin(UIFilmKeyframes.class)
public abstract class UIFilmKeyframesMixin
{
    @Shadow
    public IUIClipsDelegate editor;

    @Shadow
    public abstract long getClipOffset();

    /* CML's keyframe editor has no overlay pass, so the bar is drawn with the background — behind
     * the keyframes rather than over them, which for a strip along the bottom reads the same. */
    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void bbs_physics$onRenderOverlay(UIContext context, CallbackInfo info)
    {
        if (BBSPhysicsSettings.enabled == null || !BBSPhysicsSettings.enabled.get() || this.editor == null)
        {
            return;
        }

        Film film = this.editor.getFilm();
        SceneStatus status = FilmScenes.getStatus(film);

        if (status == null)
        {
            return;
        }

        UIFilmKeyframes self = (UIFilmKeyframes) (Object) this;
        long offset = this.getClipOffset();

        /* CML's keyframe editor has no separate graph area, so the bar spans the whole element —
         * it reaches under the track labels on the left as well. */
        CacheBar.render(context, self.area, status, (tick) -> self.toGraphX(tick - offset));
    }
}
