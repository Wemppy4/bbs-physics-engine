package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;

/**
 * Who collides with whom. Every body belongs to one object layer, and this table decides which
 * pairs of layers are even considered — the cheapest way to say "the actor's own limbs must not
 * fight each other" without testing it thousands of times a second.
 *
 * <p>The broad phase gets a coarser split of its own: bodies that never move are kept in a
 * separate tree from bodies that do, so the static half is not rebuilt every step. Layers that
 * behave the same way to the broad phase share a broad phase layer, which is why there are fewer
 * of those than object layers.</p>
 */
public final class PhysicsLayers
{
    /** Never moves: the world's blocks and any decoration pinned in place. */
    public static final int STATIC = 0;

    /** Props: bodies with a life of their own, and the same bodies while keyframes drive them. */
    public static final int MOVING = 1;

    /**
     * An actor's bones. Separate from {@link #MOVING} purely to switch pairs off, not because a
     * bone collides by different rules: a bone is always kinematic, and two bodies that both refuse
     * to be pushed can never resolve a contact. Leaving bones in the props layer had every bone of
     * an actor testing against every other one — a forty-bone character is eight hundred permanently
     * overlapping pairs that can never produce a single impulse, and several actors would exhaust
     * the world's contact budget on nothing at all. The same argument retires bone against static.
     */
    public static final int BONE = 2;

    public static final int OBJECT_LAYERS = 3;

    private static final int BP_STATIC = 0;
    private static final int BP_MOVING = 1;
    private static final int BROAD_PHASE_LAYERS = 2;

    private PhysicsLayers()
    {}

    /**
     * Which object layers test against each other. The table starts with everything switched off,
     * so what is not listed here never reaches the narrow phase. Static against static is
     * deliberately absent for the same reason bone against bone is: two things that cannot be
     * pushed can never do anything to each other, and asking is pure waste.
     */
    public static ObjectLayerPairFilterTable newPairFilter()
    {
        ObjectLayerPairFilterTable filter = new ObjectLayerPairFilterTable(OBJECT_LAYERS);

        filter.enableCollision(STATIC, MOVING);
        filter.enableCollision(MOVING, MOVING);
        filter.enableCollision(MOVING, BONE);

        return filter;
    }

    public static BroadPhaseLayerInterfaceTable newBroadPhaseLayers()
    {
        BroadPhaseLayerInterfaceTable table = new BroadPhaseLayerInterfaceTable(OBJECT_LAYERS, BROAD_PHASE_LAYERS);

        table.mapObjectToBroadPhaseLayer(STATIC, BP_STATIC);
        table.mapObjectToBroadPhaseLayer(MOVING, BP_MOVING);
        table.mapObjectToBroadPhaseLayer(BONE, BP_MOVING);

        return table;
    }

    public static ObjectVsBroadPhaseLayerFilterTable newBroadPhaseFilter(BroadPhaseLayerInterfaceTable layers, ObjectLayerPairFilterTable pairs)
    {
        return new ObjectVsBroadPhaseLayerFilterTable(layers, BROAD_PHASE_LAYERS, pairs, OBJECT_LAYERS);
    }
}
