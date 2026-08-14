package mchorse.bbs_physics.engine;

import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;

/**
 * Who collides with whom. Every body belongs to one object layer, and this table decides which
 * pairs of layers are even considered — the cheapest way to say "the actor's own cloth must not
 * fight the actor's own limbs" without testing it thousands of times a second.
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

    /**
     * Anything that moves — dynamic bodies and the kinematic ones driven by the animation. They
     * share a layer because a prop and an animated limb collide by exactly the same rules; what
     * separates them is their motion type, not their layer.
     */
    public static final int MOVING = 1;

    public static final int OBJECT_LAYERS = 2;

    private static final int BP_STATIC = 0;
    private static final int BP_MOVING = 1;
    private static final int BROAD_PHASE_LAYERS = 2;

    private PhysicsLayers()
    {}

    /**
     * Which object layers test against each other. Static against static is deliberately absent:
     * two things that never move can never start touching, and asking is pure waste.
     */
    public static ObjectLayerPairFilterTable newPairFilter()
    {
        ObjectLayerPairFilterTable filter = new ObjectLayerPairFilterTable(OBJECT_LAYERS);

        filter.enableCollision(STATIC, MOVING);
        filter.enableCollision(MOVING, MOVING);

        return filter;
    }

    public static BroadPhaseLayerInterfaceTable newBroadPhaseLayers()
    {
        BroadPhaseLayerInterfaceTable table = new BroadPhaseLayerInterfaceTable(OBJECT_LAYERS, BROAD_PHASE_LAYERS);

        table.mapObjectToBroadPhaseLayer(STATIC, BP_STATIC);
        table.mapObjectToBroadPhaseLayer(MOVING, BP_MOVING);

        return table;
    }

    public static ObjectVsBroadPhaseLayerFilterTable newBroadPhaseFilter(BroadPhaseLayerInterfaceTable layers, ObjectLayerPairFilterTable pairs)
    {
        return new ObjectVsBroadPhaseLayerFilterTable(layers, BROAD_PHASE_LAYERS, pairs, OBJECT_LAYERS);
    }
}
