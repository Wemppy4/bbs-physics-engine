package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_physics.client.scene.FilmScenes;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the addon attaches itself to a running film.
 *
 * <p>BBS posts events to addons while it starts up, but nothing during a film — so the four
 * moments a simulation needs are taken from {@link BaseFilmController} directly. Every way of
 * running a film goes through this class: playback in the world, the film editor's live scene, and
 * the frozen frame the editor leaves standing. That matters more than it looks — a simulation that
 * only ran during playback would make the editor's viewport disagree with the exported video.</p>
 *
 * <p>Attaching by mixin rather than by asking BBS for events keeps this addon self-contained: BBS
 * is not modified at all, which means no merge conflicts across its three branches and no
 * republishing it after every change here. The cost is that these four method names are now a
 * contract — rename one and the addon fails to load, loudly, at startup.</p>
 */
@Mixin(BaseFilmController.class)
public class BaseFilmControllerMixin
{
    /**
     * The cast was assembled, or rebuilt after the editor changed who is in the film. The entities
     * are new objects, so whatever was simulated for the previous ones is stale.
     */
    @Inject(method = "createEntities", at = @At("TAIL"))
    private void bbs_physics$onSetup(CallbackInfo info)
    {
        FilmScenes.onSetup((BaseFilmController) (Object) this);
    }

    /**
     * One film tick, with every actor already updated to it — the moment physics belongs in, since
     * it can read this tick's poses and have its answer ready before anything is drawn.
     *
     * <p>Hooked here rather than in {@code update()} because {@code ticks} is the tick the actors
     * were actually moved to: the film editor recomputes it (the cursor, plus one while playing)
     * and passes the corrected value down to this method. Reading the controller's own tick
     * instead would leave physics a tick behind during playback.</p>
     */
    @Inject(method = "updateEntities", at = @At("TAIL"))
    private void bbs_physics$onTick(int ticks, CallbackInfo info)
    {
        FilmScenes.onTick((BaseFilmController) (Object) this, ticks);
    }

    /** The film's actors have been drawn — the scene may add its own overlay to the same pass. */
    @Inject(method = "render", at = @At("TAIL"))
    private void bbs_physics$onRender(WorldRenderContext context, CallbackInfo info)
    {
        FilmScenes.onRender((BaseFilmController) (Object) this, context);
    }

    /**
     * The film is done. Note that {@code WorldFilmController} overrides this without calling
     * {@code super}, so playback in the world is caught by {@link WorldFilmControllerMixin}
     * instead — the native world has to be released either way.
     */
    @Inject(method = "shutdown", at = @At("TAIL"))
    private void bbs_physics$onShutdown(CallbackInfo info)
    {
        FilmScenes.onShutdown((BaseFilmController) (Object) this);
    }
}
