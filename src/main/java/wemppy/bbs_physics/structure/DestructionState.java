package wemppy.bbs_physics.structure;

import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.engine.PhysicsCache;

import java.util.BitSet;
import java.util.List;

/** Recorded block poses in scene coordinates, independent of the actor's later animation. */
public final class DestructionState
{
    public final String structure;
    public final List<BlockPos> blocks;
    public final Vector3f offset;
    public final Vector3f scale;
    private final Vector3f[] previous;
    private final Vector3f[] current;
    private final Quaternionf[] previousRotation;
    private final Quaternionf[] currentRotation;
    private final BitSet detached = new BitSet();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private boolean recorded;

    public DestructionState(String structure, List<BlockPos> blocks, Vector3f offset, Vector3f scale)
    {
        this.structure = structure;
        this.blocks = List.copyOf(blocks);
        this.offset = new Vector3f(offset);
        this.scale = new Vector3f(scale);
        this.previous = new Vector3f[blocks.size()];
        this.current = new Vector3f[blocks.size()];
        this.previousRotation = new Quaternionf[blocks.size()];
        this.currentRotation = new Quaternionf[blocks.size()];
        for (int i = 0; i < blocks.size(); i++)
        {
            this.previous[i] = new Vector3f();
            this.current[i] = new Vector3f();
            this.previousRotation[i] = new Quaternionf();
            this.currentRotation[i] = new Quaternionf();
        }
    }

    public void read(PhysicsCache cache, int[] channels, int tick, boolean teleport)
    {
        boolean first = teleport || !this.recorded;
        this.detached.clear();
        for (int i = 0; i < channels.length; i++)
        {
            this.previous[i].set(this.current[i]);
            this.previousRotation[i].set(this.currentRotation[i]);
            if (!cache.read(tick, channels[i], this.current[i], this.currentRotation[i]))
            {
                this.clear();
                return;
            }
            if (first)
            {
                this.previous[i].set(this.current[i]);
                this.previousRotation[i].set(this.currentRotation[i]);
            }
            this.detached.set(i, cache.readAuthority(tick, channels[i]) == 0F);
        }
        this.recorded = true;
    }

    public void clear()
    {
        this.recorded = false;
        this.detached.clear();
    }

    public boolean isBroken() { return this.recorded && !this.detached.isEmpty(); }
    public BitSet detached() { return (BitSet) this.detached.clone(); }

    /** The form's full frame, with translation relative to the same origin as the recorded poses. */
    public void renderFrame(Matrix4f frame)
    {
        this.inverse.set(frame).invert();
    }

    public Matrix4f transform(int block, float transition, Matrix4f out)
    {
        BlockPos pos = this.blocks.get(block);
        this.previous[block].lerp(this.current[block], transition, this.position);
        this.previousRotation[block].slerp(this.currentRotation[block], transition, this.rotation);
        return out.set(this.inverse).translate(this.position).rotate(this.rotation).scale(this.scale)
            .translate(-pos.getX() - 0.5F - this.offset.x,
                -pos.getY() - 0.5F - this.offset.y, -pos.getZ() - 0.5F - this.offset.z);
    }
}
