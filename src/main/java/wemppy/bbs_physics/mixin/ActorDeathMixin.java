package wemppy.bbs_physics.mixin;

import wemppy.bbs_physics.ragdoll.DeathCapture;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class ActorDeathMixin
{
    @Inject(method = "onDeath", at = @At("TAIL"))
    private void bbs_physics$recordDeath(DamageSource source, CallbackInfo ci)
    {
        DeathCapture.record((LivingEntity) (Object) this, source);
    }
}
