package wemppy.bbs_physics.client.scene;

import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.Pair;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsTimeline;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.forms.FormTreeWalk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The physics of one film: a single Jolt world, a recording of what it did on every tick, and the
 * bodies in it — the world's blocks, everything the cast's forms mark up as collidable, and every
 * modifier anywhere in their trees.
 *
 * <p><b>The world is a recorder, not a source of pictures</b> (§2.5, §6). It only ever moves
 * forwards, one tick at a time, writing each tick's answers into {@link PhysicsCache}; a drawn frame
 * reads that recording and never asks the world anything. Scrubbing therefore costs an array lookup,
 * an edit costs a counter reset, and a frame the recording has not reached yet is drawn as plain
 * animation (Р8.1) until the background catch-up gets there. What this replaced — snapshots,
 * rewinds, a re-simulation budget, a scene that could be "behind" the cursor — is gone entirely,
 * along with the class of bugs where the viewport and the exported video disagreed.</p>
 *
 * <p><b>Everything is simulated around an origin</b> rather than in raw world coordinates. Jolt is
 * built here in single precision, where a float's resolution at a hundred thousand blocks out is
 * coarse enough to see, and Minecraft worlds go much further than that. So the scene picks a block
 * to be its zero — the first actor's position when the scene is built — and every body lives near
 * it. World coordinates only come back at drawing time.</p>
 *
 * <p><b>Everything is read at whole ticks.</b> BBS writes a form's keyframed values when it draws,
 * at the tick plus however far into it the frame happens to fall, so a simulation that simply read
 * them would be sampling the animation at a moment decided by the frame rate — and a film would come
 * out differently on a faster machine, which is the one thing a film's physics may not do. So the
 * scene re-applies the replay's properties at the integer tick before evaluating, and asks the
 * entity for the tick it is on rather than the tick it is coming from.</p>
 *
 * <p>What is <em>not</em> here: assembling the bodies ({@link SceneBuilder}), standing the actors on
 * a tick ({@link SceneCast}), and firing the action clips ({@link SceneClips}). This class is the
 * clock and the recording.</p>
 */
public class FilmScene implements AutoCloseable
{
    /**
     * How long a frame may spend simulating while the cursor is ahead of the recording, and how long
     * it may spend running ahead of the cursor once it has caught up.
     *
     * <p>A budget in <em>time</em>, not in steps, which is the correction the old design needed: the
     * cost of a step is a pose evaluation per actor and varies by an order of magnitude between a
     * crate and a cast of models, so a fixed step count meant a stall of unpredictable length.
     * Catching up is worth a visible fraction of a frame because the author is waiting for it;
     * running ahead is not, because nobody is.</p>
     */
    private static final long CATCHUP_BUDGET = 12_000_000L;
    private static final long LOOKAHEAD_BUDGET = 4_000_000L;

    /**
     * How long after the last edit the background catch-up stays out of the way. An author dragging
     * a slider invalidates the recording on every batch of changes, and re-simulating between the
     * batches would be work thrown away — worse, work thrown away inside the frames they are
     * dragging in.
     */
    private static final long IDLE_AFTER_EDIT = 200_000_000L;

    /** How far past the film's own length the recording is allowed to run. */
    private static final int LOOKAHEAD_PAST_END = 40;

    private final PhysicsWorld world;
    private final PhysicsTimeline timeline;

    /**
     * The film as a recording: what every body was doing on every tick that has been simulated.
     * This, and not the Jolt world, is what a drawn frame reads (§6).
     */
    private final PhysicsCache cache = new PhysicsCache();

    /** The film's cast, borrowed by the simulation and handed back every time. */
    private final SceneCast cast;

    /** The film's physics action clips — the pushes and tears (Э5). */
    private final SceneClips clips = new SceneClips(this);

    /** The film's cast, as the anchor resolution needs it — an anchor points at another actor. */
    private final IntObjectMap<IEntity> entities;

    /** The film being simulated, for its length — the recording has no reason to run past the end. */
    private final Film film;

    /** The world-space block this scene's physics is centred on — see the class note. */
    private double originX;
    private double originY;
    private double originZ;

    /** Every body in the world as the debug overlay sees it, the ground and the bones included. */
    private final List<SceneBody> bodies = new ArrayList<>();

    private final List<SceneActor> actors = new ArrayList<>();

    /**
     * Whether the film has been edited since this simulation ran, so everything it worked out is a
     * consequence of numbers that are no longer there. Raised from outside (see
     * {@link FilmScenes#onFilmEdited}) and answered on the next tick by starting over.
     */
    private boolean stale;

    /** When the last edit arrived — the background catch-up keeps clear for a moment after one. */
    private long editedAt;

    /** Whether the recording has hit its ceiling, so the fact is reported once rather than per tick. */
    private boolean full;

    /** The first tick on which something left the world, for the notch on the bar; -1 for none. */
    private int lostAt = -1;

    /** The scene-wide knobs this recording was made under — see {@link #applyWorldSettings()}. */
    private float gravity = PhysicsWorld.EARTH_GRAVITY;
    private int collisionSteps = PhysicsWorld.COLLISION_STEPS;

    /** The tick the film last asked for, against which the simulation's own tick is reported. */
    private int filmTick;

    /** The tick the bodies were last handed, so a jump can be told from a step forward. */
    private int drawnTick = -1;

    /**
     * Whether the next frame handed out must be a cut rather than a step, whatever the ticks say.
     * Raised by a rewind: the bodies have been moved back to the opening frame, so the pose each of
     * them was last drawn in is not a place it travelled from.
     */
    private boolean teleport;

    /** The region the world's blocks were collected from. */
    private WorldCollider.Window window;

    /**
     * Model forms whose model had not finished loading when this scene was assembled, so nothing
     * could be built for them — see {@link #needsRebuild()}.
     */
    private final List<ModelForm> awaited = new ArrayList<>(0);

    /** Scratch for the status readout, which runs per drawn frame. */
    private final Vector3f probe = new Vector3f();

    public FilmScene(BaseFilmController controller)
    {
        this.world = new PhysicsWorld();
        this.timeline = new PhysicsTimeline(this.world);
        this.entities = controller.getEntities();
        this.film = controller.film;
        this.cast = new SceneCast(controller);

        boolean built = false;

        try
        {
            this.assemble(controller.getTick());

            built = true;
        }
        finally
        {
            if (!built)
            {
                /* Half a scene is worse than none: a Jolt world is native memory that no garbage
                 * collector ever comes back for, and the forms already claimed hold a state
                 * belonging to a simulation that will never step again. The caller only sees the
                 * exception and drops the object on the floor, so cleaning up is this constructor's
                 * job. Closing twice is harmless, which is what makes this safe. */
                this.close();
            }
        }
    }

    /** Everything the constructor does that can fail — see the cleanup it is wrapped in. */
    private void assemble(int cursor)
    {
        this.cast.borrow();

        try
        {
            /* Built as the film's opening frame, not as the frame the cursor happens to be on. A
             * scene is the film's physics, so it must not depend on where the author was standing
             * when it was assembled — including the origin it is centred on, which is a rounding of
             * an actor's position and would otherwise land on a different block for the same film
             * opened at a different moment. */
            this.cast.apply(0);
            this.pickOrigin();

            SceneBuilder builder = new SceneBuilder(this, this.world);

            this.window = builder.buildGround(this.cast);
            this.actors.addAll(builder.buildActors(this.cast));

            this.world.optimize();

            /* The recording's shape is fixed here: every channel exists, so a tick is a fixed number
             * of floats and can be indexed arithmetically. A body appearing later would shift every
             * channel after it, which is why a change to the set of bodies rebuilds the scene. */
            this.cache.seal();

            /* The world as assembled is tick 0 of the film — the opening frame, whatever the cursor
             * happened to be on — and that is the recording's first entry. A physics clip sitting on
             * frame 0 fires here: the stepping loop only ever poses tick 1 onwards, so without this
             * the film's very first frame would be the one frame a push cannot land on.
             *
             * Inside the borrow, deliberately: recording a tick reads the handle off the form, and
             * the form only holds tick 0's handle while the cast is standing on tick 0. Written
             * after the cast was handed back — as it was — the film's opening frame carried whatever
             * the handle happened to say at the cursor. */
            this.timeline.start();
            this.clips.apply(this.cast, 0);
            this.record(0);
        }
        finally
        {
            this.cast.restore(cursor);
        }
    }

    /**
     * Centres the scene on its first actor. Films are authored around their cast, so this keeps the
     * numbers small where the action is; a film with no actors yet falls back to the world origin,
     * which is as good a guess as any.
     */
    private void pickOrigin()
    {
        IEntity first = this.cast.first();

        if (first != null)
        {
            this.originX = Math.floor(first.getX());
            this.originY = Math.floor(first.getY());
            this.originZ = Math.floor(first.getZ());
        }
    }

    /* What the builder fills in while a scene is being assembled */

    /** Registers a body to be drawn by the debug overlay, with a channel of its own to be read from. */
    public void addDebugBody(SceneBody body)
    {
        body.setChannel(this.cache.addChannel());

        this.bodies.add(body);
    }

    /** The fallback ground, which is drawn but has no channel of its own — it never moves. */
    void addFloor(SceneBody floor)
    {
        this.bodies.add(floor);
    }

    /** Notes a model that had not finished loading, so the scene can be built again when it has. */
    void await(ModelForm form)
    {
        this.awaited.add(form);
    }

    /** Claims a transform slot in the recording. Only valid while the scene is being assembled. */
    public int addChannel()
    {
        return this.cache.addChannel();
    }

    /** Claims a wide slot — a cloth's vertices. Only valid while the scene is being assembled. */
    public int addChannel(int floats)
    {
        return this.cache.addChannel(floats);
    }

    /* Reading the scene */

    public PhysicsWorld getWorld()
    {
        return this.world;
    }

    public PhysicsCache getCache()
    {
        return this.cache;
    }

    public List<SceneBody> getBodies()
    {
        return this.bodies;
    }

    List<SceneActor> getActors()
    {
        return this.actors;
    }

    /** The film this scene simulates — the debug overlay reads its impulse clips to mark them. */
    public Film getFilm()
    {
        return this.film;
    }

    /** The tick the film last asked for — what the overlay judges "this clip is now" against. */
    public int getFilmTick()
    {
        return this.filmTick;
    }

    /** How many blocks the world collision around this scene was built from. */
    public int getWorldBoxes()
    {
        return this.window == null ? 0 : this.window.boxes();
    }

    public double getOriginX()
    {
        return this.originX;
    }

    public double getOriginY()
    {
        return this.originY;
    }

    public double getOriginZ()
    {
        return this.originZ;
    }

    /**
     * What the simulation is doing, for the readout over the viewport. Counted per drawn frame
     * rather than kept up to date as things change: there are a handful of bodies, and a number that
     * is worked out where it is read cannot go stale.
     */
    public SceneStatus getStatus()
    {
        int ghosts = 0;
        int outside = 0;
        int lost = 0;

        /* Over the debug bodies rather than the rigs, because this is the one count that has to
         * cover everything the world holds: a ragdoll's parts have no rig of their own. */
        for (SceneBody body : this.bodies)
        {
            if (body.isLost())
            {
                lost += 1;
            }
        }

        for (SceneActor actor : this.actors)
        {
            for (SceneRig rig : actor.getRigs())
            {
                if (rig.isGhost())
                {
                    ghosts += 1;
                }

                if (rig.isLost())
                {
                    lost += 1;
                }
                else if (rig.getScenePosition(this.probe) && this.isOutside(this.probe))
                {
                    outside += 1;
                }
            }
        }

        return new SceneStatus(
            this.filmTick,
            this.cache.getComputed() - 1,
            this.recordingEnd(this.filmTick),
            this.cache.has(this.filmTick),
            this.stale || !this.backgroundAllowed(),
            this.full,
            this.lostAt,
            this.world.getBodyCount(),
            ghosts,
            outside,
            lost);
    }

    /** Whether a point in scene coordinates lies outside the world that was actually collected. */
    /** Whether any body or rig is lost to the solver right now — see {@link SceneRig#isLost()}. */
    private boolean anythingLost()
    {
        for (SceneBody body : this.bodies)
        {
            if (body.isLost())
            {
                return true;
            }
        }

        for (SceneActor actor : this.actors)
        {
            for (SceneRig rig : actor.getRigs())
            {
                if (rig.isLost())
                {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isOutside(Vector3f point)
    {
        return this.window != null && this.window.boxes() > 0 && !this.window.contains(point.x, point.y, point.z);
    }

    /* The clock */

    /**
     * The film moved to {@code tick}: record whatever of the film still needs recording, then hand
     * every body the frame it is to be drawn in.
     *
     * <p>Two halves that no longer have anything to do with each other, and that separation is the
     * whole of Э3. Recording only ever moves forwards, one tick at a time, in whatever time this
     * frame can spare. Drawing reads an array. A scrub to tick 900 does not make the world go
     * anywhere — it reads entry 900 if there is one, and shows plain animation if there is not
     * (Р8.1). Nothing can be "behind" any more, because nothing is chasing anything.</p>
     */
    public void tick(int tick)
    {
        if (tick < 0)
        {
            tick = 0;
        }

        this.filmTick = tick;

        /* Before the rewind, not after it: these knobs invalidate the recording themselves when they
         * move, and answering them second meant a whole tick was recorded under the new gravity on
         * top of a recording made under the old one, only to be thrown away by the invalidation on
         * the tick after. */
        this.applyWorldSettings();

        if (this.stale)
        {
            this.stale = false;

            this.rewind();
        }

        this.compute(tick);
        this.distribute(tick);
    }

    /**
     * Picks up the scene-wide knobs — gravity and collision steps — and throws the recording away
     * when either has moved.
     *
     * <p>They are part of the simulation's arithmetic, not a display option: half gravity is a
     * different fall from the first tick onwards, so nothing worked out under the old value is worth
     * keeping. Cheap to check and rare to change, which is why it lives on the tick rather than
     * needing the settings screen to tell anyone.</p>
     */
    private void applyWorldSettings()
    {
        float gravity = BBSPhysicsSettings.gravity == null ? PhysicsWorld.EARTH_GRAVITY : BBSPhysicsSettings.gravity.get();
        int steps = BBSPhysicsSettings.collisionSteps == null ? PhysicsWorld.COLLISION_STEPS : BBSPhysicsSettings.collisionSteps.get();

        if (gravity == this.gravity && steps == this.collisionSteps)
        {
            return;
        }

        this.gravity = gravity;
        this.collisionSteps = steps;

        this.world.setGravity(gravity);
        this.world.setCollisionSteps(steps);

        this.invalidate();
    }

    /**
     * Records as much of the film as this frame can afford, nearest need first.
     *
     * <p>Priority is the cursor: while the recording has not reached the frame the author is looking
     * at, the whole catch-up budget goes there, because that is the one frame somebody is waiting
     * for. Once it has, the same loop keeps running ahead on a much smaller budget so that playing
     * forwards never catches up with the recording — that is our one departure from Blender, which
     * simply waits to be told to compute (§6). Both stop at the film's length: past the end there is
     * nothing to look at.</p>
     */
    private void compute(int cursor)
    {
        int end = this.recordingEnd(cursor);

        if (this.cache.getComputed() > end)
        {
            return;
        }

        boolean catchup = !this.cache.has(cursor);

        if (!catchup && !this.backgroundAllowed())
        {
            return;
        }

        long deadline = System.nanoTime() + (catchup ? CATCHUP_BUDGET : LOOKAHEAD_BUDGET);

        /* The cast is the film's own entities, and standing them on the ticks being recorded moves
         * the very objects BBS is about to draw. Borrowed for the whole run and handed back in a
         * finally, on the cursor's tick — an exception halfway through would otherwise leave the
         * actors standing wherever the last recorded step put them. */
        this.cast.borrow();

        try
        {
            while (this.cache.getComputed() <= end)
            {
                if (!this.cache.canWrite(this.timeline.getTick() + 1))
                {
                    /* The recording hit its memory ceiling. Everything past this point draws as
                     * plain animation — said once, because this condition holds for every tick from
                     * here on and a line per tick would bury the log it belongs in. */
                    if (!this.full)
                    {
                        this.full = true;

                        BBSPhysics.LOGGER.warn("The physics recording is full at tick {}; later frames of this film show animation only.", this.cache.getComputed());
                    }

                    break;
                }

                this.step();

                if (System.nanoTime() >= deadline)
                {
                    break;
                }
            }
        }
        finally
        {
            this.cast.restore(cursor);
        }
    }

    /** Simulates the next tick of the film and writes it into the recording. */
    private void step()
    {
        this.record(this.timeline.step(this::poseTick));
    }

    /**
     * Stands the film on {@code tick}, for the step that is about to simulate it.
     *
     * <p>Called once per simulated step, which during a scrub is many times per frame — so the order
     * matters: the whole cast is placed first, then each actor's pose is walked. Cheap it is not,
     * and it is the price of the viewport agreeing with the exported video.</p>
     */
    private void poseTick(int tick)
    {
        this.cast.apply(tick);

        for (SceneActor actor : this.actors)
        {
            actor.drive(this, false);
        }

        /* After the drives, deliberately: a drive writes a body's velocity outright, so a push
         * applied before it would be erased in the same tick it was given. */
        this.clips.apply(this.cast, tick);
    }

    /**
     * Writes every channel's answer for a tick that has just been simulated, then declares the tick
     * whole. Nothing may read a half-written tick — a frame drawn with half the bodies on it and
     * half on the tick before would be a glitch nobody could explain.
     */
    private void record(int tick)
    {
        for (SceneBody body : this.bodies)
        {
            body.record(this.world.getBodies(), this.cache, tick);
        }

        for (SceneActor actor : this.actors)
        {
            actor.record(this.world, this, this.cache, tick);
        }

        this.cache.commit(tick);

        if (this.lostAt < 0 && this.anythingLost())
        {
            this.lostAt = tick;
        }
    }

    /** Hands every body the recorded frame for {@code tick}, or the news that there is not one. */
    private void distribute(int tick)
    {
        /* A jump is anything but the one step forward that playback makes: across one there is no
         * meaningful previous tick, and interpolating out of it would draw bodies sliding the whole
         * way. Asking for the same tick again — a paused editor — is not a jump and needs nothing
         * special: both slots end up holding the same numbers, so the interpolation collapses. */
        boolean jumped = this.teleport || (tick != this.drawnTick && tick != this.drawnTick + 1);

        this.teleport = false;

        for (SceneBody body : this.bodies)
        {
            body.readCache(this.cache, tick, jumped);
        }

        for (SceneActor actor : this.actors)
        {
            actor.readCache(this.cache, tick, jumped);
        }

        this.drawnTick = tick;
    }

    /**
     * The last tick worth recording: the film's own length, plus a little, and never less than the
     * cursor — an author scrubbing past the end still expects the frame they are on.
     */
    private int recordingEnd(int cursor)
    {
        int duration = this.film == null ? 0 : this.film.camera.calculateDuration();

        return Math.max(cursor, duration + LOOKAHEAD_PAST_END);
    }

    /**
     * Whether the recording may run ahead of the cursor right now. It may not for a moment after an
     * edit: the author is probably still dragging, and every batch of changes throws the recording
     * away again.
     */
    private boolean backgroundAllowed()
    {
        return System.nanoTime() - this.editedAt >= IDLE_AFTER_EDIT;
    }

    /**
     * The film was edited, so this recording describes a film that no longer exists.
     *
     * <p>This used to be the expensive call in the addon: it restarted the world and re-simulated up
     * to the cursor on the spot, on every batch of edits, which is what made dragging a slider feel
     * like the scene was fighting back. Now it costs a flag and a timestamp. The recording is thrown
     * away on the next tick, the bar under the timeline turns grey, and the frames come back as the
     * background catch-up refills them.</p>
     */
    public void invalidate()
    {
        this.stale = true;
        this.editedAt = System.nanoTime();
    }

    /**
     * Bakes the physics of one form of one actor into its replay's keyframes — see
     * {@link PhysicsBake} for what is written and why.
     *
     * <p>The whole film is baked, tick 0 to the end of the camera, and the recording is completed
     * first, with no time budget: this is the one call where the author is waiting for the answer
     * rather than looking at a frame, and a film is a few thousand ticks at most. The actors are
     * borrowed for the length of it and handed back on the cursor's tick, as every walk through
     * the film does, and the drawn frame is re-read from the recording afterwards, because working
     * the bake out has stood every rig's state on every tick in turn.</p>
     *
     * @param replay   the replay whose keyframes receive the bake
     * @param formPath where the form sits in the replay's form tree, by the walk's convention
     * @return what was written, or null when this scene has no actor playing that replay
     */
    public PhysicsBake.Result bake(Replay replay, String formPath)
    {
        SceneCast.Member member = this.cast.find(replay);
        SceneActor actor = member == null ? null : this.actorOf(member.entity);

        if (actor == null)
        {
            return null;
        }

        int end = this.film == null ? 0 : this.film.camera.calculateDuration();
        int last = Math.min(end, this.ensureRecorded(end));
        PhysicsBake bake = new PhysicsBake(this.film, replay, formPath);

        this.cast.borrow();

        try
        {
            for (int tick = 0; tick <= last; tick++)
            {
                this.cast.apply(tick);

                Form root = member.entity.getForm();

                if (root == null)
                {
                    continue;
                }

                try
                {
                    /* Posed the way the drive poses it: the bones are stood on the animation of
                     * this tick, which is what the substitution blends the recording against. */
                    evaluatePose(member.entity, root);
                }
                catch (Throwable e)
                {
                    /* The same failure the drive tolerates — a model not there yet. That tick is
                     * skipped rather than baked from a stale pose. */
                    continue;
                }

                bake.at(tick);

                for (SceneRig rig : actor.getRigs())
                {
                    rig.bake(this.cache, tick, bake);
                }

                bake.finishTick();
            }
        }
        finally
        {
            this.cast.restore(this.filmTick);

            /* Every rig's state now describes the last tick baked; the drawn frame is put back as
             * a jump, since nothing travelled from there to the cursor. */
            this.teleport = true;
            this.distribute(this.filmTick);
        }

        return bake.write();
    }

    /**
     * Records the film through {@code end} with no time budget, and answers the last tick actually
     * recorded — short of {@code end} only when the recording ran out of room.
     */
    private int ensureRecorded(int end)
    {
        this.applyWorldSettings();

        if (this.stale)
        {
            this.stale = false;

            this.rewind();
        }

        this.cast.borrow();

        try
        {
            while (this.cache.getComputed() <= end && this.cache.canWrite(this.timeline.getTick() + 1))
            {
                this.step();
            }
        }
        finally
        {
            this.cast.restore(this.filmTick);
        }

        return this.cache.getComputed() - 1;
    }

    /** The actor built around {@code entity}, or null when nothing of it is simulated. */
    private SceneActor actorOf(IEntity entity)
    {
        for (SceneActor actor : this.actors)
        {
            if (actor.getEntity() == entity)
            {
                return actor;
            }
        }

        return null;
    }

    /**
     * Puts the world back to the film's opening frame and empties the recording.
     *
     * <p>Physics is a consequence of everything that happened before, so an edit in the middle
     * cannot be patched into a result: moving a crate's starting corner does not nudge where it
     * landed, it changes the whole fall. Every body therefore goes back to the pose its keyframes
     * now describe on tick 0, and the recording starts again from there.</p>
     */
    private void rewind()
    {
        this.cast.borrow();

        try
        {
            this.cast.apply(0);

            for (SceneActor actor : this.actors)
            {
                actor.drive(this, true);
            }

            this.timeline.start();
            this.cache.clear();
            this.full = false;
            this.lostAt = -1;

            /* Same as at assembly: frame 0 is never posed by the stepping loop, so a clip sitting on
             * it fires here or not at all. And inside the borrow for the same reason as at assembly
             * — recording a tick reads the handle off the form, which only says tick 0's value while
             * the cast is standing on tick 0. */
            this.clips.apply(this.cast, 0);
            this.record(0);
        }
        finally
        {
            this.cast.restore(this.filmTick);
        }

        /* Everything in the world has just been put back to the opening frame. Whatever each body
         * was last drawn at is a place it never travelled from, so the next frame is a cut rather
         * than a step — without this it slides from the old pose to the new one over one frame,
         * every time an edit lands with the recording already caught up to the cursor. */
        this.teleport = true;
    }

    /* Reading the film */

    /**
     * What a chain's bottom end is told to do on the tick being simulated, resolved from its anchor
     * track the way the old chain solver resolved its physics target: the bound side is taken at its
     * full position and the fade becomes a 0..1 weight, because feeding a fading anchor straight to
     * {@code getTotalMatrix} lerps the position from the world origin across a "no target" key and
     * yanks the end to (0,0,0).
     *
     * <p>An anchor whose target actor is itself a simulated root body becomes a real tie to that
     * body — the rope pulls it. Anything else the anchor can name resolves to a point, and the end
     * is pinned there kinematically.</p>
     */
    ChainRig.Attach resolveAttach(ChainForm form)
    {
        Anchor anchor = form.attach.get();

        if (anchor == null)
        {
            return ChainRig.Attach.NONE;
        }

        Anchor resolve;
        float weight;

        if (anchor.previous != null && anchor.isFadeIn())
        {
            resolve = anchor;
            weight = anchor.x;
        }
        else if (anchor.previous != null && anchor.isFadeOut())
        {
            resolve = anchor.previous;
            weight = 1F - anchor.x;
        }
        else
        {
            resolve = anchor;
            weight = 1F;
        }

        if (weight <= 0F || resolve.replay == Anchor.NO_ATTACHMENT)
        {
            return ChainRig.Attach.NONE;
        }

        IEntity target = this.entities.get(resolve.replay);

        if (target == null)
        {
            return ChainRig.Attach.NONE;
        }

        /* A simulated root body wins over its animated frame: tying the rope to where the crate would
         * have been had it not fallen is nobody's intention. The attachment name is ignored for a
         * body — a crate has no bones. */
        for (SceneActor actor : this.actors)
        {
            if (actor.getEntity() != target)
            {
                continue;
            }

            for (SceneRig rig : actor.getRigs())
            {
                if (rig instanceof BodyRig body && body.getPath().isEmpty())
                {
                    /* Whatever the body's handle says right now: a tie to a body the animation still
                     * owns simply hangs off it — Jolt holds a dynamic-kinematic pair fine — and
                     * starts dragging the moment the handle lets the body go. */
                    return new ChainRig.Attach(ChainRig.ATTACH_BODY, weight, 0F, 0F, 0F, body.getBodyId());
                }
            }
        }

        /* Everything else is a point: the actor itself, a bone of it, with the anchor's own offset —
         * the same resolution the film's anchors go through, at the tick the cast is standing on and
         * at transition 1 (0 is the previous tick — the Э1 lesson). */
        Pair<Matrix4f, Float> matrix = BaseFilmController.getTotalMatrix(
            this.entities, resolve, new Matrix4f(), 0D, 0D, 0D, 1F, 0, true, null);

        if (matrix.a == null)
        {
            return ChainRig.Attach.NONE;
        }

        Vector3f position = matrix.a.getTranslation(new Vector3f());

        return new ChainRig.Attach(
            ChainRig.ATTACH_PIN,
            weight,
            (float) (position.x - this.originX),
            (float) (position.y - this.originY),
            (float) (position.z - this.originZ),
            -1);
    }

    /**
     * Where the actor stands in the world — the frame BBS's own render composes its form matrices on
     * top of, resolved the same way so that physics and drawing agree to the last bit.
     *
     * <p>Two things about it are easy to get wrong and both were. The transition is <b>1</b>, not 0:
     * the entity carries the tick it came from alongside the tick it is on, and the tick it is on is
     * the one just applied — asking for 0 hands back the previous tick, and every bone in the scene
     * trails the character by a frame. And the anchor is resolved rather than ignored, so an actor
     * riding another actor's bone is simulated where it is drawn instead of where it would stand if
     * it were riding nothing.</p>
     */
    Matrix4f actorWorld(IEntity entity)
    {
        /* Zero camera: the actor's placement in the world, not on the screen. */
        Matrix4f matrix = BaseFilmController.getMatrixForRenderWithRotation(entity, 0D, 0D, 0D, 1F);
        Form root = entity.getForm();

        if (root == null)
        {
            return matrix;
        }

        Pair<Matrix4f, Float> anchored = BaseFilmController.getTotalMatrix(
            this.entities, root.anchor.get(), matrix, 0D, 0D, 0D, 1F, 0, false, null);

        return anchored.a == null ? matrix : anchored.a;
    }

    /**
     * One actor's pose as it stands: the same {@code collectMatrices} walk the anchors and gizmos
     * use, over an actor {@link SceneCast#apply} has already stood on the tick being simulated.
     *
     * <p>That pinning is not optional. BBS writes a form's keyframed values when it <em>draws</em>,
     * at the tick plus however far into the tick the frame happens to fall, so a simulation that
     * simply read them would be sampling the animation at a moment decided by the frame rate — the
     * authority handle, the form's transform, the pose, all of it — and the same film would come out
     * differently on a faster machine. The next frame writes them again for drawing, so nothing is
     * taken away from the render path.</p>
     *
     * <p>Flagged as the simulation's own walk while it runs: the renderer substitutes a ragdoll's
     * simulated pose into this very walk for everyone else — anchors, gizmos — but the simulation
     * must read pure animation here, because this pose is the target the muscles pull towards.</p>
     */
    static MatrixCache evaluatePose(IEntity entity, Form root)
    {
        ensureAnimators(root);

        RagdollPoseApplier.setEvaluating(true);

        try
        {
            return FormUtilsClient.getRenderer(root).collectMatrices(entity, 1F);
        }
        finally
        {
            RagdollPoseApplier.setEvaluating(false);
        }
    }

    /**
     * Warms up the animator of every model form in the tree before the matrix walk runs. The walk
     * assumes the render path has already done this — on a freshly built cast it has not, and a
     * model form with body parts trips over the gap.
     */
    private static void ensureAnimators(Form form)
    {
        FormTreeWalk.walk(form, (child, path, anchor) ->
        {
            if (FormUtilsClient.getRenderer(child) instanceof ModelFormRenderer model)
            {
                model.ensureAnimator(0F);
            }

            return true;
        });
    }

    /* The scene's own life */

    /**
     * Whether the region of the world this scene collected no longer matches what the settings ask
     * for, which is the one change a rewind cannot answer: the blocks are a body, and a different set
     * of blocks is a different set of bodies — a different shape of recording, since a channel is
     * fixed when the scene is sealed. So the scene is rebuilt from scratch instead, and the caller is
     * the one who can do that.
     */
    public boolean needsRebuild()
    {
        return this.window == null
            || this.window.radius() != BBSPhysicsSettings.worldRadius.get()
            || this.window.below() != BBSPhysicsSettings.worldBelow.get()
            || this.window.above() != BBSPhysicsSettings.worldAbove.get()
            || this.modelArrived();
    }

    /**
     * Whether a model this scene was assembled without has since finished loading. Same answer as
     * the window: the set of bodies is different now, and a different set of bodies is a different
     * shape of recording, so it is a rebuild and not a re-simulation.
     *
     * <p>Converges by construction. A model that never loads keeps handing back null and is never
     * waited on again; one that loads triggers exactly one rebuild, and the scene that comes out of
     * it lists only whatever is still missing.</p>
     */
    private boolean modelArrived()
    {
        for (ModelForm form : this.awaited)
        {
            if (ModelFormRenderer.getModel(form) != null)
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void close()
    {
        /* Hand the forms back to their keyframes. A form draws itself from the simulation for
         * exactly as long as a scene claims it, and one left holding the last transform of a world
         * that no longer exists would sit frozen wherever the simulation happened to stop — with
         * nothing left to move it again. */
        for (SceneActor actor : this.actors)
        {
            actor.release();
        }

        this.bodies.clear();
        this.actors.clear();
        this.cast.clear();
        this.world.close();
    }
}
