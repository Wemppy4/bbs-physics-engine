package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.CollisionGroup;
import com.github.stephengold.joltjni.GroupFilterTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Who, inside one actor, is excused from colliding with whom.
 *
 * <p>Jolt's layer table answers this between <em>kinds</em> of body — props against bones, bones
 * against the world — and that is the wrong grain for a character, because the pairs that must not
 * collide here are all in the same two layers. So every body an actor's skeleton is made of, its
 * kinematic bones and its ragdolls' parts alike, is given the same collision group id and this one
 * table; two bodies of the same group ask it, and bodies of different groups (a different actor, a
 * prop) never do and collide normally.</p>
 *
 * <p><b>What it excuses, and why each pair would otherwise be a disaster rather than a waste.</b></p>
 *
 * <ul>
 * <li><b>A ragdoll part against a kinematic bone of its own actor.</b> The layer table has props
 * meet bones on purpose — that is a crate landing on a shoulder — and a released ragdoll part is in
 * the props layer, so without this it meets the bones of the very character it belongs to. Those
 * shapes are neighbours by construction: they were cut from one skeleton and they overlap wherever
 * two bones join. A kinematic body cannot be pushed, so the whole of resolving that overlap is
 * dumped on the part, and it is thrown out of its own character. This is exactly the case Р9 exists
 * for — collision on the body and the head, ragdoll on the head alone.</li>
 * <li><b>A ragdoll part against the part it is jointed to.</b> Neighbours share a joint, meet at it
 * by design and always will; letting them collide has every joint permanently fighting its own
 * limits.</li>
 * </ul>
 *
 * <p>Everything else about an actor still collides, which is the point of not simply switching the
 * pair off in the layer table: parts of one ragdoll that are <em>not</em> jointed keep each other
 * out (an arm does not fold through the chest), and another actor's animated hand can still shove a
 * fallen character, because that hand is in a different group.</p>
 *
 * <p>Native, and held by Jolt by pointer for as long as the bodies exist — so whoever builds one
 * keeps it in a field until the world goes.</p>
 */
public class ActorCollisionGroup
{
    private final GroupFilterTable filter;
    private final int id;

    /** The next free subgroup index — one per body, handed out in build order. */
    private int next;

    private final List<Integer> parts = new ArrayList<>();
    private final List<Integer> bones = new ArrayList<>();

    /**
     * @param id     an id no other actor in this scene uses
     * @param bodies an upper bound on how many bodies will claim a place — the table is sized once
     */
    public ActorCollisionGroup(int id, int bodies)
    {
        this.id = id;

        /* Jolt sizes the table's bit set from this and indexes it unchecked, so it is the count of
         * bodies before anything is skipped, never the count that turned out to be built. Zero is
         * not a size Jolt accepts, and an actor with no bodies still constructs one of these. */
        this.filter = new GroupFilterTable(Math.max(1, bodies));
    }

    /** A place for a ragdoll part — a body that will be dynamic as soon as it is released. */
    public int claimPart()
    {
        int sub = this.next++;

        this.parts.add(sub);

        return sub;
    }

    /** A place for a kinematic bone — a body that only ever rides the animation. */
    public int claimBone()
    {
        int sub = this.next++;

        this.bones.add(sub);

        return sub;
    }

    /**
     * A place for a chain segment or one of its pins — a body that neither {@link #seal} case
     * applies to.
     *
     * <p>Deliberately not a part: sealing excuses every part from every kinematic bone, which is
     * right for a ragdoll (its shapes were cut from the same skeleton and overlap everywhere) and
     * exactly wrong for hair, whose whole job is to rest on the shoulders it is told to collide
     * with. The one pair a strand must not have — its first segment against the bone it hangs from
     * — is excused by hand where that joint is made.</p>
     */
    public int claimChain()
    {
        return this.next++;
    }

    /** What a body is told to carry, so that it consults this table rather than colliding blindly. */
    public CollisionGroup of(int sub)
    {
        return new CollisionGroup(this.filter, this.id, sub);
    }

    /** Excuses one pair by hand — the ragdoll's joints, which only it knows about. */
    public void excuse(int a, int b)
    {
        this.filter.disableCollision(a, b);
    }

    /**
     * The actor is fully built: excuse every ragdoll part from every one of the actor's kinematic
     * bones. Done here rather than as the bodies are created because the two halves are built in
     * turn — the ragdolls claim their bones first, the leftovers become the kinematic rig — so
     * neither half knows the whole of the other while it is running.
     */
    public void seal()
    {
        for (int part : this.parts)
        {
            for (int bone : this.bones)
            {
                this.filter.disableCollision(part, bone);
            }
        }
    }
}
