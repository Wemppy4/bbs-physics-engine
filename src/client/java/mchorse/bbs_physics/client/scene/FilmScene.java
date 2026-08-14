package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsTimeline;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The physics of one film: a single Jolt world, a timeline that keeps it honest under scrubbing,
 * and the bodies in it — the world's blocks, every actor's bones, and every physics body form
 * anywhere in the cast's form trees.
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
    private final PhysicsWorld world;
    private final PhysicsTimeline timeline;

    /** The film's cast, as the anchor resolution needs it — an anchor points at another actor. */
    private final IntObjectMap<IEntity> entities;

    /** The world-space block this scene's physics is centred on — see the class note. */
    private double originX;
    private double originY;
    private double originZ;

    private final List<SceneBody> bodies = new ArrayList<>();
    private final List<EntityRigs> rigs = new ArrayList<>();

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

        /**
         * Whether this actor's last evaluation failed, so the failure is reported once instead of
         * sixty times a second. The usual cause is a model that has not loaded yet — BBS's matrix
         * walk trips over body parts when the animator is not there — and it clears itself once
         * the model arrives.
         */
        private boolean broken;

        private EntityRigs(IEntity entity, Replay replay, ActorRig bones, List<PhysicsBodyRig> bodyRigs)
        {
            this.entity = entity;
            this.replay = replay;
            this.bones = bones;
            this.bodyRigs = bodyRigs;
        }
    }

    public FilmScene(BaseFilmController controller)
    {
        this.world = new PhysicsWorld();
        this.timeline = new PhysicsTimeline(this.world);
        this.entities = controller.getEntities();

        List<Integer> order = castOrder(controller);

        this.pickOrigin(controller, order);
        this.buildGround();
        this.buildRigs(controller, order);

        this.world.optimize();

        /* Everything the scene will ever contain has to exist before the first snapshot: Jolt
         * refuses to restore a state whose set of bodies no longer matches, so a body added later
         * would break every rewind past this point. */
        this.timeline.start();
        this.sampleBodies(true);
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
     * Builds the simulated side of every actor: bone bodies for its model, a rigid body for every
     * physics body form anywhere in its form tree. Actors whose model has not loaded yet report no
     * bones and are skipped — they get their rig when the film's cast is next rebuilt, which is
     * also what happens whenever the editor changes a form.
     */
    private void buildRigs(BaseFilmController controller, List<Integer> order)
    {
        List<Replay> replays = controller.film == null ? null : controller.film.replays.getList();

        for (int index : order)
        {
            IEntity entity = controller.getEntities().get(index);
            Form root = entity == null ? null : entity.getForm();

            if (root == null)
            {
                continue;
            }

            ActorRig bones = ActorRig.build(this.world, entity, this);
            List<PhysicsBodyRig> bodyRigs = new ArrayList<>(0);

            this.discoverBodies(entity, root, "", bodyRigs);

            if (bones == null && bodyRigs.isEmpty())
            {
                continue;
            }

            Replay replay = replays == null ? null : CollectionUtils.getSafe(replays, index);
            EntityRigs rigs = new EntityRigs(entity, replay, bones, bodyRigs);

            this.rigs.add(rigs);

            /* Placed outright rather than steered: bodies are created at the origin, and letting
             * them travel to their real spots would sweep them through the scene on the first
             * tick. The read right after hands the placement to the renderer too.
             *
             * At the film's current tick, not at zero, even though the timeline is about to call
             * this world tick zero: the actors already stand where the cursor puts them, so zero
             * would pose them against a position they are not in — and it would leave the form's
             * keyframed values on the film's opening frame for however long it takes the next
             * render to write them back. The world catches up to the cursor on the first tick
             * either way; what matters here is not flashing the wrong pose while it does. */
            this.updateRigs(rigs, controller.getTick(), true);

            for (PhysicsBodyRig rig : bodyRigs)
            {
                rig.read(this.world, this, true);
            }
        }
    }

    /**
     * Finds every physics body form in an actor's form tree, however deep — a crate in a hand, a
     * helmet on a head — and gives each one a body. The path mirrors the matrix walk's convention
     * exactly, because it is the key the body's evaluated placement is read back by.
     */
    private void discoverBodies(IEntity entity, Form form, String path, List<PhysicsBodyRig> out)
    {
        if (form instanceof PhysicsBodyForm body)
        {
            out.add(PhysicsBodyRig.build(this.world, entity, body, path, this));
        }

        int i = 0;

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                this.discoverBodies(entity, child, StringUtils.combinePaths(path, String.valueOf(i)), out);
            }

            /* Outside the null check, mirroring the walk: a partless slot still takes an index. */
            i += 1;
        }
    }

    /** Registers a body to be drawn by the debug overlay. */
    public void addDebugBody(SceneBody body)
    {
        this.bodies.add(body);
    }

    public PhysicsWorld getWorld()
    {
        return this.world;
    }

    public PhysicsTimeline getTimeline()
    {
        return this.timeline;
    }

    public List<SceneBody> getBodies()
    {
        return this.bodies;
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
     * The film moved to {@code tick}. Anything but a repeat of the current tick makes the timeline
     * bring the world there, forwards by stepping or backwards through a checkpoint.
     *
     * <p>The pose is written into the world <em>between</em> the rewind and the re-simulation, and
     * that placement is the whole reason the timeline's two halves are separate calls. Writing
     * before the rewind fills a world that a restored checkpoint is about to overwrite; writing
     * after leaves every re-simulated step running against yesterday's pose.</p>
     */
    public void tick(int tick)
    {
        if (tick < 0)
        {
            tick = 0;
        }

        int before = this.timeline.getTick();

        if (tick == before)
        {
            /* The world stood still — a paused editor asking for the same tick again. The bodies
             * have to be pinned to it, because the frame's transition goes on sweeping 0 to 1
             * regardless, and interpolating a body between two ticks that are no longer being
             * advanced leaves it visibly shaking in a scene that is supposed to be frozen. */
            this.freezeBodies();

            return;
        }

        /* One step forward is the only case where the animation can be handed to Jolt as motion:
         * a steered body is given a target one tick away, and one tick is what is about to be
         * simulated. Everything else — a scrub, a jump, a catch-up — sets the animated bodies
         * down at the destination pose instead, because a steer integrated over twenty steps
         * throws them twenty times as far. */
        boolean place = tick != before + 1;

        this.timeline.rewind(tick);

        for (EntityRigs rigs : this.rigs)
        {
            this.updateRigs(rigs, tick, place);
        }

        this.timeline.advance(tick);

        this.sampleBodies(place);

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.read(this.world, this, place);
            }
        }
    }

    /**
     * Evaluates one actor's pose at {@code tick} and drives everything hanging off it. One walk
     * per actor per tick: the bones and every nested body read the same {@code MatrixCache}, and
     * the walk fills each physics body's parent frame through its renderer on the way.
     */
    private void updateRigs(EntityRigs rigs, int tick, boolean place)
    {
        Form root = rigs.entity.getForm();

        if (root == null)
        {
            return;
        }

        try
        {
            if (rigs.replay != null)
            {
                /* Pin the form's keyframed values to this whole tick. BBS writes them when it
                 * renders, at tick plus however far into the tick the frame happens to fall, so
                 * left alone physics would read the authority handle — and the form's transform,
                 * and the pose — at a value that depends on the frame rate. The next frame writes
                 * them again for drawing, so nothing is taken away from the render path. */
                rigs.replay.properties.applyProperties(root, rigs.replay.getTick(tick));
            }

            ensureAnimators(root);

            MatrixCache matrices = FormUtilsClient.getRenderer(root).collectMatrices(rigs.entity, 1F);
            Matrix4f actorWorld = this.actorWorld(rigs.entity);

            if (rigs.bones != null)
            {
                rigs.bones.update(this.world, this, matrices, actorWorld, place);
            }

            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.update(this.world, this, matrices, actorWorld, place);
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

    private void freezeBodies()
    {
        for (SceneBody body : this.bodies)
        {
            body.freeze();
        }

        for (EntityRigs rigs : this.rigs)
        {
            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.freeze(this.world);
            }
        }
    }

    /**
     * Reads every body's transform out of Jolt into the render-side snapshot.
     *
     * @param teleport whether the move was a jump rather than a single step. A seek that crossed
     *                 many ticks (or a restore) has no meaningful "previous" position, and
     *                 interpolating from the old one would draw the body sliding across the scene
     */
    private void sampleBodies(boolean teleport)
    {
        BodyInterface bodyInterface = this.world.getBodies();

        for (SceneBody body : this.bodies)
        {
            body.sample(bodyInterface, teleport);
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

        /* The blocks the film is actually shot among. Not drawn as debug boxes — there are
         * thousands of them and they are already visible as, well, the world. */
        int ground = WorldCollider.build(this.world, MinecraftClient.getInstance().world, this.originX, this.originY, this.originZ);

        if (ground == 0)
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
        }

        this.bodies.clear();
        this.rigs.clear();
        this.world.close();
    }
}
