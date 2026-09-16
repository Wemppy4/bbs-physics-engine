package wemppy.bbs_physics.engine;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Standalone check: exercises staging isolation, reset and capacity growth without natives. */
public final class PhysicsCacheCheck
{
    public static void main(String[] args)
    {
        PhysicsCache cache = new PhysicsCache();
        int parent = cache.addChannel();
        int child = cache.addChannel();
        int cloth = cache.addChannel(4);
        cache.seal();

        Vector3f position = new Vector3f();
        Quaternionf rotation = new Quaternionf();

        for (int tick = 0; tick < 600; tick++)
        {
            PhysicsCache pending = cache.beginFrame(tick);
            require(!cache.has(tick), "staging must not advance playback");
            require(!pending.read(tick, parent, position, rotation), "unwritten parent");
            cache.write(tick, parent, new Vector3f(tick, 2, 3), rotation, 0.25F);
            require(pending.read(tick, parent, position, rotation), "parent available to child");
            require(position.x == tick && pending.readAuthority(tick, parent) == 0.25F, "parent data");
            require(!cache.read(tick, parent, position, rotation), "public read is still atomic");
            require(!pending.read(tick, child, position, rotation), "child remains silent");
            pending.write(tick, parent, new Vector3f(-100), rotation, 1F);
            require(pending.read(tick, parent, position, rotation) && position.x == tick, "view cannot write");
            cache.writeFloats(tick, cloth, new float[] {1, 2, 3, 0.5F});
            float[] vertices = new float[4];
            require(pending.readFloats(tick, cloth, vertices) && vertices[3] == 0.5F, "wide channel");
            cache.commit(tick);
            require(cache.read(tick, parent, position, rotation) && position.x == tick, "committed data");
        }

        cache.clear();
        PhysicsCache pending = cache.beginFrame(0);
        require(!pending.read(0, parent, position, rotation), "reset must not expose old recording");
        require(!pending.readFloats(0, cloth, new float[4]), "wide channel reset");
        cache.write(0, parent, new Vector3f(9), rotation, 0);
        pending = cache.beginFrame(0);
        require(!pending.read(0, parent, position, rotation), "post-step pass clears pre-step staging");
        require(!cache.has(0), "staging passes never commit");
        cache.write(0, child, new Vector3f(12), rotation, 0);
        cache.commit(0);
        require(!cache.read(0, parent, position, rotation), "unwritten channel stays silent after commit");
        require(cache.read(0, child, position, rotation) && position.x == 12, "child survives reset");
        System.out.println("PhysicsCacheCheck: passed (600 frames, staging, reset, wide channels)");
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
