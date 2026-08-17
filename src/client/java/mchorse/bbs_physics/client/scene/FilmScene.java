package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.actions.ImpulseActionClip;
import mchorse.bbs_physics.actions.TearActionClip;
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.client.ragdoll.RagdollPoseApplier;
import mchorse.bbs_physics.client.ragdoll.RagdollWelds;
import mchorse.bbs_physics.engine.PhysicsCache;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsTimeline;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The physics of one film: a single Jolt world, a recording of what it did on every tick, and the
 * bodies in it — the world's blocks, everything the cast's forms mark up as collidable, and every
 * physics body form anywhere in their trees.
 *
 * <p><b>The world is a recorder, not a source of pictures</b> (§2.5, §6). It only ever moves
 * forwards, one tick at a time, writing each tick's answers into {@link PhysicsCache}; a drawn
 * frame reads that recording and never asks the world anything. Scrubbing therefore costs an array
 * lookup, an edit costs a counter reset, and a frame the recording has not reached yet is drawn as
 * plain animation (Р8.1) until the background catch-up gets there. What this replaced — snapshots,
 * rewinds, a re-simulation budget, a scene that could be "behind" the cursor — is gone entirely,
 * along with the class of bugs where the viewport and the exported video disagreed.</p>
 *
 * <p><b>Everything is simulated around an origin</b> rather than in raw world coordinates. Jolt is
 * built here in single precision, where a float's resolution at a hundred thousand blocks out is
 * coarse enough to see, and Minecraft worlds go much further than that. So the scene picks a block
 * to be its zero — the first actor's position when the scene is built — and every body lives near
 * it. World coordinates only come back at drawing time.</p>
 *
 * <p>Per tick, each actor's pose is evaluated <b>once</b> (the {@code collectMatrices} walk — the
 * same evaluation the anchors and gizmos use) and shared by everything hanging off that actor: the
 * bone bodies and every physics body form in its tree. The walk also passes through each physics
 * body's renderer, which captures the frame above it — the piece a nested body needs to carry the
 * simulation's world-space answer back into the local transform the renderer substitutes.</p>
 *
 * <p><b>Everything is read at whole ticks.</b> BBS writes a form's keyframed values when it draws,
 * at the tick plus however far into it the frame happens to fall, so a simulation that simply read
 * them would be sampling the animation at a moment decided by the frame rate — and a film would
 * come out differently on a faster machine, which is the one thing a film's physics may not do.
 * So the scene re-applies the replay's properties at the integer tick before evaluating, and asks
 * the entity for the tick it is on rather than the tick it is coming from.</p>
 */
public class FilmScene implements AutoCloseable
{
    /**
     * How long a frame may spend simulating while the cursor is ahead of the recording, and how
     * long it may spend running ahead of the cursor once it has caught up.
     *
     * <p>A budget in <em>time</em>, not in steps, which is the correction the old design needed:
     * the cost of a step is a pose evaluation per actor and varies by an order of magnitude between
     * a crate and a cast of models, so a fixed step count meant a stall of unpredictable length.
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

    /** The film's cast, as the anchor resolution needs it — an anchor points at another actor. */
    private final IntObjectMap<IEntity> entities;

    /** The film being simulated, for its length — the recording has no reason to run past the end. */
    private final Film film;

    /** The world-space block this scene's physics is centred on — see the class note. */
    private double originX;
    private double originY;
    private double originZ;

    private final List<SceneBody> bodies = new ArrayList<>();
    private final List<EntityRigs> rigs = new ArrayList<>();

    /**
     * Whether the film has been edited since this simulation ran, so everything it worked out is a
     * consequence of numbers that are no longer there. Raised from outside (see
     * {@link FilmScenes#onFilmEdited()}) and answered on the next tick by starting over.
     */
    private boolean stale;

    /** When the last edit arrived — the background catch-up keeps clear for a moment after one. */
    private long editedAt;

    /** Whether the recording has hit its ceiling, so the fact is reported once rather than per tick. */
    private boolean full;

    /** The scene-wide knobs this recording was made under — see {@link #applyWorldSettings()}. */
    private float gravity = PhysicsWorld.EARTH_GRAVITY;
    private int collisionSteps = PhysicsWorld.COLLISION_STEPS;

    /** The tick the film last asked for, against which the simulation's own tick is reported. */
    private int filmTick;

    /** The tick the bodies were last handed, so a jump can be told from a step forward. */
    private int drawnTick = -1;

    /**
     * Whether the next frame handed out must be a cut rather than a step, whatever the ticks say.
     * Raised by a rewind: the bodies have been moved back to the opening frame, so the pose each
     * of them was last drawn in is not a place it travelled from.
     */
    private boolean teleport;

    /** The region the world's blocks were collected from — see {@link #buildGround()}. */
    private WorldCollider.Window window;

    /**
     * Model forms whose model had not finished loading when this scene was assembled, so nothing
     * could be built for them — see {@link #needsRebuild()}.
     */
    private final List<ModelForm> awaited = new ArrayList<>(0);

    /** Scratch for the status readout, which runs per drawn frame. */
    private final Vector3f probe = new Vector3f();

    /**
     * Bone names a tear clip asked for that no ragdoll of the actor has, so the fact is reported
     * once per name rather than on every tick of every re-recording.
     */
    private final Set<String> warnedTears = new HashSet<>(0);

    /**
     * Everything simulated for one actor: its bone bodies and the physics body forms found in its
     * form tree, grouped so the actor's pose is evaluated once for all of them.
     */
    private static final class EntityRigs
    {
        private final IEntity entity;

        /**
         * The replay this actor is played from, or null when the film has none for it. Held for
         * one reason: the form's keyframed values — the authority handle above all — are only
         * written into the form when BBS renders, at a fractional tick that depends on the frame
         * rate. Physics has to read them at the whole tick it is simulating, or the same film
         * simulates differently on a faster machine.
         */
        private final Replay replay;

        private final ActorRig bones;
        private final List<PhysicsBodyRig> bodyRigs;
        private final List<ActorRagdoll> ragdolls;
        private final List<ClothRig> cloths;
        private final List<BalloonRig> balloons;

        /**
         * Who among this actor's bodies is excused from colliding with whom. Native and held by
         * Jolt by pointer, so it lives here for as long as the bodies do rather than being dropped
         * once the scene is assembled.
         */
        private final ActorCollisionGroup group;

        /**
         * How far each of this actor's ragdolled bones has been carried from its animated pose,
         * refilled every tick by the ragdolls and read by everything pinned to them — see
         * {@link ActorRagdoll#publish}. Per actor, because the paths inside two actors are the
         * same strings and a shared map would have one character's cape reading another's fall.
         */
        private final Map<String, Matrix4f> boneDeltas = new HashMap<>();

        /**
         * Whether this actor's last evaluation failed, so the failure is reported once instead of
         * sixty times a second. The usual cause is a model that has not loaded yet — BBS's matrix
         * walk trips over body parts when the animator is not there — and it clears itself once
         * the model arrives.
         */
        private boolean broken;

        private EntityRigs(IEntity entity, Replay replay, ActorRig bones, List<PhysicsBodyRig> bodyRigs, List<ActorRagdoll> ragdolls, List<ClothRig> cloths, List<BalloonRig> balloons, ActorCollisionGroup group)
        {
            this.entity = entity;
            this.replay = replay;
            this.bones = bones;
            this.bodyRigs = bodyRigs;
            this.ragdolls = ragdolls;
            this.cloths = cloths;
            this.balloons = balloons;
            this.group = group;
        }
    }

    /**
     * One actor of the film, whether or not it has anything simulated hanging off it.
     *
     * <p>Wider than {@link EntityRigs} on purpose: standing the film on a tick means standing the
     * <em>whole</em> cast on it, including actors with no bodies of their own. An actor anchored to
     * another one is placed relative to it, so an unsimulated actor left on the wrong tick would
     * drag everything riding it there too.</p>
     */
    private static final class CastMember
    {
        private final IEntity entity;
        private final Replay replay;
        private final ActorState state = new ActorState();

        private CastMember(IEntity entity, Replay replay)
        {
            this.entity = entity;
            this.replay = replay;
        }
    }

    private final List<CastMember> cast = new ArrayList<>();

    /**
     * One ragdoll's claim on an actor's markup, held between the moment its pieces are taken and
     * the moment it is built — the gap in which the kinematic bones are created, so that a falling
     * part can be jointed to a bone the animation kept.
     */
    private record ClaimedRagdoll(ModelForm form, String formPath, FormRagdoll config, Map<String, String> welds, List<CollisionCollector.Piece> claimed)
    {}

    public FilmScene(BaseFilmController controller)
    {
        this.world = new PhysicsWorld();
        this.timeline = new PhysicsTimeline(this.world);
        this.entities = controller.getEntities();
        this.film = controller.film;

        boolean built = false;

        try
        {
            this.assemble(controller);

            built = true;
        }
        finally
        {
            if (!built)
            {
                /* Half a scene is worse than none: a Jolt world is native memory that no garbage
                 * collector ever comes back for, and the forms already claimed hold a state
                 * belonging to a simulation that will never step again. The caller only sees the
                 * exception and drops the object on the floor, so cleaning up is this
                 * constructor's job. Closing twice is harmless, which is what makes this safe. */
                this.close();
            }
        }
    }

    /** Everything the constructor does that can fail — see the cleanup it is wrapped in. */
    private void assemble(BaseFilmController controller)
    {
        List<Integer> order = castOrder(controller);

        this.collectCast(controller, order);
        this.borrowCast();

        try
        {
            /* Built as the film's opening frame, not as the frame the cursor happens to be on. A
             * scene is the film's physics, so it must not depend on where the author was standing
             * when it was assembled — including the origin it is centred on, which is a rounding
             * of an actor's position and would otherwise land on a different block for the same
             * film opened at a different moment. */
            this.applyCast(0);
            this.pickOrigin(controller, order);
            this.buildGround();
            this.buildRigs();

            this.world.optimize();

            /* The recording's shape is fixed here: every channel exists, so a tick is a fixed
             * number of floats and can be indexed arithmetically. A body appearing later would
             * shift every channel after it, which is why a change to the set of bodies rebuilds
             * the scene. */
            this.cache.seal();

            /* The world as assembled is tick 0 of the film — the opening frame, whatever the cursor
             * happened to be on — and that is the recording's first entry. A physics clip sitting
             * on frame 0 fires here: the stepping loop only ever poses tick 1 onwards, so without
             * this the film's very first frame would be the one frame a push cannot land on.
             *
             * Inside the borrow, deliberately: recording a tick reads the handle off the form, and
             * the form only holds tick 0's handle while the cast is standing on tick 0. Written
             * after the cast was handed back — as it was — the film's opening frame carried
             * whatever the handle happened to say at the cursor. */
            this.timeline.start();
            this.applyClips(0);
            this.record(0);
        }
        finally
        {
            this.returnCast(controller.getTick());
        }
    }

    /** The cast in simulation order, with the replay each actor is played from. */
    private void collectCast(BaseFilmController controller, List<Integer> order)
    {
        List<Replay> replays = controller.film == null ? null : controller.film.replays.getList();

        for (int index : order)
        {
            IEntity entity = controller.getEntities().get(index);

            if (entity != null)
            {
                this.cast.add(new CastMember(entity, replays == null ? null : CollectionUtils.getSafe(replays, index)));
            }
        }
    }

    /**
     * Takes a copy of where every actor stands, because the simulation is about to move them.
     *
     * <p>Physics has to read the film at whole ticks of its own choosing, and the only way to read
     * an actor at a tick is to write that tick's keyframes into the entity — the same entity BBS is
     * about to draw. So it is borrowed and handed back, always in a {@code finally}: an exception
     * halfway through a seek would otherwise leave the cast standing wherever the last simulated
     * step put them, which is a scene visibly broken by a failure that was recovered from.</p>
     */
    private void borrowCast()
    {
        for (CastMember member : this.cast)
        {
            member.state.capture(member.entity);
        }
    }

    /** Hands the cast back to the film, standing on {@code tick} exactly as it was found. */
    private void returnCast(int tick)
    {
        /* Both halves: the keyframes put back everything an actor carries — held items, equipment,
         * the lot — and the snapshot then puts the placement back verbatim, because BBS pairs the
         * current tick with the previous one differently depending on whether the film is playing,
         * and only a copy knows which rule was in force. */
        this.applyCast(tick);

        for (CastMember member : this.cast)
        {
            member.state.restore(member.entity);
        }
    }

    /**
     * Stands the whole cast on {@code tick}: where each actor is, and what its form's animated
     * properties say at that moment.
     *
     * <p>Done for everyone before any pose is evaluated, never actor by actor as each is walked. An
     * anchored actor is placed through the actor it rides, so a walk that ran while half the cast
     * was still on the previous tick would resolve that anchor against a stale position.</p>
     */
    private void applyCast(int tick)
    {
        for (CastMember member : this.cast)
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

    /**
     * The cast by replay index, ascending.
     *
     * <p>BBS keys its actors in a hash map, whose iteration order is an implementation detail. The
     * order matters here in a way it does not there: it decides which actor the scene is centred
     * on and in which order bodies enter the world, and Jolt resolves a pile in body order. Two
     * runs of the same film that disagreed about it would settle a stack of crates differently.</p>
     */
    private static List<Integer> castOrder(BaseFilmController controller)
    {
        List<Integer> order = new ArrayList<>(controller.getEntities().keySet());

        Collections.sort(order);

        return order;
    }

    /**
     * Builds the simulated side of every actor: a kinematic body for every slot its forms mark up
     * as collidable, a ragdoll for every model that asked for one, and a rigid body for every
     * physics body form anywhere in its tree. An actor with nothing marked up and no physics body
     * is skipped entirely — that is the default state of a form, not a failure.
     */
    private void buildRigs()
    {
        /* Distinct per actor — and per sheet of cloth, which draws from the same counter for the
         * same reason: bodies of one group consult its filter, bodies of different groups never do
         * and collide normally. Two groups sharing an id would consult each other's filter by
         * subgroup index — nonsense pairs, and one character's arm excused from another's. */
        int group = 0;

        for (CastMember member : this.cast)
        {
            IEntity entity = member.entity;
            Form root = entity.getForm();

            if (root == null)
            {
                continue;
            }

            /* Before anything is measured, because a model that has not arrived measures to nothing
             * and would otherwise leave this actor silently unsimulated for the rest of the film. */
            collectAwaited(root, this.awaited);

            Replay replay = member.replay;

            /* The pose the colliders are measured against. Evaluated before anything is built,
             * because a shape's size has to come out of the frame it will live in — a model at 2×
             * collides at 2× — and because a body welded out of several bones needs to know where
             * those bones are relative to it. */
            MatrixCache matrices = this.evaluate(entity, root);

            if (matrices == null)
            {
                continue;
            }

            Matrix4f actorWorld = this.actorWorld(entity);

            /* One collection of the actor's markup, divided between owners: a ragdoll-enabled
             * model claims its bone slots, everything left over becomes plain kinematic bones. */
            List<CollisionCollector.Piece> pieces = CollisionCollector.collectActor(root, matrices);
            List<ActorRagdoll> ragdolls = new ArrayList<>(0);

            /* Sized before the markup is divided between owners, because that division does not
             * change how many bodies there will be — only which half builds them. */
            ActorCollisionGroup actorGroup = new ActorCollisionGroup(group, pieces.size());

            group += 1;

            /* Claims first, builds after the kinematic bones exist — in that order deliberately.
             * A bone the author left out of the ragdoll is a kinematic body, and a falling part is
             * now jointed to it ("the ragdoll is only on the head": the head hangs off the walking
             * torso instead of dropping free), so the bodies it hangs off have to be there before
             * any joint is made. Whether a model can carry a ragdoll at all is checked before its
             * pieces are claimed, because a claim can no longer be undone once the rig is built. */
            List<ClaimedRagdoll> claims = new ArrayList<>(0);

            for (Pair<ModelForm, String> found : discoverRagdolls(root, "", new ArrayList<>(0)))
            {
                if (!ActorRagdoll.supports(found.a))
                {
                    continue;
                }

                FormRagdoll config = FormRagdolls.get(found.a);

                /* Before the claim, because a welded bone is claimed too — it has no body of its
                 * own, so leaving it behind would put a second collider where its owner's shape
                 * already is, standing on the animation while the owner falls away from it. */
                Map<String, String> welds = RagdollWelds.resolve(config, pieces, found.b, found.a);
                List<CollisionCollector.Piece> claimed = claimBonePieces(pieces, found.b, config, welds);

                if (!claimed.isEmpty())
                {
                    claims.add(new ClaimedRagdoll(found.a, found.b, config, welds, claimed));
                }
            }

            ActorRig bones = ActorRig.build(this.world, root, matrices, this, pieces, actorGroup);

            for (ClaimedRagdoll claim : claims)
            {
                /* The bones of this form the animation kept — what a part with no falling parent
                 * can be attached to. Gathered from what is left after every claim, so a bone
                 * claimed by another ragdoll of the same actor is never offered. */
                List<CollisionCollector.Piece> kinematic = new ArrayList<>(0);

                for (CollisionCollector.Piece piece : pieces)
                {
                    if (RagdollWelds.isBonePiece(piece, claim.formPath))
                    {
                        kinematic.add(piece);
                    }
                }

                ActorRagdoll ragdoll = ActorRagdoll.build(this.world, claim.form, claim.formPath, claim.claimed, claim.welds, kinematic, bones, matrices, actorWorld, this, actorGroup);

                if (ragdoll != null)
                {
                    ragdolls.add(ragdoll);
                }
                else
                {
                    /* Every claimed piece failed to become a body — a pose broken enough that no
                     * frame or shape came out of it. The bones are simply absent until the cast is
                     * next rebuilt; said out loud because absent collision is otherwise invisible. */
                    BBSPhysics.LOGGER.warn("No ragdoll part of '{}' could be built; its bones have no bodies until the scene is rebuilt.", claim.form.getDisplayName());
                }
            }

            List<PhysicsBodyRig> bodyRigs = new ArrayList<>(0);
            List<ClothRig> cloths = new ArrayList<>(0);
            List<BalloonRig> balloons = new ArrayList<>(0);

            this.discoverBodies(root, "", matrices, bodyRigs, null);

            /* Each sheet takes an id of its own from the same counter, so its stand-ins are excused
             * from it alone — see ClothProxy. */
            group = this.discoverCloths(root, "", matrices, actorWorld, cloths, group, null);

            this.discoverBalloons(root, "", matrices, actorWorld, balloons, null);

            if (bones == null && bodyRigs.isEmpty() && ragdolls.isEmpty() && cloths.isEmpty() && balloons.isEmpty())
            {
                continue;
            }

            /* Both halves of the actor exist now, which is the first moment anything knows every
             * ragdoll part and every kinematic bone at once. */
            actorGroup.seal();

            EntityRigs rigs = new EntityRigs(entity, replay, bones, bodyRigs, ragdolls, cloths, balloons, actorGroup);

            this.rigs.add(rigs);

            /* Placed outright rather than steered: bodies are created at the origin, and letting
             * them travel to their real spots would sweep them through the scene on the first
             * tick. Simulated bodies too, which is what {@code reset} adds — a crate that is
             * already released at the film's opening frame has only its keyframes to say where it
             * starts, and without this it would begin its fall from the scene's origin instead,
             * with the author's coordinates never read at all. */
            this.updateRigs(rigs, 0, true);
        }
    }

    /**
     * Finds every model form in a tree whose model BBS has not finished loading.
     *
     * <p>BBS loads models on a thread of its own and hands back null for one that has not arrived,
     * which the scene has no way to work around: a form with no model has no bones, no geometry to
     * measure collision from and no groups to hand a pose back through, so <em>nothing</em> is built
     * for it — no ragdoll, no kinematic bones, not even a collision shape. A film opened from cold
     * assembles its scene in the same moment its models are requested, so this is the common case
     * and not the rare one.</p>
     *
     * <p>What made it a bug rather than a hiccup is that the scene never looked again: the actor
     * stayed unsimulated for as long as the film was open, and the only symptom was physics quietly
     * not existing — while the readout, having nothing to record, insisted the frame was merely not
     * computed yet. Whoever eventually noticed had to notice it from a log line about model formats.
     * Now the scene keeps the list and asks {@link #needsRebuild()} to say so.</p>
     */
    private static List<ModelForm> collectAwaited(Form form, List<ModelForm> out)
    {
        if (form instanceof ModelForm modelForm && ModelFormRenderer.getModel(modelForm) == null)
        {
            out.add(modelForm);
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                collectAwaited(child, out);
            }
        }

        return out;
    }

    /**
     * Finds every ragdoll-enabled model form in an actor's tree, with the path it lives at. Does
     * not descend into physics body forms, mirroring the piece collection: what is inside a body
     * belongs to the body.
     */
    private static List<Pair<ModelForm, String>> discoverRagdolls(Form form, String path, List<Pair<ModelForm, String>> out)
    {
        if (PhysicsForms.isBody(form))
        {
            /* A form welded into one falling lump is not a ragdoll, whatever its bones say — and
             * what is nested inside it belongs to that lump too. */
            return out;
        }

        if (form instanceof ModelForm modelForm && FormRagdolls.isEnabled(modelForm))
        {
            out.add(new Pair<>(modelForm, path));
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                discoverRagdolls(child, StringUtils.combinePaths(path, String.valueOf(i)), out);
            }

            i += 1;
        }

        return out;
    }

    /**
     * Takes one form's bone slots out of the actor's piece list and returns them. A bone piece of
     * the form at {@code formPath} has the path {@code formPath/bone} with the bone as its label;
     * the form's own slot (path equal to the form's) is deliberately left behind — it is a shape,
     * not a bone, and stays a plain kinematic body.
     *
     * <p>Bones the author left out of the ragdoll are left behind for the same reason: they still
     * have a shape and still collide, they simply ride the animation instead of falling. That is the
     * case this exists for — a body that walks on while the head comes off.</p>
     *
     * <p>Unless they are welded ({@code welds}), in which case they are claimed as well: a bone
     * nailed to a falling one is part of that body, and a kinematic copy of it left standing here
     * would be a collider the character walked out from under.</p>
     */
    private static List<CollisionCollector.Piece> claimBonePieces(List<CollisionCollector.Piece> pieces, String formPath, FormRagdoll config, Map<String, String> welds)
    {
        List<CollisionCollector.Piece> claimed = new ArrayList<>(0);

        for (int i = pieces.size() - 1; i >= 0; i--)
        {
            CollisionCollector.Piece piece = pieces.get(i);

            if (RagdollWelds.isBonePiece(piece, formPath) && (config.isPart(piece.label()) || welds.containsKey(piece.label())))
            {
                claimed.add(pieces.remove(i));
            }
        }

        Collections.reverse(claimed);

        return claimed;
    }

    /**
     * Finds every form carrying the rigid body modifier in an actor's tree, however deep — a crate
     * in a hand, a helmet on a head — and gives each one a body. The path mirrors the matrix walk's
     * convention exactly, because it is the key the body's evaluated placement is read back by.
     *
     * <p>Nested bodies are still visited: a crate with a body, holding a lid with a body of its
     * own, is two bodies. What the outer one <em>collides</em> as stops at the inner one, which is
     * the collector's business, not this walk's.</p>
     */
    private void discoverBodies(Form form, String path, MatrixCache matrices, List<PhysicsBodyRig> out, String anchor)
    {
        if (PhysicsForms.isBody(form))
        {
            out.add(PhysicsBodyRig.build(this.world, form, path, matrices, this, anchor));
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                /* The same anchor rule as cloth: descending out of a model means everything below
                 * hangs on one of its bones, and that bone is what a ragdoll moves. */
                String childAnchor = form instanceof ModelForm
                    ? StringUtils.combinePaths(path, part.bone.get())
                    : anchor;

                this.discoverBodies(child, StringUtils.combinePaths(path, String.valueOf(i)), matrices, out, childAnchor);
            }

            /* Outside the null check, mirroring the walk: a partless slot still takes an index. */
            i += 1;
        }
    }

    /**
     * Finds every cloth form in an actor's tree and gives each one a soft body. The same walk and
     * the same path convention as the rigid bodies, because the path is how the sheet's animated
     * frame is read back per tick.
     */
    private int discoverCloths(Form form, String path, MatrixCache matrices, Matrix4f actorWorld, List<ClothRig> out, int group, String anchor)
    {
        if (form instanceof ClothForm cloth)
        {
            ClothRig rig = ClothRig.build(this.world, cloth, path, matrices, actorWorld, this, group, anchor);

            if (rig != null)
            {
                out.add(rig);
            }

            /* Taken whether or not the sheet was built, and whether or not it asked for
             * stand-ins: an id spent is cheaper than an id reused by mistake. */
            group += 1;
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                /* Descending out of a model means everything below hangs on one of its bones, and
                 * that bone is what a ragdoll moves. Anywhere else the anchor is inherited: a
                 * sheet two groups deep under an arm still hangs on the arm. The path is built the
                 * same way the pose walk names bones, which is what lets the delta be looked up by
                 * it. */
                String childAnchor = form instanceof ModelForm
                    ? StringUtils.combinePaths(path, part.bone.get())
                    : anchor;

                group = this.discoverCloths(child, StringUtils.combinePaths(path, String.valueOf(i)), matrices, actorWorld, out, group, childAnchor);
            }

            /* Outside the null check, mirroring the walk: a partless slot still takes an index. */
            i += 1;
        }

        return group;
    }

    /**
     * Finds every balloon form in an actor's tree and gives each one a pressurized soft body. The
     * cloth walk without the group counter — a ball asks for no stand-ins.
     */
    private void discoverBalloons(Form form, String path, MatrixCache matrices, Matrix4f actorWorld, List<BalloonRig> out, String anchor)
    {
        if (form instanceof BalloonForm balloon)
        {
            BalloonRig rig = BalloonRig.build(this.world, balloon, path, matrices, actorWorld, this, anchor);

            if (rig != null)
            {
                out.add(rig);
            }
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                /* The same anchor rule as cloth: descending out of a model means everything below
                 * hangs on one of its bones, and that bone is what a ragdoll moves. */
                String childAnchor = form instanceof ModelForm
                    ? StringUtils.combinePaths(path, part.bone.get())
                    : anchor;

                this.discoverBalloons(child, StringUtils.combinePaths(path, String.valueOf(i)), matrices, actorWorld, out, childAnchor);
            }

            /* Outside the null check, mirroring the walk: a partless slot still takes an index. */
            i += 1;
        }
    }

    /** Registers a body to be drawn by the debug overlay, with a channel of its own to be read from. */
    public void addDebugBody(SceneBody body)
    {
        body.setChannel(this.cache.addChannel());

        this.bodies.add(body);
    }

    /**
     * Records the rest of the film right now, however long it takes.
     *
     * <p>The button for authors who would rather wait once than watch the bar creep — Blender's
     * {@code Calculate to Frame}, without the frame. Everything else about the recording is
     * unchanged: this is the same loop the background catch-up runs, with the budget removed.</p>
     */
    public void computeAll()
    {
        /* An edit that has not been answered yet would otherwise be recorded straight over: the
         * button would fill the bar to the end, and the next tick would throw the lot away and
         * start again. Answered here instead, so "compute everything" computes the film as it is
         * now. */
        if (this.stale)
        {
            this.stale = false;

            this.rewind();
        }

        int end = this.recordingEnd(this.filmTick);

        this.borrowCast();

        try
        {
            while (this.cache.getComputed() <= end && this.cache.canWrite(this.timeline.getTick() + 1))
            {
                this.step();
            }
        }
        finally
        {
            this.returnCast(this.filmTick);
        }

        this.distribute(this.filmTick);
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

    public PhysicsWorld getWorld()
    {
        return this.world;
    }

    public PhysicsTimeline getTimeline()
    {
        return this.timeline;
    }

    public PhysicsCache getCache()
    {
        return this.cache;
    }

    public List<SceneBody> getBodies()
    {
        return this.bodies;
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

    /**
     * What the simulation is doing, for the readout over the viewport. Counted per drawn frame
     * rather than kept up to date as things change: there are a handful of bodies, and a number
     * that is worked out where it is read cannot go stale.
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

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                if (rig.isGhost())
                {
                    ghosts += 1;
                }

                rig.getScenePosition(this.probe);

                if (this.window != null && this.window.boxes() > 0 && !this.window.contains(this.probe.x, this.probe.y, this.probe.z))
                {
                    outside += 1;
                }
            }

            /* The soft forms go through the same outside-the-window check as the rigid bodies —
             * this very count is how "the balloon is falling where no world was collected" stops
             * being invisible from the viewport. */
            for (ClothRig cloth : rigs.cloths)
            {
                if (cloth.isLost())
                {
                    lost += 1;
                }
                else if (this.isOutside(cloth.getRecordedCenter(this.probe)))
                {
                    outside += 1;
                }
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                if (balloon.isLost())
                {
                    lost += 1;
                }
                else if (this.isOutside(balloon.getRecordedCenter(this.probe)))
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
            this.world.getBodyCount(),
            ghosts,
            outside,
            lost);
    }

    /**
     * Whether {@link #probe} — filled by the caller's getter, whose return value is passed in —
     * lies outside the world that was actually collected.
     */
    private boolean isOutside(boolean filled)
    {
        return filled && this.window != null && this.window.boxes() > 0
            && !this.window.contains(this.probe.x, this.probe.y, this.probe.z);
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

        /* Before the rewind, not after it: these knobs invalidate the recording themselves when
         * they move, and answering them second meant a whole tick was recorded under the new
         * gravity on top of a recording made under the old one, only to be thrown away by the
         * invalidation on the tick after. */
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
     * different fall from the first tick onwards, so nothing worked out under the old value is
     * worth keeping. Cheap to check and rare to change, which is why it lives on the tick rather
     * than needing the settings screen to tell anyone.</p>
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
     * <p>Priority is the cursor: while the recording has not reached the frame the author is
     * looking at, the whole catch-up budget goes there, because that is the one frame somebody is
     * waiting for. Once it has, the same loop keeps running ahead on a much smaller budget so that
     * playing forwards never catches up with the recording — that is our one departure from
     * Blender, which simply waits to be told to compute (§6). Both stop at the film's length: past
     * the end there is nothing to look at.</p>
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
        this.borrowCast();

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
            this.returnCast(cursor);
        }
    }

    /** Simulates the next tick of the film and writes it into the recording. */
    private void step()
    {
        int tick = this.timeline.step(this::poseTick);

        this.record(tick);
    }

    /**
     * Writes every channel's answer for a tick that has just been simulated, then declares the tick
     * whole. Nothing may read a half-written tick — a frame drawn with half the bodies on it and
     * half on the tick before would be a glitch nobody could explain.
     */
    private void record(int tick)
    {
        BodyInterface bodies = this.world.getBodies();

        for (SceneBody body : this.bodies)
        {
            body.record(bodies, this.cache, tick);
        }

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.record(this.world, this, this.cache, tick);
            }

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                ragdoll.record(this.world, this, this.cache, tick);
            }

            for (ClothRig cloth : rigs.cloths)
            {
                cloth.record(this.world, this, this.cache, tick);
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                balloon.record(this.world, this, this.cache, tick);
            }
        }

        this.cache.commit(tick);
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

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.readCache(this.cache, tick, jumped);
            }

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                ragdoll.readCache(this.cache, tick, jumped);
            }

            for (ClothRig cloth : rigs.cloths)
            {
                cloth.readCache(this.cache, tick, jumped);
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                balloon.readCache(this.cache, tick, jumped);
            }
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
     * Stands the film on {@code tick}, for the step that is about to simulate it.
     *
     * <p>Called once per simulated step, which during a scrub is many times per frame — so the
     * order matters: the whole cast is placed first, then each actor's pose is walked. Cheap it is
     * not, and it is the price of the viewport agreeing with the exported video.</p>
     */
    private void poseTick(int tick)
    {
        this.applyCast(tick);

        for (EntityRigs rigs : this.rigs)
        {
            this.updateRigs(rigs, tick, false);
        }

        /* After the drives, deliberately: a drive writes a body's velocity outright, so a push
         * applied before it would be erased in the same tick it was given. Applied after, the push
         * lands on top and the next tick's drive mixes it away by the handle's proportion — which
         * is the muscles resisting the blast, and exactly what partial authority means. */
        this.applyClips(tick);
    }

    /**
     * Fires the physics action clips that sit on {@code tick} — the Э5 pushes and tears. Runs
     * inside the recording, once per simulated tick, which is what keeps them deterministic: a
     * re-recording replays the same clip on the same tick and the film comes out the same.
     */
    private void applyClips(int tick)
    {
        for (CastMember member : this.cast)
        {
            if (member.replay == null)
            {
                continue;
            }

            int local = member.replay.getTick(tick);

            for (Clip clip : member.replay.actions.getClips(local))
            {
                if (!fires(clip, local))
                {
                    continue;
                }

                if (clip instanceof ImpulseActionClip impulse)
                {
                    this.applyImpulse(impulse);
                }
                else if (clip instanceof TearActionClip tear)
                {
                    this.applyTear(member, tear);
                }
            }
        }
    }

    /**
     * Whether an action clip goes off on this tick — the same rule {@code ActionClip} applies for
     * the server and client passes, repeated here because physics reads the clip directly: at its
     * first tick once, or every {@code frequency} ticks of its length when one is set.
     */
    private static boolean fires(Clip clip, int tick)
    {
        if (!(clip instanceof ActionClip action) || !clip.enabled.get())
        {
            return false;
        }

        int relative = tick - clip.tick.get();

        if (relative < 0)
        {
            /* The clip has not started. BBS's own passes cannot reach this — they only ever ask a
             * clip that covers the tick — but the list this reads also hands back "global" clips
             * whatever the tick, and a repeating clip's modulo says yes to a negative multiple just
             * as readily as to a positive one. A push before its own frame is not a thing. */
            return false;
        }

        int frequency = action.frequency.get();

        return frequency == 0 ? relative == 0 : relative % frequency == 0;
    }

    /**
     * One firing of an impulse clip: the push is worked out once and offered to everything
     * simulated in the scene — every actor's bodies, not only the clip's own. An explosion has no
     * respect for whose timeline it was authored on.
     */
    private void applyImpulse(ImpulseActionClip clip)
    {
        Point point = clip.point.get();
        Point direction = clip.direction.get();

        SceneImpulse push = SceneImpulse.of(
            (float) (point.x - this.originX),
            (float) (point.y - this.originY),
            (float) (point.z - this.originZ),
            clip.radius.get(),
            clip.strength.get(),
            clip.radial.get() ? null : new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));

        if (push == null)
        {
            return;
        }

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.impulse(this.world, push);
            }

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                ragdoll.impulse(this.world, push);
            }

            for (ClothRig cloth : rigs.cloths)
            {
                cloth.impulse(this.world, push);
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                balloon.impulse(this.world, push);
            }
        }
    }

    /**
     * One firing of a tear clip: the named bone of this clip's own actor comes off, with the kick
     * the author gave it. The actor's ragdolls are asked in order; the first that owns the bone
     * answers.
     */
    private void applyTear(CastMember member, TearActionClip clip)
    {
        String bone = clip.bone.get().trim();

        if (bone.isEmpty())
        {
            return;
        }

        Point direction = clip.direction.get();
        Vector3f kick = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);

        if (kick.lengthSquared() > 1.0e-12F && kick.isFinite())
        {
            kick.normalize().mul(clip.strength.get());
        }
        else
        {
            kick.zero();
        }

        for (EntityRigs rigs : this.rigs)
        {
            if (rigs.entity != member.entity)
            {
                continue;
            }

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                if (ragdoll.tear(this.world, bone, kick.x, kick.y, kick.z))
                {
                    return;
                }
            }
        }

        /* Nobody owns that bone. Said out loud once per name, because the alternative is the clip
         * doing nothing at all with no explanation — and the name is typed by hand, so the usual
         * cause is a bone that is spelled differently, unmarked in the collision tab, or ticked
         * out of the ragdoll. Exactly the kind of silence the impulse clip's point already cost a
         * live run over. */
        if (this.warnedTears.add(bone))
        {
            BBSPhysics.LOGGER.warn("A tear clip names the bone '{}', which is not a ragdoll part of that actor; nothing comes off. Check the spelling, that the bone is marked up in the Collision tab, and that it is ticked on in the ragdoll modifier.", bone);
        }
    }

    /**
     * Whether the region of the world this scene collected no longer matches what the settings ask
     * for, which is the one change a rewind cannot answer: the blocks are a body, and a different
     * set of blocks is a different set of bodies — a different shape of recording, since a channel
     * is fixed when the scene is sealed. So the scene is rebuilt from scratch instead, and the
     * caller is the one who can do that.
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

    /**
     * The film was edited, so this recording describes a film that no longer exists.
     *
     * <p>This used to be the expensive call in the addon: it restarted the world and re-simulated
     * up to the cursor on the spot, on every batch of edits, which is what made dragging a slider
     * feel like the scene was fighting back. Now it costs a flag and a timestamp. The recording is
     * thrown away on the next tick, the bar under the timeline turns grey, and the frames come back
     * as the background catch-up refills them.</p>
     */
    public void invalidate()
    {
        this.stale = true;
        this.editedAt = System.nanoTime();
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
        this.borrowCast();

        try
        {
            this.applyCast(0);

            for (EntityRigs rigs : this.rigs)
            {
                this.updateRigs(rigs, 0, true);
            }

            this.timeline.start();
            this.cache.clear();
            this.full = false;

            /* Same as at assembly: frame 0 is never posed by the stepping loop, so a clip sitting
             * on it fires here or not at all. And inside the borrow for the same reason as at
             * assembly — recording a tick reads the handle off the form, which only says tick 0's
             * value while the cast is standing on tick 0. */
            this.applyClips(0);
            this.record(0);
        }
        finally
        {
            this.returnCast(this.filmTick);
        }

        /* Everything in the world has just been put back to the opening frame. Whatever each body
         * was last drawn at is a place it never travelled from, so the next frame is a cut rather
         * than a step — without this it slides from the old pose to the new one over one frame,
         * every time an edit lands with the recording already caught up to the cursor. */
        this.teleport = true;
    }

    /**
     * Evaluates one actor's pose at {@code tick} and drives everything hanging off it. One walk
     * per actor per tick: the bones and every nested body read the same {@code MatrixCache}, and
     * the walk fills each physics body's parent frame through its renderer on the way.
     */
    private void updateRigs(EntityRigs rigs, int tick, boolean reset)
    {
        Form root = rigs.entity.getForm();

        if (root == null)
        {
            return;
        }

        try
        {
            MatrixCache matrices = evaluatePose(rigs.entity, root);
            Matrix4f actorWorld = this.actorWorld(rigs.entity);

            if (rigs.bones != null)
            {
                rigs.bones.update(this.world, this, matrices, actorWorld, reset);
            }

            /* Emptied rather than left to age: a ragdoll taken back by the animation stops
             * publishing, and a stale delta would keep a cape hanging off a fall that is over. */
            rigs.boneDeltas.clear();

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                ragdoll.update(this.world, this, matrices, actorWorld, reset,
                    rigs.cloths.isEmpty() && rigs.balloons.isEmpty() && rigs.bodyRigs.isEmpty() ? null : rigs.boneDeltas);
            }

            /* After the ragdolls, like everything pinned to them: a crate on a falling arm is
             * driven from this tick's fall rather than the one before it. */
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.update(this.world, this, matrices, actorWorld, reset, rigs.boneDeltas);
            }

            /* After the ragdolls, so a sheet pinned to a falling bone is placed from this tick's
             * fall rather than the one before it. */
            for (ClothRig cloth : rigs.cloths)
            {
                cloth.update(this.world, this, matrices, actorWorld, reset, rigs.boneDeltas);
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                balloon.update(this.world, this, matrices, actorWorld, reset, rigs.boneDeltas);
            }

            rigs.broken = false;
        }
        catch (Throwable e)
        {
            if (!rigs.broken)
            {
                rigs.broken = true;

                BBSPhysics.LOGGER.warn("An actor's pose could not be evaluated for physics; its bodies hold still until it recovers.", e);
            }
        }
    }

    /**
     * One actor's pose as it stands: the same {@code collectMatrices} walk the anchors and gizmos
     * use, over an actor {@link #applyCast} has already stood on the tick being simulated.
     *
     * <p>That pinning is not optional. BBS writes a form's keyframed values when it <em>draws</em>,
     * at the tick plus however far into the tick the frame happens to fall, so a simulation that
     * simply read them would be sampling the animation at a moment decided by the frame rate — the
     * authority handle, the form's transform, the pose, all of it — and the same film would come
     * out differently on a faster machine. The next frame writes them again for drawing, so nothing
     * is taken away from the render path.</p>
     *
     * <p>Flagged as the simulation's own walk while it runs: the renderer substitutes a ragdoll's
     * simulated pose into this very walk for everyone else — anchors, gizmos — but the simulation
     * must read pure animation here, because this pose is the target the muscles pull towards.</p>
     */
    private static MatrixCache evaluatePose(IEntity entity, Form root)
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
     * The same thing at scene-build time, where there is no rig yet to remember that this actor is
     * broken — a model that has not loaded reports the failure once and simply gets no bodies. It
     * gets them when the cast is next rebuilt, which is also what happens the moment the editor
     * touches a form.
     */
    private MatrixCache evaluate(IEntity entity, Form root)
    {
        try
        {
            return evaluatePose(entity, root);
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.warn("An actor's pose could not be evaluated while building the scene; it gets no physics until the cast is rebuilt.", e);

            return null;
        }
    }

    /**
     * Where the actor stands in the world — the frame BBS's own render composes its form matrices
     * on top of, resolved the same way so that physics and drawing agree to the last bit.
     *
     * <p>Two things about it are easy to get wrong and both were. The transition is <b>1</b>, not
     * 0: the entity carries the tick it came from alongside the tick it is on, and the tick it is
     * on is the one just applied — asking for 0 hands back the previous tick, and every bone in
     * the scene trails the character by a frame. And the anchor is resolved rather than ignored,
     * so an actor riding another actor's bone is simulated where it is drawn instead of where it
     * would stand if it were riding nothing.</p>
     */
    private Matrix4f actorWorld(IEntity entity)
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
     * Warms up the animator of every model form in the tree before the matrix walk runs. The walk
     * assumes the render path has already done this — on a freshly built cast it has not, and a
     * model form with body parts trips over the gap.
     */
    private static void ensureAnimators(Form form)
    {
        if (FormUtilsClient.getRenderer(form) instanceof ModelFormRenderer model)
        {
            model.ensureAnimator(0F);
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                ensureAnimators(child);
            }
        }
    }

    /**
     * Centres the scene on its first actor. Films are authored around their cast, so this keeps
     * the numbers small where the action is; a film with no actors yet falls back to the world
     * origin, which is as good a guess as any.
     */
    private void pickOrigin(BaseFilmController controller, List<Integer> order)
    {
        for (int index : order)
        {
            IEntity entity = controller.getEntities().get(index);

            if (entity != null)
            {
                this.originX = Math.floor(entity.getX());
                this.originY = Math.floor(entity.getY());
                this.originZ = Math.floor(entity.getZ());

                return;
            }
        }
    }

    /** The ground the scene stands on. Everything else comes from the film's own forms. */
    private void buildGround()
    {
        BodyInterface bodies = this.world.getBodies();

        /* Where the world's collision must exist: around the origin, and around every actor whose
         * tree carries any physics — the cast is standing on tick 0 right now, so these are the
         * film's opening positions. One area was not enough: a balloon placed ninety blocks from
         * the first actor fell through a world that had only ever been collected around that first
         * actor, and nothing on screen said why. */
        List<double[]> centers = new ArrayList<>(1);

        centers.add(new double[] {this.originX, this.originY, this.originZ});

        for (CastMember member : this.cast)
        {
            Form root = member.entity.getForm();

            if (root != null && PhysicsForms.isSimulatedTree(root))
            {
                centers.add(new double[] {member.entity.getX(), member.entity.getY(), member.entity.getZ()});
            }
        }

        /* The blocks the film is actually shot among. Not drawn as debug boxes — there are
         * thousands of them and they are already visible as, well, the world. */
        this.window = WorldCollider.build(this.world, MinecraftClient.getInstance().world, this.originX, this.originY, this.originZ, centers);

        if (this.window.boxes() == 0)
        {
            /* No world to stand on — a scene built before the client has one, or a spot with
             * nothing solid nearby. A slab under the origin keeps bodies from falling forever,
             * which would look exactly like physics being broken. */
            BoxShape floorShape = new BoxShape(16F, 0.5F, 16F);
            BodyCreationSettings floor = new BodyCreationSettings(floorShape, new RVec3(0D, -0.5D, 0D), Quat.sIdentity(), EMotionType.Static, PhysicsLayers.STATIC);

            int floorId = bodies.createAndAddBody(floor, EActivation.DontActivate);

            this.bodies.add(new SceneBody(floorId, 16F, 0.5F, 16F, 0.35F, 0.35F, 0.4F));
        }
    }

    @Override
    public void close()
    {
        /* Hand the forms back to their keyframes. A physics body draws itself from the simulation
         * for exactly as long as a scene claims it, and a form left holding the last transform of
         * a world that no longer exists would sit frozen wherever the simulation happened to stop
         * — with nothing left to move it again. */
        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.release();
            }

            for (ActorRagdoll ragdoll : rigs.ragdolls)
            {
                ragdoll.release();
            }

            for (ClothRig cloth : rigs.cloths)
            {
                cloth.release();
            }

            for (BalloonRig balloon : rigs.balloons)
            {
                balloon.release();
            }
        }

        this.bodies.clear();
        this.rigs.clear();
        this.cast.clear();
        this.world.close();
    }
}
