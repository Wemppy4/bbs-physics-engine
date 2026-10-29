package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.forms.PhysicsTracks;

import java.util.List;

@Mixin(value = UIReplaysEditorUtils.class, remap = false)
public class PhysicsTrackSheetsMixin
{
    @Inject(method = "buildSheets", at = @At("RETURN"))
    private static void bbs_physics$group(List<TrackDescriptor> catalog, List<UIKeyframeSheet> sheets,
        FormProperties properties, CallbackInfo info)
    {
        PhysicsTracks.group(sheets);
    }
}
