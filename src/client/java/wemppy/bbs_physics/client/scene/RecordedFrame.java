package wemppy.bbs_physics.client.scene;

import org.joml.Matrix4f;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PoseFrame;

/** One reference frame per rig; shared by every bone of a ragdoll. */
final class RecordedFrame
{
    private final int channel;
    private final PoseFrame pose;
    private final float[] values = new float[17];
    private final Matrix4f matrix = new Matrix4f();
    private boolean recorded;

    RecordedFrame(FilmScene scene, PoseFrame pose)
    {
        this.channel = scene.addChannel(17);
        this.pose = pose;
    }

    void write(PhysicsCache cache, int tick, Matrix4f frame)
    {
        frame.get(this.values);
        this.values[16] = 0F;
        cache.writeFloats(tick, this.channel, this.values);
    }

    void read(PhysicsCache cache, int tick, boolean teleport)
    {
        boolean found = cache.readFloats(tick, this.channel, this.values);
        if (found) this.pose.set(this.matrix.set(this.values), teleport || !this.recorded);
        this.recorded = found;
    }
}
