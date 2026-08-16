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

    /**
     * Meets nothing at all. Where a physics body goes when nothing inside it is marked up as
     * collidable: it still falls, it just has nothing to land on — the same thing a rigid body
     * without a collider does in Unity, and deliberately so (§5.1). Handing it a box it was never
     * asked for would be a lie about a shape the author did not describe; the debug overlay draws
     * such a body in its own colour so it does not read as broken.
     */
    public static final int GHOST = 3;

    /**
     * Soft bodies — cloth. A layer of its own for one load-bearing reason: a soft body is the one
     * kind of body that looks for its <em>own</em> contacts (a rigid body that finds a soft one in
     * the broad phase does nothing about it), so this layer's pairs decide everything cloth can
     * touch. It meets the world, the props and the actor's bones — a cape lands on shoulders —
     * but not other cloth: sheet-on-sheet contact costs a vertex-against-vertex narrow phase that
     * nothing filmed so far has needed.
     */
    public static final int CLOTH = 4;

    public static final int OBJECT_LAYERS = 5;

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

        /* Cloth against bones is not the waste the bone-on-bone ban is about: the sheet is the one
         * asking, it can be pushed, and the pair is exactly a cape lying on shoulders. */
        filter.enableCollision(CLOTH, STATIC);
        filter.enableCollision(CLOTH, MOVING);
        filter.enableCollision(CLOTH, BONE);

        return filter;
    }

    public static BroadPhaseLayerInterfaceTable newBroadPhaseLayers()
    {
        BroadPhaseLayerInterfaceTable table = new BroadPhaseLayerInterfaceTable(OBJECT_LAYERS, BROAD_PHASE_LAYERS);

        table.mapObjectToBroadPhaseLayer(STATIC, BP_STATIC);
        table.mapObjectToBroadPhaseLayer(MOVING, BP_MOVING);
        table.mapObjectToBroadPhaseLayer(BONE, BP_MOVING);
        table.mapObjectToBroadPhaseLayer(GHOST, BP_MOVING);
        table.mapObjectToBroadPhaseLayer(CLOTH, BP_MOVING);

        return table;
    }

    public static ObjectVsBroadPhaseLayerFilterTable newBroadPhaseFilter(BroadPhaseLayerInterfaceTable layers, ObjectLayerPairFilterTable pairs)
    {
        /* The second count is the number of OBJECT layers, the last one the number of broad phase
         * layers — the reverse of what the parameter names suggest. Handing them over the other way
         * round builds rows for the first two object layers only, and every layer past those
         * collides with nothing, silently: the table answers false for every tree, so the broad
         * phase never returns a single candidate. It went unseen for the whole of Э1–Э4 because the
         * clipped layers were BONE and GHOST — a kinematic bone never queries for itself (dynamic
         * bodies find it through their own, intact row) and GHOST is supposed to meet nothing. A
         * soft body is the first thing that queries the broad phase from one of those rows, which
         * is how cloth fell through the world and named this. Proven by probing shouldCollide on
         * both argument orders — see the ClothSmoke stands. */
        return new ObjectVsBroadPhaseLayerFilterTable(layers, OBJECT_LAYERS, pairs, BROAD_PHASE_LAYERS);
    }
}
