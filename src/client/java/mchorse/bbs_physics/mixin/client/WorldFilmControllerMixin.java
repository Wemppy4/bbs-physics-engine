package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.WorldFilmController;
import mchorse.bbs_physics.client.scene.FilmScenes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches the end of a film played in the world.
 *
 * <p>{@link WorldFilmController#shutdown()} overrides the base method without calling {@code
 * super}, so the hook in {@link BaseFilmControllerMixin} never fires for it — and a scene that is
 * never told the film ended would keep a Jolt world, and the native memory under it, alive for the
 * rest of the session.</p>
 */
@Mixin(WorldFilmController.class)
public class WorldFilmControllerMixin
{
    @Inject(method = "shutdown", at = @At("TAIL"))
    private void bbs_physics$onShutdown(CallbackInfo info)
    {
        FilmScenes.onShutdown((BaseFilmController) (Object) this);
    }
}
