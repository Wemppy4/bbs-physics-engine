package wemppy.bbs_physics.engine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * What a scene's rigs carry from one tick to the next that neither the recording nor Jolt's own
 * snapshot does, written down at a checkpoint and read back to pick the simulation up there.
 *
 * <p>A plain sequence: every rig writes its memory in one order and reads it back in the same order,
 * a value at a time, bit for bit — a float comes back as the very float that went in, or the run
 * picked up from it would drift from the run that made it. References (a shape's description, say)
 * go into a side list, kept as they are.</p>
 *
 * <p>Each rig opens its part with {@link #mark} and the reader checks it with {@link #expect}: a rig
 * that reads back one value more or less than it wrote fails at the next mark, loudly, instead of
 * handing every rig after it someone else's numbers.</p>
 */
public final class RigMemory
{
    private long[] values = new long[64];
    private Object[] references = new Object[0];
    private int size;
    private int referenceCount;
    private int cursor;
    private int referenceCursor;

    /* Writing */

    public RigMemory write(long value)
    {
        if (this.size == this.values.length)
        {
            this.values = Arrays.copyOf(this.values, this.values.length * 2);
        }

        this.values[this.size++] = value;

        return this;
    }

    public RigMemory write(int value)
    {
        return this.write((long) value);
    }

    public RigMemory write(boolean value)
    {
        return this.write(value ? 1L : 0L);
    }

    public RigMemory write(float value)
    {
        return this.write((long) Float.floatToRawIntBits(value));
    }

    public RigMemory write(double value)
    {
        return this.write(Double.doubleToRawLongBits(value));
    }

    public RigMemory write(Vector3f value)
    {
        return this.write(value.x).write(value.y).write(value.z);
    }

    public RigMemory write(Quaternionf value)
    {
        return this.write(value.x).write(value.y).write(value.z).write(value.w);
    }

    public RigMemory write(Matrix4f value)
    {
        this.write(value.m00()).write(value.m01()).write(value.m02()).write(value.m03());
        this.write(value.m10()).write(value.m11()).write(value.m12()).write(value.m13());
        this.write(value.m20()).write(value.m21()).write(value.m22()).write(value.m23());

        return this.write(value.m30()).write(value.m31()).write(value.m32()).write(value.m33());
    }

    /** Keeps {@code value} as it is — for something immutable, or never changed once handed over. */
    public RigMemory keep(Object value)
    {
        if (this.referenceCount == this.references.length)
        {
            this.references = Arrays.copyOf(this.references, Math.max(4, this.references.length * 2));
        }

        this.references[this.referenceCount++] = value;

        return this;
    }

    /** Opens one rig's part of the memory; see {@link #expect}. */
    public RigMemory mark(String part)
    {
        return this.write((long) part.hashCode() << 32 | 0x5EC7L);
    }

    /* Reading */

    /** Starts reading from the beginning. */
    public RigMemory rewind()
    {
        this.cursor = 0;
        this.referenceCursor = 0;

        return this;
    }

    public long readLong()
    {
        if (this.cursor >= this.size)
        {
            throw new IllegalStateException("A checkpoint was read past its end");
        }

        return this.values[this.cursor++];
    }

    public int readInt()
    {
        return (int) this.readLong();
    }

    public boolean readBoolean()
    {
        return this.readLong() != 0L;
    }

    public float readFloat()
    {
        return Float.intBitsToFloat((int) this.readLong());
    }

    public double readDouble()
    {
        return Double.longBitsToDouble(this.readLong());
    }

    public Vector3f read(Vector3f out)
    {
        return out.set(this.readFloat(), this.readFloat(), this.readFloat());
    }

    public Quaternionf read(Quaternionf out)
    {
        return out.set(this.readFloat(), this.readFloat(), this.readFloat(), this.readFloat());
    }

    public Matrix4f read(Matrix4f out)
    {
        float[] m = new float[16];

        for (int i = 0; i < 16; i++)
        {
            m[i] = this.readFloat();
        }

        return out.set(m);
    }

    @SuppressWarnings("unchecked")
    public <T> T kept()
    {
        if (this.referenceCursor >= this.referenceCount)
        {
            throw new IllegalStateException("A checkpoint was read past its end");
        }

        return (T) this.references[this.referenceCursor++];
    }

    /** Checks that the next rig's part starts here — that everything before it read back exactly what it wrote. */
    public void expect(String part)
    {
        long mark = (long) part.hashCode() << 32 | 0x5EC7L;

        if (this.readLong() != mark)
        {
            throw new IllegalStateException("A checkpoint is out of step at '" + part + "': a rig read back more or less than it wrote");
        }
    }

    /** Whether everything written has been read back. */
    public boolean isDrained()
    {
        return this.cursor == this.size && this.referenceCursor == this.referenceCount;
    }

    /** Roughly what this holds, for the checkpoints' memory budget. */
    public long bytes()
    {
        return this.size * 8L + this.referenceCount * 16L;
    }

    /** The numbers written, in order, marks included — for keeping a checkpoint on disk. */
    public long[] numbers()
    {
        return Arrays.copyOf(this.values, this.size);
    }

    /** The objects kept by reference, in order — for keeping a checkpoint on disk. */
    public Object[] keptObjects()
    {
        return Arrays.copyOf(this.references, this.referenceCount);
    }

    /** A memory holding what {@link #numbers} and {@link #keptObjects} gave out, ready to be read. */
    public static RigMemory of(long[] numbers, Object[] kept)
    {
        RigMemory memory = new RigMemory();

        memory.values = Arrays.copyOf(numbers, numbers.length);
        memory.size = numbers.length;
        memory.references = Arrays.copyOf(kept, kept.length);
        memory.referenceCount = kept.length;

        return memory;
    }

    /** Drops the spare room left by growing, once nothing more will be written. */
    public RigMemory trim()
    {
        this.values = Arrays.copyOf(this.values, this.size);
        this.references = Arrays.copyOf(this.references, this.referenceCount);

        return this;
    }
}
