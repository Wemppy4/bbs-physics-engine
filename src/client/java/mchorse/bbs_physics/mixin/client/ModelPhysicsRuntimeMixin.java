package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsRuntime;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_physics.client.chain.ChainMute;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps BBS's own chain solver off the bones our chain modifier drives.
 *
 * <p>The whole of it is one redirect: the model's stored physics config is handed to the solver
 * with the claimed chains taken out of it (see {@link ChainMute}), so the strands we own are
 * simply not chains as far as BBS is concerned, and the ones we do not own keep working exactly as
 * before. Cancelling the solver outright was the obvious alternative and is wrong — an author may
 * keep a skirt on the old physics while the hair moves to ours.</p>
 *
 * <p>Redirected at the <em>read</em> of the config rather than at its compilation, which is where
 * this belongs anyway: {@code ModelPhysicsCache} is package-private, so its type cannot even be
 * named from here, while the value read sits one line earlier.</p>
 *
 * <p>🔴 <b>The target descriptor is {@code ValueData.get()Ljava/lang/Object;}</b> — the owner of
 * the subclass with the erasure of the generic base, and both halves have to be right at once.
 * Written as {@code ValueData.get()LBaseType;} it matches nothing ("Scanned 0 target(s)"), because
 * {@code get()} is declared on {@code BaseValueBasic<T>} and erases to Object; written against
 * {@code BaseValueBasic} it matches nothing either, because javac emits the call with the static
 * type at the call site as its owner. Both were tried, both crashed the game on the first model
 * drawn. Read the bytecode, do not reason about the source: {@code javap -c} on BBS's own jar says
 * {@code invokevirtual ValueData.get:()Ljava/lang/Object;} and that is the whole answer.</p>
 */
@Mixin(ModelPhysicsRuntime.class)
public class ModelPhysicsRuntimeMixin
{
    @Redirect(
        method = "apply",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/settings/values/core/ValueData;get()Ljava/lang/Object;"
        ),
        remap = false
    )
    private static Object bbs_physics$muteClaimedChains(ValueData value, IEntity entity, ModelInstance instance, float transition, Matrix4f baseTransform)
    {
        Object data = value.get();

        /* Only the model's own physics config, and only when there is something to filter out. Any
         * other value this method happens to read passes through untouched. */
        if (data instanceof MapType map && instance != null && instance.form instanceof ModelForm form && form.physics == value)
        {
            return ChainMute.filter(form, map);
        }

        return data;
    }
}
