package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The film's actors, as the simulation needs them: able to be stood on any tick, and put back
 * afterwards exactly as they were found.
 *
 * <p><b>Physics has to read the film at whole ticks of its own choosing</b>, and the only way to
 * read an actor at a tick is to write that tick's keyframes into the entity — the same entity BBS is
 * about to draw. So the cast is <em>borrowed</em> and handed back, always in a {@code finally}: an
 * exception halfway through a catch-up would otherwise leave everyone standing wherever the last
 * simulated step put them, which is a film visibly broken by a failure that was recovered from.</p>
 *
 * <p><b>Everyone is placed before any pose is evaluated</b>, never actor by actor as each is walked.
 * An anchored actor is placed through the actor it rides, so a walk that ran while half the cast was
 * still on the previous tick would resolve that anchor against a stale position. The unsimulated
 * actors are here for exactly that reason — an actor with no physics of its own can still be the
 * one another actor is riding.</p>
 *
 * <p><b>The order is by replay index, ascending.</b> BBS keys its actors in a hash map, whose
 * iteration order is an implementation detail; here it decides which actor the scene is centred on
 * and in which order bodies enter the world, and Jolt resolves a pile in body order. Two runs of the
 * same film that disagreed about it would settle a stack of crates differently.</p>
 */
public final class SceneCast implements Iterable<SceneCast.Member>
{
    /** One actor of the film, whether or not it has anything simulated hanging off it. */
    public static final class Member
    {
        public final IEntity entity;

        /**
         * The replay this actor is played from, or null when the film has none for it. Held for one
         * reason: the form's keyframed values — the authority handle above all — are only written
         * into the form when BBS renders, at a fractional tick that depends on the frame rate.
         * Physics has to read them at the whole tick it is simulating, or the same film simulates
         * differently on a faster machine.
         */
        public final Replay replay;

        private final ActorState state = new ActorState();

        private Member(IEntity entity, Replay replay)
        {
            this.entity = entity;
            this.replay = replay;
        }
    }

    private final List<Member> members = new ArrayList<>();

    public SceneCast(BaseFilmController controller)
    {
        List<Replay> replays = controller.film == null ? null : controller.film.replays.getList();
        List<Integer> order = new ArrayList<>(controller.getEntities().keySet());

        Collections.sort(order);

        for (int index : order)
        {
            IEntity entity = controller.getEntities().get(index);

            if (entity != null)
            {
                this.members.add(new Member(entity, replays == null ? null : CollectionUtils.getSafe(replays, index)));
            }
        }
    }

    @Override
    public Iterator<Member> iterator()
    {
        return this.members.iterator();
    }

    /** The actor the scene is centred on, or null for a film with no cast yet. */
    public IEntity first()
    {
        return this.members.isEmpty() ? null : this.members.get(0).entity;
    }

    /** The actor played from {@code replay}, or null when the cast has none for it. */
    public Member find(Replay replay)
    {
        for (Member member : this.members)
        {
            if (member.replay == replay)
            {
                return member;
            }
        }

        return null;
    }

    /** Takes a copy of where every actor stands, because the simulation is about to move them. */
    public void borrow()
    {
        for (Member member : this.members)
        {
            member.state.capture(member.entity);
        }
    }

    /** Hands the cast back to the film, standing on {@code tick} exactly as it was found. */
    public void restore(int tick)
    {
        /* Both halves: the keyframes put back everything an actor carries — held items, equipment,
         * the lot — and the snapshot then puts the placement back verbatim, because BBS pairs the
         * current tick with the previous one differently depending on whether the film is playing,
         * and only a copy knows which rule was in force. */
        this.apply(tick);

        for (Member member : this.members)
        {
            member.state.restore(member.entity);
        }
    }

    /**
     * Stands the whole cast on {@code tick}: where each actor is, and what its form's animated
     * properties say at that moment.
     */
    public void apply(int tick)
    {
        for (Member member : this.members)
        {
            if (member.replay == null)
            {
                continue;
            }

            int local = member.replay.getTick(tick);
            Form root = member.entity.getForm();

            member.replay.keyframes.apply(local, member.entity);

            if (root != null)
            {
                member.replay.properties.applyProperties(root, local);
            }
        }
    }

    public void clear()
    {
        this.members.clear();
    }
}
