package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import wemppy.bbs_physics.actions.DeathActionClip;
import wemppy.bbs_physics.engine.PhysicsCache;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * What a scene hands to the one built after it for the same film, when BBS rebuilds the cast.
 *
 * <p>BBS rebuilds it often — on every undo and redo, every time the form editor opens or closes, a
 * replay switched on or off — and a scene is bound to the cast it was built for, so it goes with it.
 * Built again from nothing, the next scene collected the world block by block and recorded the film
 * from its opening frame, for an edit that may have changed one key near the end, or nothing at
 * all.</p>
 *
 * <p>So the scene going hands over what it knows: the ground it collected, what it recorded and the
 * checkpoints it took, with one more taken as it goes, of where the world stands; the copy of what
 * the recording was made from, and whatever edits it had not answered yet. The scene coming checks
 * that it was built into the same bodies, recording channel for channel, and goes on from where the
 * last one stood, answering those edits as if it had been there all along. Built into anything
 * else, it records from the opening frame as before — still on the same ground, with the same
 * kept poses.</p>
 *
 * <p>Edits made while no scene is up — between the cast being rebuilt and the next scene being
 * built — land here, and are answered by the scene coming (see {@link #edited}).</p>
 */
final class SceneHandover
{
    /** The film both scenes are of: the very object, since the copies below follow its values. */
    final Film film;

    final double originX;
    final double originY;
    final double originZ;

    /** The ground the scene stood on — see {@link WorldCollider#adopt}. */
    final WorldCollider.Window ground;

    /** What the scene recorded, and which key every channel of it was claimed under. */
    final PhysicsCache cache;
    final Map<String, Integer> channels;

    /** How many bodies the world held, which the next world has to hold too. */
    final int bodies;

    final SceneCheckpoints checkpoints;

    /** Where the world stood as the scene went, which is where the last checkpoint was taken. */
    final int tick;

    final SceneChanges changes;
    final Set<String> reach;
    final Map<DeathActionClip, NavigableMap<Integer, RagdollRig.Impact>> impacts;
    final boolean full;

    /** The film's scene-wide knobs the recording was made under. */
    final float gravity;
    final float speed;
    final int collisionSteps;

    /** Edits that take back the whole recording, or the part from a tick on — not yet answered. */
    boolean stale;
    int staleFrom;

    /**
     * Whether the recording was locked, and what the lock carries — see {@link FilmScene#isHeld}. A
     * locked recording stands whatever is edited, so an edit that would start it over only waits
     * ({@code pending}) for the lock to come off.
     */
    boolean held;
    boolean pending;
    boolean outdated;
    FilmScene.Made made;
    RecordingStore.Stored unchecked;

    /** Whether a scene took this handover on, which makes the ground its own to keep or let go of. */
    boolean taken;

    SceneHandover(Film film, double originX, double originY, double originZ, WorldCollider.Window ground,
        PhysicsCache cache, Map<String, Integer> channels, int bodies, SceneCheckpoints checkpoints, int tick,
        SceneChanges changes, Set<String> reach, Map<DeathActionClip, NavigableMap<Integer, RagdollRig.Impact>> impacts,
        boolean full, boolean stale, int staleFrom, float gravity, float speed, int collisionSteps)
    {
        this.film = film;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.ground = ground;
        this.cache = cache;
        this.channels = channels;
        this.bodies = bodies;
        this.checkpoints = checkpoints;
        this.tick = tick;
        this.changes = changes;
        this.reach = reach;
        this.impacts = impacts;
        this.full = full;
        this.stale = stale;
        this.staleFrom = staleFrom;
        this.gravity = gravity;
        this.speed = speed;
        this.collisionSteps = collisionSteps;
    }

    /**
     * Lets go of the ground when no scene takes this handover on — the film closing, another film's
     * scene built instead. See {@link WorldCollider.Window#release}.
     */
    void release()
    {
        if (!this.taken && this.ground != null)
        {
            this.taken = true;
            this.ground.release();
        }
    }

    /**
     * An edit made while no scene was up: placed against the copy the scene that went kept, and
     * left for the scene coming to answer.
     */
    SceneChanges.Change edited(Film film, BaseValue value)
    {
        SceneChanges.Change change = this.changes.affected(film, value, this.reach);
        int tick = change.tick();

        if (tick <= 0)
        {
            if (this.held)
            {
                this.pending = true;
            }
            else
            {
                this.stale = true;
            }
        }
        else if (tick != SceneChanges.NONE)
        {
            this.staleFrom = Math.min(this.staleFrom, tick);
        }

        if (this.held && tick != SceneChanges.NONE)
        {
            this.outdated = true;
        }

        return change;
    }
}
