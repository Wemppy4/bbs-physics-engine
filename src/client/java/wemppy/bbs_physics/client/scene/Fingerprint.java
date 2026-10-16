package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ByteArrayType;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.data.types.FloatType;
import mchorse.bbs_mod.data.types.IntArrayType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.LongArrayType;
import mchorse.bbs_mod.data.types.LongType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.ShortArrayType;
import mchorse.bbs_mod.data.types.ShortType;
import mchorse.bbs_mod.data.types.StringType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * A digest of what something was made from, fed in a canonical order: two inputs that say the same
 * thing digest the same, whatever order they were built in — a map's keys go in sorted, numbers go
 * in by their bits — and two that say anything different do not.
 *
 * <p>Every value goes in with a tag and, where it has one, a length, so that no two different
 * sequences of values can run together into the same bytes.</p>
 */
final class Fingerprint
{
    private final MessageDigest digest;

    Fingerprint()
    {
        try
        {
            this.digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    Fingerprint put(String value)
    {
        byte[] bytes = (value == null ? "\u0000null" : value).getBytes(StandardCharsets.UTF_8);

        this.tag('s').putInt(bytes.length);
        this.digest.update(bytes);

        return this;
    }

    Fingerprint put(int value)
    {
        return this.tag('i').putInt(value);
    }

    Fingerprint put(long value)
    {
        this.tag('l');

        for (int shift = 56; shift >= 0; shift -= 8)
        {
            this.digest.update((byte) (value >>> shift));
        }

        return this;
    }

    Fingerprint put(boolean value)
    {
        return this.tag(value ? 'T' : 'F');
    }

    Fingerprint put(float value)
    {
        return this.tag('f').putInt(Float.floatToRawIntBits(value));
    }

    Fingerprint put(double value)
    {
        return this.tag('d').put(Double.doubleToRawLongBits(value));
    }

    Fingerprint put(byte[] value)
    {
        this.tag('b').putInt(value == null ? -1 : value.length);

        if (value != null)
        {
            this.digest.update(value);
        }

        return this;
    }

    /** A piece of BBS data, whole, with a map's keys in sorted order. */
    Fingerprint put(BaseType data)
    {
        if (data == null)
        {
            return this.tag('0');
        }

        if (data instanceof MapType map)
        {
            List<String> keys = new ArrayList<>(map.keys());

            Collections.sort(keys);
            this.tag('M').putInt(keys.size());

            for (String key : keys)
            {
                this.put(key).put(map.get(key));
            }

            return this;
        }

        if (data instanceof ListType list)
        {
            this.tag('L').putInt(list.size());

            for (BaseType element : list.elements)
            {
                this.put(element);
            }

            return this;
        }

        if (data instanceof StringType string)
        {
            return this.tag('S').put(string.value);
        }

        if (data instanceof FloatType number)
        {
            return this.tag('F').put(number.value);
        }

        if (data instanceof DoubleType number)
        {
            return this.tag('D').put(number.value);
        }

        if (data instanceof IntType number)
        {
            return this.tag('I').put(number.value);
        }

        if (data instanceof LongType number)
        {
            return this.tag('J').put(number.value);
        }

        if (data instanceof ShortType number)
        {
            return this.tag('H').put((int) number.value);
        }

        if (data instanceof ByteType number)
        {
            return this.tag('B').put((int) number.value);
        }

        if (data instanceof ByteArrayType array)
        {
            return this.tag('[').put(array.value);
        }

        if (data instanceof IntArrayType array)
        {
            this.tag('{').putInt(array.value.length);

            for (int value : array.value)
            {
                this.putInt(value);
            }

            return this;
        }

        if (data instanceof LongArrayType array)
        {
            this.tag('(').putInt(array.value.length);

            for (long value : array.value)
            {
                this.put(value);
            }

            return this;
        }

        if (data instanceof ShortArrayType array)
        {
            this.tag('<').putInt(array.value.length);

            for (short value : array.value)
            {
                this.putInt(value);
            }

            return this;
        }

        /* A kind of data this does not know: whatever it says, it says through its own words. */
        return this.tag('?').put(data.getClass().getName()).put(data.toString());
    }

    /** The digest of everything put in, and this fingerprint is spent. */
    byte[] digest()
    {
        return this.digest.digest();
    }

    /** The first {@code bytes} bytes of the digest, as hexadecimal — a name for a file, say. */
    String hex(int bytes)
    {
        return HexFormat.of().formatHex(this.digest(), 0, bytes);
    }

    private Fingerprint tag(char tag)
    {
        this.digest.update((byte) tag);

        return this;
    }

    private Fingerprint putInt(int value)
    {
        this.digest.update((byte) (value >>> 24));
        this.digest.update((byte) (value >>> 16));
        this.digest.update((byte) (value >>> 8));
        this.digest.update((byte) value);

        return this;
    }
}
