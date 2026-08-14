package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsTimeline;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;

import java.util.ArrayList;
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
 */
public class FilmScene implements AutoCloseable
{
    private final PhysicsWorld world;
    private final PhysicsTimeline timeline;

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
        private final ActorRig bones;
        private final List<PhysicsBodyRig> bodyRigs;

        /**
         * Whether this actor's last evaluation failed, so the failure is reported once instead of
         * sixty times a second. The usual cause is a model that has not loaded yet — BBS's matrix
         * walk trips over body parts when the animator is not there — and it clears itself once
         * the model arrives.
         */
        private boolean broken;

        private EntityRigs(IEntity entity, ActorRig bones, List<PhysicsBodyRig> bodyRigs)
        {
            this.entity = entity;
            this.bones = bones;
            this.bodyRigs = bodyRigs;
        }
    }

    public FilmScene(BaseFilmController controller)
    {
        this.world = new PhysicsWorld();
        this.timeline = new PhysicsTimeline(this.world);

        this.pickOrigin(controller);
        this.buildGround();
        this.buildRigs(controller);

        this.world.optimize();

        /* Everything the scene will ever contain has to exist before the first snapshot: Jolt
         * refuses to restore a state whose set of bodies no longer matches, so a body added later
         * would break every rewind past this point. */
        this.timeline.start();
        this.sampleBodies(true);
    }

    /**
     * Builds the simulated side of every actor: bone bodies for its model, a rigid body for every
     * physics body form anywhere in its form tree. Actors whose model has not loaded yet report no
     * bones and are skipped — they get their rig when the film's cast is next rebuilt, which is
     * also what happens whenever the editor changes a form.
     */
    private void buildRigs(BaseFilmController controller)
    {
        for (IEntity entity : controller.getEntities().values())
        {
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

            EntityRigs rigs = new EntityRigs(entity, bones, bodyRigs);

            this.rigs.add(rigs);

            /* Placed outright rather than steered: bodies are created at the origin, and letting
             * them travel to their real spots would sweep them through the scene on the first
             * tick. The read right after hands the placement to the renderer too. */
            this.updateRigs(rigs, true);

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
     */
    public void tick(int tick)
    {
        int before = this.timeline.getTick();

        /* Animation first: bones and keyframe-driven bodies have to stand where this tick's pose
         * puts them before the step runs — a kinematic body moved after the step pushes nothing. */
        for (EntityRigs rigs : this.rigs)
        {
            this.updateRigs(rigs, false);
        }

        this.timeline.seek(tick);

        if (this.timeline.getTick() != before)
        {
            boolean teleport = this.timeline.getLastSeekSteps() != 1;

            this.sampleBodies(teleport);

            for (EntityRigs rigs : this.rigs)
            {
                for (PhysicsBodyRig rig : rigs.bodyRigs)
                {
                    rig.read(this.world, this, teleport);
                }
            }
        }
        else
        {
            /* The world stood still — a paused editor asking for the same tick again. The bodies
             * have to be pinned to it, because the frame's transition goes on sweeping 0 to 1
             * regardless, and interpolating a body between two ticks that are no longer being
             * advanced leaves it visibly shaking in a scene that is supposed to be frozen. */
            this.freezeBodies();
        }
    }

    /**
     * Evaluates one actor's pose and drives everything hanging off it. One walk per actor per
     * tick: the bones and every nested body read the same {@code MatrixCache}, and the walk fills
     * each physics body's parent frame through its renderer on the way.
     */
    private void updateRigs(EntityRigs rigs, boolean teleport)
    {
        Form root = rigs.entity.getForm();

        if (root == null)
        {
            return;
        }

        try
        {
            ensureAnimators(root);

            MatrixCache matrices = FormUtilsClient.getRenderer(root).collectMatrices(rigs.entity, 0F);

            /* Zero camera: the actor's placement in the world, not on the screen. */
            Matrix4f actorWorld = BaseFilmController.getMatrixForRenderWithRotation(rigs.entity, 0D, 0D, 0D, 0F);

            if (rigs.bones != null)
            {
                rigs.bones.update(this.world, this, matrices, actorWorld, teleport);
            }

            for (PhysicsBodyRig rig : rigs.bodyRigs)
            {
                rig.update(this.world, this, matrices, actorWorld, teleport);
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
                rig.freeze();
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
    private void pickOrigin(BaseFilmController controller)
    {
        for (IEntity entity : controller.getEntities().values())
        {
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
        this.bodies.clear();
        this.rigs.clear();
        this.world.close();
    }
}
