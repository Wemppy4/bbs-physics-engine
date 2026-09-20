package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Seeds the world point of a newly inserted impulse before BBS records its first undo state. */
@Mixin(UIClips.class)
public abstract class UIClipsMixin
{
    @Shadow
    private IUIClipsDelegate delegate;

    /** Seed only freshly created clips, before insertion records their data for undo/redo. */
    @ModifyArgs(
        method = "addClip(Lmchorse/bbs_mod/resources/Link;III)V",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/film/UIClips;addClip(Lmchorse/bbs_mod/utils/clips/Clip;III)V")
    )
    private void bbs_physics$initializeImpulsePoint(Args args)
    {
        if (!(args.get(0) instanceof ImpulseActionClip impulse) || this.delegate == null)
        {
            return;
        }

        Film film = this.delegate.getFilm();

        if (film == null)
        {
            return;
        }

        UIClips self = (UIClips) (Object) this;

        for (Replay replay : film.replays.getList())
        {
            if (replay.actions == self.getClips())
            {
                int tick = replay.getTick(args.<Integer>get(1));

                impulse.point.set(new Point(
                    replay.keyframes.x.interpolate(tick),
                    replay.keyframes.y.interpolate(tick),
                    replay.keyframes.z.interpolate(tick)));

                return;
            }
        }
    }

}
