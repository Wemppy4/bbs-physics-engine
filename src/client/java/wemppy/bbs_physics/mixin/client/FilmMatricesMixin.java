package wemppy.bbs_physics.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import wemppy.bbs_physics.client.scene.SceneActor;

@Mixin(BaseFilmController.class)
public abstract class FilmMatricesMixin
{
    @ModifyVariable(method = "getTotalMatrix(Lio/netty/util/collection/IntObjectMap;Lmchorse/bbs_mod/forms/forms/utils/Anchor;Lorg/joml/Matrix4f;DDDFI)Lmchorse/bbs_mod/utils/Pair;",
        at = @At("HEAD"), argsOnly = true)
    private static Anchor bbs_physics$releaseAnchor(Anchor value)
    {
        return SceneActor.releaseAnchor(value);
    }
}
