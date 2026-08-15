package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.forms.entities.IEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Where an actor stood before physics borrowed it, so it can be put back exactly.
 *
 * <p>Re-simulating a stretch of film means standing the cast on every tick of that stretch in turn,
 * and the only way to stand an actor on a tick is to write the keyframes into the entity BBS is
 * about to draw. Borrowing it is fine; leaving it borrowed is not — the frame would draw the actor
 * wherever the last simulated step happened to leave them, which on a long catch-up is somewhere
 * else entirely.</p>
 *
 * <p>Both halves of every value are kept, the previous tick's as well as the current one's. The
 * renderer draws between them, and BBS sets the pair differently depending on whether the film is
 * playing or paused — so putting back only what is visible would leave a paused actor rocking
 * between two ticks. Copying the pair verbatim needs no opinion about which rule was in force.</p>
 */
public final class ActorState
{
    private double x;
    private double y;
    private double z;
    private double prevX;
    private double prevY;
    private double prevZ;

    private float yaw;
    private float pitch;
    private float headYaw;
    private float bodyYaw;
    private float prevYaw;
    private float prevPitch;
    private float prevHeadYaw;
    private float prevBodyYaw;

    private float velocityX;
    private float velocityY;
    private float velocityZ;
    private float fallDistance;

    public void capture(IEntity entity)
    {
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        this.prevX = entity.getPrevX();
        this.prevY = entity.getPrevY();
        this.prevZ = entity.getPrevZ();

        this.yaw = entity.getYaw();
        this.pitch = entity.getPitch();
        this.headYaw = entity.getHeadYaw();
        this.bodyYaw = entity.getBodyYaw();
        this.prevYaw = entity.getPrevYaw();
        this.prevPitch = entity.getPrevPitch();
        this.prevHeadYaw = entity.getPrevHeadYaw();
        this.prevBodyYaw = entity.getPrevBodyYaw();

        Vec3d velocity = entity.getVelocity();

        this.velocityX = (float) velocity.x;
        this.velocityY = (float) velocity.y;
        this.velocityZ = (float) velocity.z;
        this.fallDistance = entity.getFallDistance();
    }

    public void restore(IEntity entity)
    {
        entity.setPosition(this.x, this.y, this.z);
        entity.setPrevX(this.prevX);
        entity.setPrevY(this.prevY);
        entity.setPrevZ(this.prevZ);

        entity.setYaw(this.yaw);
        entity.setPitch(this.pitch);
        entity.setHeadYaw(this.headYaw);
        entity.setBodyYaw(this.bodyYaw);
        entity.setPrevYaw(this.prevYaw);
        entity.setPrevPitch(this.prevPitch);
        entity.setPrevHeadYaw(this.prevHeadYaw);
        entity.setPrevBodyYaw(this.prevBodyYaw);

        entity.setVelocity(this.velocityX, this.velocityY, this.velocityZ);
        entity.setFallDistance(this.fallDistance);
    }
}
