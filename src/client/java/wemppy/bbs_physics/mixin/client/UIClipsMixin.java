package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
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
 * Draws the cache bar along the bottom of the film's timeline.
 *
 * <p>{@code UIClips} is the timeline: it owns the time scale, so it is the only place that can turn
 * a tick into a screen position — {@link UIClips#toGraphX(int)} already accounts for the zoom and
 * the horizontal scroll, which a bar that is to line up with the clips above it must do too.</p>
 *
 * <p>Drawn after the element has drawn itself, so it sits over the timeline's own background rather
 * than under it, and only when a film that is actually being simulated is on screen — an editor
 * with physics switched off has no bar, because it has nothing to say.</p>
 */
@Mixin(UIClips.class)
public abstract class UIClipsMixin
{
    @Shadow
    private IUIClipsDelegate delegate;

    @Shadow
    public abstract int toGraphX(int value);

    @Inject(method = "render", at = @At("TAIL"))
    private void bbs_physics$onRender(UIContext context, CallbackInfo info)
    {
        if (BBSPhysicsSettings.enabled == null || !BBSPhysicsSettings.enabled.get() || this.delegate == null)
        {
            return;
        }

        Film film = this.delegate.getFilm();
        SceneStatus status = FilmScenes.getStatus(film);

        if (status == null)
        {
            return;
        }

        UIClips self = (UIClips) (Object) this;

        CacheBar.render(context, self.area, status, this::toGraphX);
    }
}
