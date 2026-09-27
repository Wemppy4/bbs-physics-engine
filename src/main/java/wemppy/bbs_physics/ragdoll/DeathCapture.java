package wemppy.bbs_physics.ragdoll;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionRecorder;
import mchorse.bbs_mod.entity.ActorEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import wemppy.bbs_physics.mixin.ActionManagerAccessor;
import wemppy.bbs_physics.mixin.ActionRecorderAccessor;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-confirmed deaths on the active take's clock, independent of the damage source. */
public final class DeathCapture
{
    public static final Identifier ID = new Identifier("bbs_physics", "death");
    private static final Map<ActionRecorder, String> owners = new WeakHashMap<>();
    private static final ThreadLocal<Impact> impact = new ThreadLocal<>();

    /** Optional integration data, scoped to exactly the damaged entity. */
    public record Impact(Entity victim, Vec3d point, Vec3d direction) {}

    public static Impact setImpact(Impact value)
    {
        Impact previous = impact.get();
        if (value == null) impact.remove(); else impact.set(value);
        return previous;
    }

    public static void begin(ActionRecorder recorder, int replayIndex)
    {
        var replays = recorder.getFilm().replays.getList();
        if (replayIndex >= 0 && replayIndex < replays.size()) owners.put(recorder, replays.get(replayIndex).getId());
    }

    public static void record(LivingEntity victim, DamageSource source)
    {
        if (victim.getWorld().isClient || !(victim instanceof ActorEntity || victim instanceof ServerPlayerEntity)) return;
        Impact hit = impact.get();
        if (hit != null && hit.victim() != victim) hit = null;
        Vec3d point = hit == null ? victim.getBoundingBox().getCenter() : hit.point();
        Vec3d from = source.getPosition();
        Vec3d direction = hit != null ? hit.direction() : from == null ? Vec3d.ZERO : victim.getPos().subtract(from).normalize();

        for (var entry : ((ActionManagerAccessor) BBSMod.getActions()).bbs_physics$getRecorders().entrySet())
        {
            var player = entry.getKey();
            var recorder = entry.getValue();
            var clock = (ActionRecorderAccessor) recorder;
            String owner = owners.get(recorder);
            if (owner == null || clock.bbs_physics$getCountdown() > 0 || player.getWorld() != victim.getWorld()
                || !ServerPlayNetworking.canSend(player, ID)) continue;
            String replay;
            if (victim == player) replay = owner;
            else if (victim instanceof ActorEntity actor && recorder.getFilm().getId().equals(actor.getFilmId())) replay = actor.getReplayId();
            else continue;

            var buf = PacketByteBufs.create();
            buf.writeString(recorder.getFilm().getId());
            buf.writeString(replay);
            buf.writeUuid(victim.getUuid());
            buf.writeString(owner);
            // BBS starts ActionRecorder at zero even when the take starts later in the film.
            // 1.20.4 adds covariant PacketByteBuf write overrides absent in 1.20.1.
            // Invoke the stable Netty signatures for this multi-version artifact.
            io.netty.buffer.ByteBuf payload = buf;
            payload.writeInt(clock.bbs_physics$getTick() - recorder.getInitialTick());
            payload.writeDouble(point.x).writeDouble(point.y).writeDouble(point.z);
            payload.writeDouble(direction.x).writeDouble(direction.y).writeDouble(direction.z);
            ServerPlayNetworking.send(player, ID, buf);
        }
    }

    private DeathCapture() {}
}
