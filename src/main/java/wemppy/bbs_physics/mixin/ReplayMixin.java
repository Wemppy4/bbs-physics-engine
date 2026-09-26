package wemppy.bbs_physics.mixin;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.ragdoll.DeathReplay;

@Mixin(Replay.class)
public class ReplayMixin implements DeathReplay
{
    @Unique private final ValueBoolean bbs_physics$death = new ValueBoolean("bbs_physics:death", false);
    @Unique private final ValueFloat bbs_physics$kick = new ValueFloat("bbs_physics:death_strength", 1F, 0F, Float.MAX_VALUE);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs_physics$values(String id, CallbackInfo ci)
    {
        ValueGroup self = (ValueGroup) (Object) this;
        self.add(this.bbs_physics$death);
        self.add(this.bbs_physics$kick);
    }

    public ValueBoolean bbs_physics$deathEnabled() { return this.bbs_physics$death; }
    public ValueFloat bbs_physics$deathStrength() { return this.bbs_physics$kick; }
}
