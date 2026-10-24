package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.Pair;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;
import wemppy.bbs_physics.engine.FilmPhysics;
import wemppy.bbs_physics.engine.PhysicsCache;
import wemppy.bbs_physics.engine.PhysicsThreads;
import wemppy.bbs_physics.engine.PhysicsTimeline;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.engine.RigMemory;
import wemppy.bbs_physics.forms.FormTreeWalk;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The physics of one film: a single Jolt world, a recording of what it did on every tick, and the
 * bodies in it — the world's blocks, everything the cast's forms mark up as collidable, and every
 * modifier anywhere in their trees.
 *
 * <p><b>The world is a recorder, not a source of pictures</b> (§2.5, §6). It only ever moves
 * forwards, one tick at a time, writing each tick's answers into {@link PhysicsCache}; a drawn frame
 * reads that recording and never asks the world anything. Scrubbing therefore costs an array lookup,
 * and a frame the recording has not reached yet is drawn as plain animation (Р8.1) until the
 * background catch-up gets there. What this replaced — rewinds on every scrub, a re-simulation
 * budget, a scene that could be "behind" the cursor — is gone entirely, along with the class of bugs
 * where the viewport and the exported video disagreed.</p>
 *
 * <p><b>An edit takes back only what it changed.</b> The recording keeps a checkpoint every second
 * ({@link SceneCheckpoints}), and an edit is compared against what the recording was made from
 * ({@link SceneChanges}) to find the first tick it can change. Everything before that tick stands;
 * the world and every rig go back to the last checkpoint before it and record on from there. A run
 * picked up from a checkpoint has to come out exactly as the run that made it — that is what keeps
 * the editor and the video agreeing — and the development build checks it as it goes
 * ({@link #verify}).</p>
 *
 * <p><b>The animation is walked once.</b> A pose walk that takes in nothing simulated is what the
 * keyframes alone say, and is kept per tick for the film ({@link ScenePoses}), outliving the scene:
 * a physics edit — even one that comes in through a form editor, after which BBS rebuilds the cast
 * and this scene with it — records the film again without walking its animation again.</p>
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
     * The longest a recording that still has ticks to record may go without a step, whatever a
     * frame's budget says — the pace the per-tick schedule always guaranteed, one step a tick.
     */
    private static final long MIN_PROGRESS = 50_000_000L;

    /**
     * How long after the last edit the background catch-up stays out of the way. An author dragging
     * a slider invalidates the recording on every batch of changes, and re-simulating between the
     * batches would be work thrown away — worse, work thrown away inside the frames they are
     * dragging in.
     */
    private static final long IDLE_AFTER_EDIT = 200_000_000L;

    /** How far past the film's own length the recording is allowed to run. */
    private static final int LOOKAHEAD_PAST_END = 40;

    /** What {@link #staleFrom} holds while nothing has been edited. */
    private static final int FRESH = Integer.MAX_VALUE;

    /**
     * How dear Jolt's part of a step must be, in milliseconds, before the last step of a frame's run
     * is launched rather than taken — see {@link #launch}. Landing one stands the cast on its tick a
     * second time, which is worth it only when what runs beside the frame is worth more.
     */
    private static final double LAUNCH_ABOVE = 1D;

    /**
     * Whether every checkpoint is checked by recording the second before it again from the one
     * before, and comparing — see {@link #verify}. Twice the work, so only in the development
     * environment or when asked for with {@code -Dbbs_physics.checkResume=true}.
     */
    private static final boolean CHECK_RESUME = FabricLoader.getInstance().isDevelopmentEnvironment()
        || Boolean.getBoolean("bbs_physics.checkResume");

    /** Made as the scene is assembled, once the cast is counted — see {@link #assemble}. */
    private PhysicsWorld world;
    private PhysicsTimeline timeline;

    /**
     * The film as a recording: what every body was doing on every tick that has been simulated.
     * This, and not the Jolt world, is what a drawn frame reads (§6). Swapped for a copy only while
     * {@link #verify} records part of the film a second time.
     */
    private PhysicsCache cache = new PhysicsCache();

    /** Where the world and every rig stood every second of the recording. */
    private SceneCheckpoints checkpoints = new SceneCheckpoints();

    /** What the recording was made from, for working out the first tick an edit changes. */
    private final SceneChanges changes;

    /** Whether {@link #verify} is recording a second time, which takes no checkpoints of its own. */
    private boolean verifying;
    private boolean verifyReported;
    private final Map<String, Integer> channelKeys = new java.util.HashMap<>();
    private String channelActor = "";
    private final Recording retained;

    /**
     * What the scene before this one for the same film handed over, while this one is being put up
     * — see {@link SceneHandover}. Let go of once taken over.
     */
    private SceneHandover handover;

    /**
     * Whether the ground this scene stands on went to the scene after it, which keeps it — see
     * {@link WorldCollider.Window#release}.
     */
    private boolean groundHandedOver;

    /**
     * How much of the film is recorded afresh and compared, bit for bit, with a recording kept on
     * disk before that recording is taken — see {@link #takeStored}.
     */
    private static final int STORED_CHECK = 20;

    /**
     * The recording kept on disk for this film, when it passed every check but the last: the first
     * second of the film recorded afresh, and compared — see {@link #takeStored}.
     */
    private RecordingStore.Stored stored;

    /** Whether the scene was built whole: only such a scene keeps its recording on disk. */
    private boolean built;

    /**
     * Which world the film belongs to, taken as the scene is built: by the time a scene closes on
     * leaving the world, the world may be gone, and the recording would be kept under no name.
     */
    private final String worldKey = RecordingStore.worldKey();

    /**
     * Where the world and every rig stood before the film's first step, as built — what starting
     * over goes back to (see {@link #rewind}), so that a recording made after any number of edits is
     * the recording a scene built afresh makes, to the bit.
     */
    private SceneCheckpoints.Checkpoint initial;

    record Recording(PhysicsCache cache, Map<String, Integer> keys, double x, double y, double z, boolean complete, boolean full, int lostAt) {}

    Recording recording()
    {
        return new Recording(this.cache, Map.copyOf(this.channelKeys), this.originX, this.originY, this.originZ, this.calculationComplete, this.full, this.lostAt);
    }

    void channelActor(String id) { this.channelActor = id; }

    public int addChannel(String key, int floats)
    {
        int channel = this.cache.addChannel(floats);
        this.channelKeys.put(this.channelActor + "/" + key, channel);
        return channel;
    }

    public int addChannel(String key) { return this.addChannel(key, PhysicsCache.FLOATS); }

    /** The film's cast, borrowed by the simulation and handed back every time. */
    private final SceneCast cast;

    /** The film's physics action clips — the pushes and tears (Э5). */
    private final SceneClips clips = new SceneClips(this);

    /** The film's cast, as the anchor resolution needs it — an anchor points at another actor. */
    private final Map<String, IEntity> entities;

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
     * Whether the film has been edited in a way that changes it from the opening frame, so
     * everything the simulation worked out is a consequence of numbers that are no longer there.
     * Raised from outside (see {@link FilmScenes#onFilmEdited}) and answered on the next tick by
     * starting over.
     */
    private boolean stale;

    /**
     * The first tick an edit since the last tick has changed, or {@link #FRESH}: the recording from
     * there on is wrong, the rest stands. Answered on the next tick by picking the recording up at
     * the last checkpoint before it ({@link #resume}).
     */
    private int staleFrom = FRESH;
    private final boolean editor;
    private boolean calculationRequested;
    private boolean calculationComplete;
    private int requestedEnd;
    private PhysicsCache impulsePreview;
    private int impulsePreviewTick = -1;

    /** When the last edit arrived — the background catch-up keeps clear for a moment after one. */
    private long editedAt;

    /** When the last step finished, and what a step has been costing — see {@link #recordUntil}. */
    private long steppedAt;
    private long stepCost;

    /**
     * Whether the last step of a frame's run was launched rather than taken: its physics running in
     * the background while the frame is drawn, the rest of it waiting for {@link #land}. What
     * launching one costs the frame — the pose and the drives — is kept beside it.
     */
    private boolean launched;
    private long launchCost;

    /** What the pass before the step published, for as long as the step lasts — see {@link #land}. */
    private PhysicsCache staging;

    /** Whether the log has been told that this scene's steps run beside the frames. */
    private boolean launchNoted;

    /** Counts the steps, so a pose walked for one step is never taken for the next one's. */
    private long step;

    /** The film tick the cast is standing on for the step being taken — see {@link #posedTick}. */
    private int posed;

    /**
     * The poses the film's walks came to when they took in nothing simulated, kept between runs and
     * between scenes of the film — see {@link ScenePoses}.
     */
    private final ScenePoses poses;

    /** Whether this scene has compared the kept poses with its replays in full — see {@link #reach}. */
    private boolean posesChecked;

    /** The older half of the pair {@link #distribute} last handed out, and whether it was a cut. */
    private int shownPrevious = -1;
    private boolean shownJumped = true;

    /**
     * The replays a step reads — the actors with anything simulated or marked up, and whatever they
     * hang off. See {@link SceneReach}; worked out again whenever the recording starts over.
     */
    private Set<String> reach = Set.of();

    /** Whether the recording has hit its ceiling, so the fact is reported once rather than per tick. */
    private boolean full;

    /** The first tick on which something left the world, for the notch on the bar; -1 for none. */
    private int lostAt = -1;

    /** The scene-wide knobs this recording was made under — see {@link #applyWorldSettings()}. */
    private float gravity = PhysicsWorld.EARTH_GRAVITY;
    private float speed = 1F;
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
        this(controller, null);
    }

    FilmScene(BaseFilmController controller, Recording retained)
    {
        this(controller, retained, null);
    }

    /**
     * @param handover what the scene before this one for the same film handed over when BBS rebuilt
     *                 its cast, or null — see {@link SceneHandover}
     */
    FilmScene(BaseFilmController controller, Recording retained, SceneHandover handover)
    {
        this.retained = retained;
        this.handover = handover;

        if (handover != null)
        {
            /* Its ground is this scene's from here: kept if stood on, let go of if not. */
            handover.taken = true;
        }

        this.changes = handover != null ? handover.changes : new SceneChanges();
        this.editor = controller instanceof mchorse.bbs_mod.ui.film.controller.FilmEditorController;
        this.entities = controller.getEntities();
        this.film = controller.film;
        this.poses = FilmScenes.posesFor(this.film);
        this.cast = new SceneCast(controller);

        boolean built = false;

        try
        {
            this.assemble(controller.getTick());
            if (retained != null)
            {
                int[] channels = new int[this.cache.getChannelCount()];
                java.util.Arrays.fill(channels, -1);
                this.channelKeys.forEach((key, channel) -> channels[channel] = retained.keys().getOrDefault(key, -1));
                this.cache.restore(retained.cache(), channels);
                this.calculationComplete = retained.complete() && this.cache.getComputed() == retained.cache().getComputed();
                this.full = retained.full();
                this.lostAt = retained.lostAt();
                this.stale = true;
            }
            else if (handover != null)
            {
                this.adopt(handover);
            }
            else if (!this.manualCalculation())
            {
                this.stored = this.storedCandidate();
            }

            built = true;
            this.built = true;
        }
        finally
        {
            if (handover != null && handover.ground != null && this.window != handover.ground)
            {
                /* Built on ground of its own: the ground handed over is stood on by nobody now. */
                handover.ground.release();
            }

            this.handover = null;

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
            this.posed = 0;
            this.cast.apply(0);
            this.pickOrigin();

            /* Jolt fixes how many bodies and contacts a world holds when it is made, and a
             * destructible structure is a body a block — so the world is made for this cast,
             * counted as it stands on the same opening frame it is built from. */
            this.world = new PhysicsWorld(SceneBuilder.debris(this.cast));
            this.timeline = new PhysicsTimeline(this.world);
            this.takeWorldSettings();

            SceneBuilder builder = new SceneBuilder(this, this.world);

            this.window = builder.buildGround(this.cast, this.handover == null ? null : this.handover.ground);
            this.actors.addAll(builder.buildActors(this.cast));
            this.reach();

            this.world.optimize();

            /* The recording's shape is fixed here: every channel exists, so a tick is a fixed number
             * of floats and can be indexed arithmetically. A body appearing later would shift every
             * channel after it, which is why a change to the set of bodies rebuilds the scene. */
            this.cache.seal();

            /* The opening frame stood exactly as starting over stands it (see rewind): the deaths
             * the film opens with, every body at its animated pose and stopped, with everything
             * built. */
            this.clips.prepareDeaths(this.cast, 0);

            for (SceneActor actor : this.actors)
            {
                actor.readCache(this.cache, -1, true);
            }

            for (SceneActor actor : this.actors)
            {
                actor.drive(this, true);
            }

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

            this.initial = this.standing(0);
        }
        finally
        {
            this.cast.restore(cursor);
        }
    }

    /** Where the world and every rig stand now, as a checkpoint of {@code tick}. */
    private SceneCheckpoints.Checkpoint standing(int tick)
    {
        RigMemory memory = new RigMemory();

        for (SceneActor actor : this.actors)
        {
            actor.remember(memory);
        }

        return new SceneCheckpoints.Checkpoint(tick, this.world.saveState(), memory.trim(), this.lostAt);
    }

    /**
     * Centres the scene on its first actor. Films are authored around their cast, so this keeps the
     * numbers small where the action is; a film with no actors yet falls back to the world origin,
     * which is as good a guess as any.
     */
    private void pickOrigin()
    {
        if (this.retained != null)
        {
            this.originX = this.retained.x();
            this.originY = this.retained.y();
            this.originZ = this.retained.z();
            return;
        }

        if (this.handover != null)
        {
            /* Where the scene before stood its world: the ground it hands over and every number it
             * recorded are around it. */
            this.originX = this.handover.originX;
            this.originY = this.handover.originY;
            this.originZ = this.handover.originZ;
            return;
        }
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

    public RagdollRig.Impact getDeathImpact(wemppy.bbs_physics.actions.DeathActionClip clip)
    {
        return this.clips.deathImpact(clip, this.filmTick);
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

    /** Which step is being taken, for the walks an actor keeps for the rest of it. */
    long stepIndex()
    {
        return this.step;
    }

    /** The film tick the cast is standing on for this step, which is where its walks are kept. */
    int posedTick()
    {
        return this.posed;
    }

    /**
     * Works out what a step reads — see {@link SceneReach} — and tells every actor how much of its
     * pose work a step may share: an actor that hangs off nothing walks its pose once and reads it
     * three times. Then brings the copy of what the recording is made from in line with it (see
     * {@link SceneChanges}).
     */
    private void reach()
    {
        this.reach = SceneReach.of(this.cast, this.actors);
        this.cast.reach(this.reach);

        for (SceneActor actor : this.actors)
        {
            SceneCast.Member member = this.cast.find(actor.getEntity());

            actor.plan(member == null || SceneReach.anchored(member),
                member == null ? null : this.poses.actor(member.replay, actor.getEntity().getForm(), !this.posesChecked));
        }

        this.posesChecked = true;

        this.changes.follow(this.film, this.reach);
    }

    /**
     * The film was edited: the recording from the first tick these edits change is taken back, and
     * everything before it stands.
     *
     * <p>The animation of a replay outside the reach — an actor with nothing simulated and nothing
     * marked up, that nothing simulated hangs off — is drawn by the film and read by nobody here, so
     * moving its keys takes nothing back. A key of an actor that is read takes back the film from two
     * keys before it; a clip, from where it starts or used to; anything else, from the opening
     * frame.</p>
     */
    public void edited(Film film, List<BaseValue> values, boolean impulseEdit)
    {
        int first = SceneChanges.NONE;

        for (BaseValue value : values)
        {
            SceneChanges.Change change = this.changes.affected(film, value, this.reach);

            first = Math.min(first, change.tick());
            this.poses.edited(change);
        }

        if (first != SceneChanges.NONE)
        {
            this.invalidateFrom(first, impulseEdit);
        }
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
            this.manualCalculation() && this.calculationComplete ? this.cache.getComputed() - 1 : this.recordingEnd(this.filmTick),
            this.cache.has(this.filmTick),
            this.manualCalculation() ? this.waitingForCalculation() : this.isStale() || !this.backgroundAllowed(),
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
     * The film moved to {@code tick}: hand every body the frame it is to be drawn in, recording
     * first only what that frame cannot be drawn without.
     *
     * <p>Two halves that no longer have anything to do with each other, and that separation is the
     * whole of Э3. Recording only ever moves forwards, one tick at a time, in whatever time the frames
     * can spare. Drawing reads an array. A scrub to tick 900 does not make the world go anywhere — it
     * reads entry 900 if there is one, and shows plain animation if there is not (Р8.1). Nothing can
     * be "behind" any more, because nothing is chasing anything.</p>
     *
     * <p>The recording used to be done here, on a budget per tick: twelve milliseconds of every
     * fiftieth of a second while the cursor waited, four once it had its frame — a quarter of the
     * time at most, and a run ahead that was never faster than the film plays. The frames pace it now
     * ({@link #advance}, from {@link FilmScenes#onFrame}). Two things stay with the tick: an exported
     * video records through the cursor here whatever it costs, because a frame of the video must
     * never be drawn without its physics; and with no frames being drawn at all, the per-tick budget
     * still moves the recording along.</p>
     *
     * @param paced     whether frames are being drawn, which do the recording
     * @param exporting whether a video is being recorded
     */
    public void tick(int tick, boolean paced, boolean exporting)
    {
        if (tick < 0)
        {
            tick = 0;
        }

        this.filmTick = tick;

        /* A step left running beside the last frame comes in before anything else is done here:
         * the knobs below and the answer to an edit both reach into the world. */
        this.land();

        /* Before the rewind, not after it: these knobs invalidate the recording themselves when they
         * move, and answering them second meant a whole tick was recorded under the new gravity on
         * top of a recording made under the old one, only to be thrown away by the invalidation on
         * the tick after. */
        this.applyWorldSettings();

        if (this.manualCalculation() && !this.calculationRequested)
        {
            this.distribute(tick);
            return;
        }

        this.catchUpWithEdits();

        if (exporting)
        {
            this.recordUntil(tick, Long.MAX_VALUE);
        }
        else if (!paced)
        {
            this.compute(tick);
        }

        this.settle();
        this.distribute(tick);
    }

    /** How much this scene wants a frame's spare time, from not at all up to an author who asked. */
    public enum Demand
    {
        NONE, BACKGROUND, CATCH_UP, REQUESTED
    }

    /**
     * What this scene would do with a frame's spare time right now: nothing, run ahead of the cursor,
     * record the frame the author is looking at, or carry out a calculation the author asked for.
     * A recording that is about to start over has nothing to say until the tick has done that.
     */
    public Demand demand()
    {
        boolean manual = this.manualCalculation();

        if (this.isStale() || this.full || (manual && !this.calculationRequested))
        {
            return Demand.NONE;
        }

        if (this.cache.getComputed() > (manual ? this.requestedEnd : this.recordingEnd(this.filmTick)))
        {
            return Demand.NONE;
        }

        if (manual)
        {
            return Demand.REQUESTED;
        }

        if (!this.cache.has(this.filmTick))
        {
            return Demand.CATCH_UP;
        }

        return this.backgroundAllowed() ? Demand.BACKGROUND : Demand.NONE;
    }

    /** Whether this is the editor's scene, where an author is waiting rather than an audience. */
    public boolean isEditor()
    {
        return this.editor;
    }

    /** Whether an edit has only just landed — the author is probably still dragging something. */
    public boolean isEditing()
    {
        return !this.backgroundAllowed();
    }

    /**
     * Records for as long as {@code deadline} allows, then hands the drawn pair back — a frame's
     * share of the work (see {@link FilmScenes#onFrame}).
     *
     * <p>Only up to the cursor while the cursor is what is missing: the frame's budget was sized for
     * an author waiting on that one frame, and running ahead is the background's business, which
     * keeps clear for a moment after an edit.</p>
     */
    public void advance(long deadline)
    {
        Demand demand = this.demand();

        if (demand == Demand.NONE)
        {
            this.land();

            return;
        }

        int end = this.manualCalculation() ? this.requestedEnd : this.recordingEnd(this.filmTick);

        if (demand == Demand.CATCH_UP)
        {
            end = Math.min(end, this.filmTick);
        }

        this.recordUntil(end, deadline);
        this.settle();
        this.redisplay();
    }

    /**
     * A video is being recorded: the frame about to be drawn must carry its physics, however long that
     * takes. The tick does the same; this covers a frame that comes before its tick does.
     */
    public void recordCursor()
    {
        this.land();

        if (this.isStale() || (this.manualCalculation() && !this.calculationRequested) || this.cache.has(this.filmTick))
        {
            return;
        }

        this.recordUntil(this.filmTick, Long.MAX_VALUE);
        this.settle();
        this.redisplay();
    }

    /** Notes whether the recording now reaches as far as it was asked to. */
    private void settle()
    {
        this.calculationComplete = this.cache.getComputed()
            > (this.manualCalculation() ? this.requestedEnd : this.recordingEnd(this.filmTick));

        if (this.calculationRequested && (this.full || this.cache.getComputed() > this.requestedEnd))
        {
            this.calculationRequested = false;
        }
    }

    /**
     * The film's scene-wide knobs, taken as they are by a scene being put up: nothing is recorded
     * under any others yet. Without this the first tick found them "moved" from the defaults and
     * started a recording over that had only just begun — or one taken over from the scene before.
     */
    private void takeWorldSettings()
    {
        FilmPhysics physics = FilmPhysics.of(this.film);

        this.gravity = physics.gravity.get();
        this.speed = physics.speed.get();
        this.collisionSteps = physics.collisionSteps.get();

        this.world.setGravity(this.gravity);
        this.world.setSpeed(this.speed);
        this.world.setCollisionSteps(this.collisionSteps);
    }

    /**
     * Picks up the film's scene-wide knobs — gravity, speed and collision steps — and throws the
     * recording away when either has moved.
     *
     * <p>They are part of the simulation's arithmetic, not a display option: half gravity is a
     * different fall from the first tick onwards, so nothing worked out under the old value is worth
     * keeping. Cheap to check and rare to change, which is why it lives on the tick rather than
     * needing the film physics panel to tell anyone.</p>
     */
    private void applyWorldSettings()
    {
        FilmPhysics physics = FilmPhysics.of(this.film);
        float gravity = physics.gravity.get();
        int steps = physics.collisionSteps.get();
        float speed = physics.speed.get();

        if (gravity == this.gravity && steps == this.collisionSteps && speed == this.speed)
        {
            return;
        }

        this.gravity = gravity;
        this.speed = speed;
        this.collisionSteps = steps;

        this.world.setGravity(gravity);
        this.world.setSpeed(speed);
        this.world.setCollisionSteps(steps);

        this.invalidate();
    }

    /**
     * Records on the per-tick budget, nearest need first — the schedule for when no frames are being
     * drawn to pace the recording.
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
        int end = this.manualCalculation() ? this.requestedEnd : this.recordingEnd(cursor);

        if (this.cache.getComputed() > end)
        {
            return;
        }

        boolean catchup = !this.cache.has(cursor);

        if (!catchup && !this.backgroundAllowed())
        {
            return;
        }

        this.recordUntil(end, System.nanoTime() + (catchup ? CATCHUP_BUDGET : LOOKAHEAD_BUDGET));
    }

    /**
     * Records forwards until {@code end} is in, the recording is full, or the next step would run
     * past {@code deadline}.
     *
     * <p>A step is only started when its usual cost still fits, so a thin budget is not overrun by a
     * heavy scene — except that a recording which has gone {@link #MIN_PROGRESS} without a step takes
     * one regardless. That keeps the pace the per-tick schedule always had, one step a tick, under
     * any frame's budget.</p>
     *
     * <p>In a scene whose solver is the dear part of a step, the step that no longer fits is launched
     * instead, its solver running while the frame is drawn — see {@link #launch}; one the frame
     * before left running is brought in first.</p>
     */
    private void recordUntil(int end, long deadline)
    {
        if (!this.launched && (this.cache.getComputed() > end || !this.mayStep(deadline) && !this.mayLaunch(deadline)))
        {
            return;
        }

        /* The cast is the film's own entities, and standing them on the ticks being recorded moves
         * the very objects BBS is about to draw. Borrowed for the whole run and handed back in a
         * finally, on the cursor's tick — an exception halfway through would otherwise leave the
         * actors standing wherever the last recorded step put them. */
        this.cast.borrow();

        try
        {
            this.landBorrowed();

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

                if (!this.mayStep(deadline))
                {
                    /* The step that no longer fits may still be started: what stays here of it is
                     * the pose and the drives, and the solver runs while the frame is drawn. */
                    if (this.mayLaunch(deadline))
                    {
                        this.launch();
                    }

                    break;
                }

                long started = System.nanoTime();

                this.step();

                this.steppedAt = System.nanoTime();

                long cost = this.steppedAt - started;

                this.stepCost = this.stepCost == 0 ? cost : (this.stepCost * 7 + cost) / 8;
            }
        }
        finally
        {
            this.cast.restore(this.filmTick);
        }
    }

    /**
     * Whether a step may start with {@code deadline} ahead: when what a step usually costs still
     * fits, or when the recording has gone {@link #MIN_PROGRESS} without one.
     */
    private boolean mayStep(long deadline)
    {
        if (deadline == Long.MAX_VALUE)
        {
            return true;
        }

        long now = System.nanoTime();

        return now - this.steppedAt >= MIN_PROGRESS || deadline - now >= this.stepCost;
    }

    /** Simulates the next tick of the film and writes it into the recording. */
    private void step()
    {
        this.complete(this.timeline.step(this::poseTick));
    }

    /**
     * The rest of a step once its physics is done: writes {@code tick} into the recording, and takes a
     * checkpoint, or the recording kept on disk, when it is time.
     */
    private void complete(int tick)
    {
        this.record(tick);
        this.staging = null;

        if (!this.verifying && this.checkpoints.due(tick))
        {
            this.checkpoint(tick);
        }

        if (this.stored != null && !this.verifying && tick >= STORED_CHECK)
        {
            this.takeStored();
        }
    }

    /**
     * Whether the step that no longer fits in a frame's budget may still be launched — see
     * {@link #launch}: there is a budget at all (an export or a bake waits for every step), Jolt's part
     * of a step is dear enough, the background is allowed, and what stays here of the step fits.
     */
    private boolean mayLaunch(long deadline)
    {
        return deadline != Long.MAX_VALUE && !this.verifying && PhysicsThreads.backgroundAllowed()
            && this.world.getStepCost() >= LAUNCH_ABOVE && deadline - System.nanoTime() >= this.launchCost;
    }

    /**
     * Starts the next step and leaves its physics running in the background while the frame is drawn
     * — the last step of a frame's run, in a scene whose solver is the dear part of a step. Everything
     * up to the solver happens here as in any step: the cast stood on the tick, the pass before the
     * step, the drives, the clips. The rest waits for {@link #land}.
     *
     * <p>Only the solver can go. Everything else a step does reads and writes what BBS draws from — the
     * actors, their forms, the poses of their models — and a frame drawn at the same time would find
     * it half done.</p>
     */
    private void launch()
    {
        long started = System.nanoTime();

        this.timeline.begin(this::poseTick);
        this.launched = true;

        long cost = System.nanoTime() - started;

        this.launchCost = this.launchCost == 0 ? cost : (this.launchCost * 7 + cost) / 8;

        if (!this.launchNoted)
        {
            this.launchNoted = true;

            BBSPhysics.LOGGER.info("Physics scene for film \"{}\" is heavy, {} ms of solver a step: its steps finish beside the frames being drawn.",
                this.film == null ? "?" : this.film.getId(), String.format(java.util.Locale.ROOT, "%.1f", this.world.getStepCost()));
        }
    }

    /**
     * Brings in a step left running in the background by {@link #launch}, when there is one — before
     * anything else touches the scene. Its physics has usually long finished; the rest of the step is
     * done here, and the drawn frame handed back after.
     */
    public void land()
    {
        if (!this.launched)
        {
            return;
        }

        this.cast.borrow();

        try
        {
            this.landBorrowed();
        }
        finally
        {
            this.cast.restore(this.filmTick);
        }

        this.settle();
        this.redisplay();
    }

    /**
     * {@link #land}, with the cast already borrowed.
     *
     * <p>The frame drawn in between handed out its own pose: it stood the cast on the cursor and its
     * own fraction of a tick, gave every form the frame it draws, walked the models it drew. So the
     * step's tick is stood again first, as the step left it — the cast on the tick, its deaths
     * prepared, every rig handed the tick before and then what the pass before the step published:
     * the calls the step made, in the order it made them. The recording then walks whatever it walks
     * afresh, as a step taken on the spot does, and comes out the same to the bit.</p>
     */
    private void landBorrowed()
    {
        if (!this.launched)
        {
            return;
        }

        this.launched = false;

        int tick = this.timeline.finish();

        this.cast.applyReached(tick);
        this.clips.prepareDeaths(this.cast, tick);

        for (SceneActor actor : this.actors)
        {
            actor.readCache(this.cache, tick - 1, true);
        }

        this.anchorsFirst((actor) -> actor.republish(this.staging, tick));
        this.complete(tick);

        this.steppedAt = System.nanoTime();
    }

    /**
     * Stands the film on {@code tick}, for the step that is about to simulate it.
     *
     * <p>Called once per simulated step, which during a scrub is many times per frame — so the order
     * matters: the cast the step reads is placed first, then each actor's pose is walked. Cheap it
     * is not, and it is the price of the viewport agreeing with the exported video. Only the reached
     * replays are placed (see {@link SceneReach}): nothing here reads the rest, and the film puts
     * every actor where it belongs before anything is drawn.</p>
     */
    private void poseTick(int tick)
    {
        this.step += 1;
        this.posed = tick;
        this.cast.applyReached(tick);
        this.clips.prepareDeaths(this.cast, tick);

        /* The viewport may be anywhere. Every drive must instead see the last completed physics
         * tick, including while computing a long batch or starting over after an edit. */
        for (SceneActor actor : this.actors)
        {
            actor.readCache(this.cache, tick - 1, true);
        }

        /* Rebase the live Jolt pose onto this tick's animation before driving anything. Merely
         * reading last tick's local cache would carry a free parent along with its newly moved
         * actor/anchor. This is a private staging pass; the tick is still unreadable to playback. */
        this.staging = this.cache.beginFrame(tick);
        this.recordPoses(tick, this.staging, true);

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
        PhysicsCache pending = this.cache.beginFrame(tick);

        for (SceneBody body : this.bodies)
        {
            body.record(this.world.getBodies(), this.cache, tick);
        }

        this.recordPoses(tick, pending, false);
        this.cache.commit(tick);

        if (this.lostAt < 0 && this.anythingLost())
        {
            this.lostAt = tick;
        }
    }

    /** Anchor targets publish before dependents, including through actors without physics.
     * Marking on entry bounds cyclic anchor graphs just as BBS bounds their matrix walk.
     *
     * @param staging whether this is the pass before the step rather than the recording after it */
    private void recordPoses(int tick, PhysicsCache pending, boolean staging)
    {
        this.anchorsFirst((actor) -> actor.record(this.world, this, this.cache, pending, tick, staging));
    }

    /** Every actor, the actors it hangs off before it — the order the passes around a step go in. */
    private void anchorsFirst(Consumer<SceneActor> visit)
    {
        Set<IEntity> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (SceneActor actor : this.actors)
        {
            this.anchorsFirst(actor.getEntity(), visit, visited);
        }
    }

    private void anchorsFirst(IEntity entity, Consumer<SceneActor> visit, Set<IEntity> visited)
    {
        if (entity == null || !visited.add(entity))
        {
            return;
        }

        Anchor anchor = FilmMatrices.getAnchor(entity);

        if (anchor != null)
        {
            this.anchorsFirst(this.entities.get(anchor.replay), visit, visited);

            if (anchor.previous != null)
            {
                this.anchorsFirst(this.entities.get(anchor.previous.replay), visit, visited);
            }
        }

        SceneActor actor = this.actorOf(entity);

        if (actor != null)
        {
            visit.accept(actor);
        }
    }

    /** Restores the displayed frame pair after the simulation borrowed the runtime slots. */
    private void distribute(int tick)
    {
        if (this.showsImpulsePreview(tick))
        {
            this.showImpulsePreview();
            this.drawnTick = tick;
            this.teleport = true;
            this.shownJumped = true;
            this.shownPrevious = tick;
            return;
        }

        this.impulsePreview = null;

        /* A jump is anything but the one step forward that playback makes: across one there is no
         * meaningful previous tick, and interpolating out of it would draw bodies sliding the whole
         * way. Asking for the same tick again — a paused editor — is not a jump and needs nothing
         * special: both slots end up holding the same numbers, so the interpolation collapses. */
        boolean jumped = this.teleport || (tick != this.drawnTick && tick != this.drawnTick + 1);

        this.teleport = false;
        this.shownJumped = jumped;
        this.shownPrevious = jumped ? tick : this.drawnTick;

        for (SceneBody body : this.bodies)
        {
            body.readCache(this.cache, tick, jumped);
        }

        for (SceneActor actor : this.actors)
        {
            /* Recording borrows the runtime slots too. Restore the displayed pair, not the
             * future tick last touched by background computation. */
            if (!jumped)
            {
                actor.readCache(this.cache, this.drawnTick, true);
            }

            actor.readCache(this.cache, tick, jumped);
        }

        this.drawnTick = tick;
    }

    /**
     * Hands the drawn pair back after a frame's recording borrowed the runtime slots between two
     * ticks.
     *
     * <p>{@link #distribute} cannot simply run again: asked for the tick it last handed out, it takes
     * it for a paused frame and puts that tick in both slots — and between two ticks of playback that
     * is the interpolation thrown away. So the pair it handed out is read back exactly, older half
     * first, and a tick recorded in the meantime is picked up on the way.</p>
     */
    private void redisplay()
    {
        if (this.drawnTick < 0)
        {
            return;
        }

        if (this.showsImpulsePreview(this.drawnTick))
        {
            this.showImpulsePreview();
            return;
        }

        for (SceneBody body : this.bodies)
        {
            if (!this.shownJumped)
            {
                body.readCache(this.cache, this.shownPrevious, true);
            }

            body.readCache(this.cache, this.drawnTick, this.shownJumped);
        }

        for (SceneActor actor : this.actors)
        {
            if (!this.shownJumped)
            {
                actor.readCache(this.cache, this.shownPrevious, true);
            }

            actor.readCache(this.cache, this.drawnTick, this.shownJumped);
        }
    }

    /** Whether the frame drawn for {@code tick} is still the placeholder left by an impulse edit. */
    private boolean showsImpulsePreview(int tick)
    {
        return this.impulsePreview != null && tick == this.impulsePreviewTick
            && !this.cache.has(tick) && !this.full && this.lostAt < 0;
    }

    /**
     * Reapplied after recording, which borrows the same render slots. The placeholder is only
     * something to look at; it never enters the recording or a bake.
     */
    private void showImpulsePreview()
    {
        for (SceneBody body : this.bodies) body.readCache(this.impulsePreview, 0, true);
        for (SceneActor actor : this.actors) actor.readCache(this.impulsePreview, 0, true);
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

    private boolean manualCalculation()
    {
        return this.editor && BBSPhysicsSettings.manualCalculation != null && BBSPhysicsSettings.manualCalculation.get();
    }

    public boolean waitingForCalculation()
    {
        return this.manualCalculation() && !this.calculationComplete && !this.calculationRequested && !this.full
            && (this.isStale() || this.cache.getComputed() <= this.recordingEnd(this.filmTick));
    }

    public void requestCalculation(int tick)
    {
        this.land();
        this.filmTick = Math.max(0, tick);
        this.applyWorldSettings();
        this.invalidate();
        this.calculationComplete = false;
        this.requestedEnd = this.recordingEnd(this.filmTick);
        this.calculationRequested = true;
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
        this.invalidate(false);
    }

    /** Keep the last displayed result only for impulse edits at the unchanged cursor. */
    public void invalidate(boolean impulseEdit)
    {
        this.invalidateFrom(0, impulseEdit);
    }

    /**
     * The film changed from {@code tick} on: the recording from there is taken back on the next tick
     * and everything before it stands. From the opening frame it is the old starting over; in manual
     * calculation any edit only marks the recording as out of date, as it always did.
     *
     * <p>An impulse edit keeps the frame on screen as a placeholder while its tick is being recorded
     * again — only when that frame is among what is taken back.</p>
     */
    public void invalidateFrom(int tick, boolean impulseEdit)
    {
        /* The film is not what the recording kept on disk was checked against any more. */
        this.stored = null;

        if (this.manualCalculation())
        {
            this.stale = true;
            this.calculationRequested = false;
            return;
        }

        if (tick <= 0)
        {
            this.clips.clearDeathImpacts();
        }

        if (!impulseEdit)
        {
            this.impulsePreview = null;
        }
        else if (this.filmTick >= this.takenBackFrom(tick) && !this.isStale() && this.drawnTick == this.filmTick && this.cache.has(this.filmTick))
        {
            this.impulsePreview = this.cache.copyFrame(this.filmTick);
            this.impulsePreviewTick = this.filmTick;
        }

        if (tick <= 0)
        {
            this.stale = true;
        }
        else
        {
            this.staleFrom = Math.min(this.staleFrom, tick);
        }

        this.calculationRequested = false;
        this.calculationComplete = false;
        this.editedAt = System.nanoTime();
    }

    /**
     * The first tick of the recording an edit that changes the film from {@code tick} takes back —
     * the one after the checkpoint it is picked up at — or {@link #FRESH} when it takes back nothing.
     */
    private int takenBackFrom(int tick)
    {
        if (tick <= 0)
        {
            return 0;
        }

        if (tick >= this.cache.getComputed())
        {
            return FRESH;
        }

        SceneCheckpoints.Checkpoint checkpoint = this.checkpoints.before(tick);

        return checkpoint == null ? 0 : checkpoint.tick() + 1;
    }

    /** Whether an edit is waiting to take back part of the recording, or all of it. */
    private boolean isStale()
    {
        return this.stale || this.staleFrom != FRESH;
    }

    /** Answers the edits since the last tick: starting over, or picking up at a checkpoint. */
    private void catchUpWithEdits()
    {
        if (this.stale)
        {
            this.stale = false;
            this.staleFrom = FRESH;

            this.rewind();
        }
        else if (this.staleFrom != FRESH)
        {
            int from = this.staleFrom;

            this.staleFrom = FRESH;

            this.resume(from);
        }
    }

    /**
     * Picks the recording up again for an edit that changed the film from {@code from} on.
     *
     * <p>When nothing recorded yet reaches that far, there is nothing to take back: the world stands
     * where the recording ends and goes on from there with the film as it is now. Otherwise the world
     * and every rig go back to the last checkpoint before {@code from} and the recording after it is
     * dropped. With no checkpoint early enough — or one that will not restore — it starts over.</p>
     */
    private void resume(int from)
    {
        if (from < this.cache.getComputed())
        {
            SceneCheckpoints.Checkpoint checkpoint = this.checkpoints.before(from);

            if (checkpoint == null || !this.restore(checkpoint))
            {
                this.rewind();

                return;
            }
        }

        /* An edit may have hung an actor off another, or let one go. */
        this.reach();
    }

    /** Puts the world and every rig back to {@code checkpoint}, and forgets what was recorded after it. */
    private boolean restore(SceneCheckpoints.Checkpoint checkpoint)
    {
        if (!this.putBack(checkpoint))
        {
            return false;
        }

        int tick = checkpoint.tick();

        this.cache.truncate(tick + 1);
        this.checkpoints.dropAfter(tick);
        this.clips.dropDeathImpactsAfter(tick);
        this.full = false;

        /* Everything was just moved back; whatever was drawn last is not a place anything travelled
         * from. */
        this.teleport = true;

        return true;
    }

    /**
     * Puts the world and every rig back to where they stood at {@code checkpoint} — the rigs first,
     * with the parts of the world Jolt's snapshot does not carry, then the snapshot itself. The
     * recording is left alone.
     */
    private boolean putBack(SceneCheckpoints.Checkpoint checkpoint)
    {
        RigMemory memory = checkpoint.rigs().rewind();

        try
        {
            for (SceneActor actor : this.actors)
            {
                actor.recall(memory, this.world);
            }

            if (!memory.isDrained())
            {
                throw new IllegalStateException("A checkpoint was not read back to its end");
            }
        }
        catch (RuntimeException e)
        {
            BBSPhysics.LOGGER.error("A physics checkpoint could not be read back; the recording starts over instead.", e);

            return false;
        }

        if (!this.world.restoreState(checkpoint.world()))
        {
            BBSPhysics.LOGGER.warn("Jolt refused a physics checkpoint at tick {}; the recording starts over instead.", checkpoint.tick());

            return false;
        }

        this.timeline.resume(checkpoint.tick());
        this.lostAt = checkpoint.lostAt();

        /* No walk from before is any walk of this run. */
        this.step += 1;

        return true;
    }

    /**
     * What the scene built after this one for the same film may take over, as BBS rebuilds the cast
     * — see {@link SceneHandover}. Where the world stands now is taken as one more checkpoint first,
     * so the next scene goes on from exactly here; a scene about to start over has nothing that
     * would stand, and hands over only its ground and its copy of the film.
     */
    SceneHandover handOver()
    {
        this.land();

        int tick = this.timeline.getTick();
        boolean standing = !this.stale && !this.verifying && this.cache.getComputed() == tick + 1;

        if (standing)
        {
            this.checkpoints.add(this.standing(tick));
        }

        SceneHandover handover = new SceneHandover(this.film, this.originX, this.originY, this.originZ, this.window,
            this.cache, Map.copyOf(this.channelKeys), this.world.getBodyCount(), this.checkpoints, tick,
            this.changes, this.reach, this.clips.deathImpacts(), this.full, !standing, this.staleFrom,
            this.gravity, this.speed, this.collisionSteps);

        this.groundHandedOver = true;

        return handover;
    }

    /**
     * Takes over what the scene before this one recorded — see {@link SceneHandover}.
     *
     * <p>Only into the same bodies: the same channels under the same keys, the same layout of a
     * tick, as many bodies in the world — which a scene built from the same film comes to, body for
     * body, in the same order. The world and every rig are put back where the scene before stood, and
     * the edits it had not answered are this scene's to answer. Into anything else — or should
     * putting back fail — this scene records the film from its opening frame, as built.</p>
     */
    private void adopt(SceneHandover given)
    {
        SceneCheckpoints.Checkpoint standing = given.stale ? null : given.checkpoints.at(given.tick);

        /* The ground too: collected afresh, it is another set of blocks — what the recording landed
         * on, and what the contacts it remembers are with, would not be what this scene stands on. */
        boolean sameGround = this.window != null && given.ground != null && this.window.shape() == given.ground.shape();

        /* And under the same knobs: a recording made under another gravity is another film. */
        boolean sameKnobs = given.gravity == this.gravity && given.speed == this.speed && given.collisionSteps == this.collisionSteps;

        if (standing == null || !sameGround || !sameKnobs || !given.channels.equals(this.channelKeys)
            || !given.cache.sameLayout(this.cache) || given.bodies != this.world.getBodyCount())
        {
            return;
        }

        PhysicsCache built = this.cache;
        SceneCheckpoints none = this.checkpoints;

        this.cache = given.cache;
        this.checkpoints = given.checkpoints;

        if (!this.putBack(standing))
        {
            /* Built the same yet not the same, and some rigs may already have been put back: the
             * recording starts over on the next tick, from a world stood on its opening frame. */
            this.cache = built;
            this.checkpoints = none;
            this.stale = true;

            return;
        }

        this.clips.adoptDeathImpacts(given.impacts);
        this.full = given.full;
        this.staleFrom = given.staleFrom;

        /* Everything was just moved to where the scene before had it; whatever is drawn next is a
         * cut, not a step from the opening frame this scene was built on. */
        this.teleport = true;

        BBSPhysics.LOGGER.info("Physics scene for film \"{}\" took over the recording of the one before it: {} ticks, standing on tick {}{}.",
            this.film == null ? "?" : this.film.getId(), this.cache.getComputed(), given.tick,
            this.staleFrom == FRESH ? "" : ", recording again from tick " + this.staleFrom);
    }

    /**
     * The recording kept on disk for this film, read back and checked against everything it was made
     * from that can be checked without recording anything — see {@link RecordingStore}. Null when
     * there is none, or when anything differs; the reason is said once, in the log.
     */
    private RecordingStore.Stored storedCandidate()
    {
        if (this.film == null || this.film.getId() == null)
        {
            return null;
        }

        try
        {
            RecordingStore.Stored stored = RecordingStore.load(this.film.getId(), this.worldKey);

            if (stored == null)
            {
                return null;
            }

            String why = this.unlike(stored);

            if (why != null)
            {
                BBSPhysics.LOGGER.info("The physics recording kept on disk for film \"{}\" is not used: {}. The film is recorded afresh.", this.film.getId(), why);

                return null;
            }

            return stored;
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.warn("The physics recording kept on disk could not be checked; the film is recorded afresh.", e);

            return null;
        }
    }

    /** What makes {@code stored} not this film's recording, the cheapest questions first — or null. */
    private String unlike(RecordingStore.Stored stored)
    {
        if (!RecordingStore.same(stored.environment(), RecordingStore.environment()))
        {
            return "the addon, BBS or the game is another build";
        }

        if (stored.ticks() <= STORED_CHECK || stored.floats().length != stored.ticks() * this.cache.getStride())
        {
            return "it is too short to check";
        }

        if (stored.originX() != this.originX || stored.originY() != this.originY || stored.originZ() != this.originZ
            || stored.gravity() != this.gravity || stored.speed() != this.speed || stored.collisionSteps() != this.collisionSteps)
        {
            return "the film's physics knobs or its first actor's place differ";
        }

        if (this.window == null || !RecordingStore.same(stored.ground(), this.window.ground()))
        {
            return "the blocks of the world under the film differ";
        }

        if (stored.bodies() != this.world.getBodyCount() || !stored.channels().equals(this.channelKeys) || !this.sameWidths(stored.widths()))
        {
            return "the film builds into other bodies";
        }

        boolean standing = false;

        for (SceneCheckpoints.Checkpoint checkpoint : stored.checkpoints())
        {
            standing |= checkpoint.tick() == stored.standing();
        }

        if (!standing || stored.standing() != stored.ticks() - 1)
        {
            return "it ends without a checkpoint to go on from";
        }

        if (!RecordingStore.same(stored.assets(), RecordingStore.assets(this.actorForms())))
        {
            return "a model or picture file of the film's forms changed";
        }

        if (!RecordingStore.same(stored.filmData(), this.filmFingerprint()))
        {
            return "the film says something else now";
        }

        return null;
    }

    private boolean sameWidths(int[] widths)
    {
        if (widths.length != this.cache.getChannelCount())
        {
            return false;
        }

        for (int i = 0; i < widths.length; i++)
        {
            if (widths[i] != this.cache.getWidth(i))
            {
                return false;
            }
        }

        return true;
    }

    /** The root form of every actor, for the files they are made from. */
    private List<Form> actorForms()
    {
        List<Form> forms = new ArrayList<>(this.actors.size());

        for (SceneActor actor : this.actors)
        {
            Form form = actor.getEntity().getForm();

            if (form != null)
            {
                forms.add(form);
            }
        }

        return forms;
    }

    /** Everything of the film this recording is made from, digested — see {@link SceneChanges#fingerprint}. */
    private byte[] filmFingerprint()
    {
        Fingerprint print = new Fingerprint();

        this.changes.fingerprint(this.film, this.reach, print);

        return print.digest();
    }

    /**
     * The last check: the film's first second, just recorded afresh, against the first second kept —
     * bit for bit, every channel. Equal, and the recording kept is taken whole, and the world goes on
     * from where it stood when it was kept. Different in anything, and nothing is taken: the film is
     * recorded on from here as if there had been nothing on disk.
     */
    private void takeStored()
    {
        RecordingStore.Stored stored = this.stored;

        this.stored = null;

        PhysicsCache kept = this.cache.withRecorded(stored.floats(), stored.ticks());

        for (int tick = 0; tick <= STORED_CHECK; tick++)
        {
            int channel = this.cache.firstDifference(kept, tick);

            if (channel >= 0)
            {
                BBSPhysics.LOGGER.info("The physics recording kept on disk for film \"{}\" is not used: recorded afresh, tick {} differs, first on channel '{}'. The film is recorded afresh.",
                    this.film.getId(), tick, this.channelName(channel));

                return;
            }
        }

        SceneCheckpoints checkpoints = new SceneCheckpoints();
        SceneCheckpoints.Checkpoint standing = null;

        for (SceneCheckpoints.Checkpoint checkpoint : stored.checkpoints())
        {
            checkpoints.add(checkpoint);

            if (checkpoint.tick() == stored.standing())
            {
                standing = checkpoint;
            }
        }

        PhysicsCache fresh = this.cache;
        SceneCheckpoints own = this.checkpoints;

        this.cache = kept;
        this.checkpoints = checkpoints;

        if (standing == null || !this.putBack(standing))
        {
            /* The same first second, yet the world would not stand where the recording ends: the
             * recording starts over on the next tick rather than going on from half of either. */
            this.cache = fresh;
            this.checkpoints = own;
            this.stale = true;

            return;
        }

        this.full = stored.full();
        this.teleport = true;

        BBSPhysics.LOGGER.info("Physics scene for film \"{}\" took the recording kept on disk: {} ticks.", this.film.getId(), this.cache.getComputed());
    }

    /**
     * What this scene keeps on disk as it goes — see {@link RecordingStore} — or null when there is
     * nothing to keep that would stand: the recording waits on an edit, or holds no more than the
     * second every scene records to check a kept one by.
     */
    private RecordingStore.Stored storable()
    {
        if (this.manualCalculation() || this.film == null || this.film.getId() == null || this.stale || this.staleFrom != FRESH
            || this.verifying || this.window == null)
        {
            return null;
        }

        int tick = this.timeline.getTick();

        if (this.cache.getComputed() != tick + 1 || tick <= STORED_CHECK)
        {
            return null;
        }

        try
        {
            this.checkpoints.add(this.standing(tick));

            int[] widths = new int[this.cache.getChannelCount()];

            for (int i = 0; i < widths.length; i++)
            {
                widths[i] = this.cache.getWidth(i);
            }

            return new RecordingStore.Stored(this.film.getId(), this.worldKey, RecordingStore.environment(),
                RecordingStore.assets(this.actorForms()), this.filmFingerprint(), this.window.ground(),
                this.originX, this.originY, this.originZ, this.gravity, this.speed, this.collisionSteps,
                this.world.getBodyCount(), new java.util.LinkedHashMap<>(this.channelKeys), widths, this.cache.getComputed(),
                this.cache.recorded(), this.full, this.checkpoints.all(), tick);
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.warn("A physics recording could not be put together to be kept on disk.", e);

            return null;
        }
    }

    /** Takes a checkpoint after recording {@code tick}. */
    private void checkpoint(int tick)
    {
        RigMemory memory = new RigMemory();

        for (SceneActor actor : this.actors)
        {
            actor.remember(memory);
        }

        this.checkpoints.add(new SceneCheckpoints.Checkpoint(tick, this.world.saveState(), memory.trim(), this.lostAt));

        if (CHECK_RESUME)
        {
            this.verify(tick);
        }
    }

    /**
     * Development check: records the second before the checkpoint just taken a second time, picked
     * up from the checkpoint before it, and compares the two bit for bit.
     *
     * <p>A difference means something a rig carries from tick to tick is missing from its memory
     * (see {@link SceneRig#remember}), so picking up after an edit would come out differently from
     * recording the film through — the editor and the video would disagree. Reported once per scene,
     * with the tick and the channel it showed on first. Afterwards the world is put back where the
     * first run left it, so the recording goes on as if nothing had been checked.</p>
     */
    private void verify(int tick)
    {
        SceneCheckpoints.Checkpoint earlier = this.checkpoints.before(tick);
        SceneCheckpoints.Checkpoint latest = this.checkpoints.at(tick);

        if (earlier == null || latest == null || this.verifyReported)
        {
            return;
        }

        PhysicsCache straight = this.cache;

        this.verifying = true;

        try
        {
            this.cache = straight.copyPrefix(earlier.tick() + 1);

            if (!this.putBack(earlier))
            {
                return;
            }

            for (int at = earlier.tick() + 1; at <= tick; at++)
            {
                this.step();

                int channel = straight.firstDifference(this.cache, at);

                if (channel >= 0)
                {
                    this.verifyReported = true;

                    BBSPhysics.LOGGER.warn("Physics checkpoint check: picked up at tick {}, the recording differs from the run that made it at tick {}, first on channel '{}'. Something a rig carries between ticks is not in its checkpoint.",
                        earlier.tick(), at, this.channelName(channel));

                    break;
                }
            }
        }
        finally
        {
            this.cache = straight;
            this.putBack(latest);
            this.verifying = false;
        }
    }

    /** A channel's name for a report — the key it was claimed under, or its number. */
    private String channelName(int channel)
    {
        for (Map.Entry<String, Integer> entry : this.channelKeys.entrySet())
        {
            if (entry.getValue() == channel)
            {
                return entry.getKey();
            }
        }

        return "#" + channel;
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
     * @param formPath where the form sits in the replay's form tree, or null for the whole actor
     * @return what was written, or null when this scene has no actor playing that replay
     */
    public PhysicsBake.Result bake(Replay replay, String formPath)
    {
        this.land();

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
                this.clips.prepareDeaths(this.cast, tick);

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

                if (formPath == null || formPath.isEmpty()) bake.anchorFrame(root, new Matrix4f());

                Anchor anchor = FilmMatrices.getAnchor(member.entity);
                if ((formPath == null || formPath.isEmpty()) && SceneActor.releaseAnchor(anchor) != anchor)
                {
                    for (SceneActor other : this.actors) other.readCache(this.cache, tick, true);
                    Matrix4f effective = this.actorWorld(member.entity);
                    Anchor authored = anchor.copy();
                    authored.previous = anchor.previous == null ? null : anchor.previous.copy();
                    authored.x = anchor.x;
                    Matrix4f plain = FilmMatrices.getMatrixForRenderWithRotation(member.entity, 0D, 0D, 0D, 1F);
                    Pair<Matrix4f, Float> resolved = FilmMatrices.getTotalMatrix(
                        this.entities, authored, plain, 0D, 0D, 0D, 1F, 0, false, null);
                    bake.anchorFrame(root, new Matrix4f(resolved.a == null ? plain : resolved.a).invert().mul(effective));
                }

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

    /** Bakes all supported physics in the actor's form tree in a single transaction. */
    public PhysicsBake.Result bake(Replay replay)
    {
        return this.bake(replay, null);
    }

    /**
     * Records the film through {@code end} with no time budget, and answers the last tick actually
     * recorded — short of {@code end} only when the recording ran out of room.
     */
    private int ensureRecorded(int end)
    {
        this.applyWorldSettings();
        this.catchUpWithEdits();
        this.recordUntil(end, Long.MAX_VALUE);

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
        this.clips.clearDeathImpacts();
        this.cast.borrow();

        try
        {
            /* An edit may have hung an actor off another, or let one go: what a step reads is
             * worked out again before anything is stepped. A new step, too, so that nothing walked
             * before the edit is ever taken for a walk of this run. */
            this.step += 1;
            this.posed = 0;
            this.reach();
            this.checkpoints.clear();

            /* Back to the world as built, before anything is stood on the opening frame: what a
             * run leaves behind in Jolt — the contacts it remembers, the impulses its joints carry
             * into the next step — would otherwise carry into the run starting now, and the same
             * film would come out a hair different after an edit than opened afresh. The knobs are
             * the film's as they are now, not as the world was built under. */
            if (this.initial != null && this.putBack(this.initial))
            {
                this.world.setGravity(this.gravity);
                this.world.setSpeed(this.speed);
                this.world.setCollisionSteps(this.collisionSteps);
            }

            this.cast.apply(0);
            this.clips.prepareDeaths(this.cast, 0);

            for (SceneActor actor : this.actors)
            {
                actor.readCache(this.cache, -1, true);
            }

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

        if (weight <= 0F || !resolve.hasTarget())
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
        Anchor targetAnchor = resolve;

        if (anchor.isFadeIn() || anchor.isFadeOut())
        {
            targetAnchor = resolve.copy();
            targetAnchor.previous = null;
            targetAnchor.x = 1F;
        }

        Pair<Matrix4f, Float> matrix = FilmMatrices.getTotalMatrix(
            this.entities, targetAnchor, new Matrix4f(), 0D, 0D, 0D, 1F, 0, true, null);

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
        return this.actorWorld(entity, 1F);
    }

    Matrix4f actorWorld(IEntity entity, float transition)
    {
        return this.actorWorld(entity, transition, true);
    }

    Matrix4f actorWorld(IEntity entity, float transition, boolean anchoredFrame)
    {
        /* Zero camera: the actor's placement in the world, not on the screen. */
        Matrix4f matrix = FilmMatrices.getMatrixForRenderWithRotation(entity, 0D, 0D, 0D, transition);
        Form root = entity.getForm();

        if (root == null || !anchoredFrame)
        {
            return matrix;
        }

        Pair<Matrix4f, Float> anchored = FilmMatrices.getTotalMatrix(
            this.entities, FilmMatrices.getAnchor(entity), matrix, 0D, 0D, 0D, transition, 0, false, null);

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

        boolean evaluating = RagdollPoseApplier.isEvaluating();
        RagdollPoseApplier.setEvaluating(true);

        try
        {
            return FormUtilsClient.getRenderer(root).collectMatrices(entity, 1F);
        }
        finally
        {
            RagdollPoseApplier.setEvaluating(evaluating);
        }
    }

    /**
     * Warms up the animator of every model form in the tree before the matrix walk runs. The walk
     * assumes the render path has already done this — on a freshly built cast it has not, and a
     * model form with body parts trips over the gap.
     */
    static void ensureAnimators(Form form)
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
     * Whether the region of the world this scene collected no longer matches what the film asks
     * for, which is the one change a rewind cannot answer: the blocks are a body, and a different set
     * of blocks is a different set of bodies — a different shape of recording, since a channel is
     * fixed when the scene is sealed. So the scene is rebuilt from scratch instead, and the caller is
     * the one who can do that.
     */
    public boolean needsRebuild()
    {
        if (this.manualCalculation()) return false;
        FilmPhysics physics = FilmPhysics.of(this.film);
        return this.window == null
            || this.window.radius() != physics.worldRadius.get()
            || this.window.below() != physics.worldBelow.get()
            || this.window.above() != physics.worldAbove.get()
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
        /* A step still running beside the last frame is brought in first — kept on disk with the rest,
         * or at the very least waited for before the world goes. */
        try
        {
            this.land();
        }
        catch (Throwable e)
        {
            this.launched = false;
            this.built = false;

            BBSPhysics.LOGGER.warn("A physics step left running beside the frames could not be brought in as the scene closed.", e);
        }

        /* Kept on disk first, while the world and the rigs are still there to take a checkpoint of —
         * unless the recording went on to the scene after this one. */
        if (this.built && !this.groundHandedOver)
        {
            RecordingStore.Stored keep = this.storable();

            if (keep != null)
            {
                RecordingStore.save(keep);
            }
        }

        this.built = false;

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

        /* None yet when the assembly failed before the cast was counted. */
        if (this.world != null)
        {
            this.world.close();
        }

        if (!this.groundHandedOver && this.window != null)
        {
            this.window.release();
        }
    }
}
