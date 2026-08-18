package mchorse.bbs_physics.client.scene;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyLockWrite;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.balloon.BalloonForm;
import mchorse.bbs_physics.chain.ChainForm;
import mchorse.bbs_physics.chain.FormChain;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.client.collision.CollisionCollector;
import mchorse.bbs_physics.client.ragdoll.RagdollWelds;
import mchorse.bbs_physics.cloth.ClothForm;
import mchorse.bbs_physics.engine.PhysicsLayers;
import mchorse.bbs_physics.engine.PhysicsWorld;
import mchorse.bbs_physics.forms.FormTreeWalk;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a film's cast into bodies: the one pass that reads what every form asks for and builds it.
 *
 * <p><b>Order is the whole of this class.</b> An actor's collision markup is one collection that
 * several owners take from, and each of them has to take its share before the next one runs:</p>
 *
 * <ol>
 * <li>The <b>ragdolls</b> claim their bone slots first, welded bones included. A welded bone has no
 * body of its own, so leaving it behind would put a second collider where its owner's shape already
 * is, standing on the animation while the owner falls away from it.</li>
 * <li>The <b>chain modifiers</b> claim theirs next, for the same reason: a bone that is going to
 * hang must not also become a kinematic body standing on the animation.</li>
 * <li>Everything left over becomes the actor's <b>kinematic bones</b> — the bones the animation
 * kept, which is what a falling part with no falling parent gets jointed to ("the ragdoll is only
 * on the head": the head hangs off the walking torso instead of dropping free).</li>
 * <li>Only then are the <b>ragdolls and strands themselves</b> built, because their joints need
 * those kinematic bodies to already exist.</li>
 * </ol>
 *
 * <p>The form tree is walked three times and no more: once for the ragdoll-enabled models, once for
 * the chain modifiers, once for everything that becomes a rig of its own. Each used to be its own
 * hand-written recursion carrying its own copy of the path and anchor conventions — see
 * {@link FormTreeWalk} for what those conventions are and why a private copy of them is a liability.
 * </p>
 */
final class SceneBuilder
{
    private final FilmScene scene;
    private final PhysicsWorld world;

    /**
     * Distinct per actor — and per sheet of cloth and per strand, which draw from the same counter
     * for the same reason: bodies of one group consult its filter, bodies of different groups never
     * do and collide normally. Two groups sharing an id would consult each other's filter by
     * subgroup index — nonsense pairs, and one character's arm excused from another's.
     */
    private int group;

    SceneBuilder(FilmScene scene, PhysicsWorld world)
    {
        this.scene = scene;
        this.world = world;
    }

    /**
     * The ground the scene stands on. Everything else comes from the film's own forms.
     *
     * <p>Collected around the origin <em>and</em> around every actor whose tree carries any physics
     * — the cast is standing on tick 0 right now, so these are the film's opening positions. One
     * area was not enough: a balloon placed ninety blocks from the first actor fell through a world
     * that had only ever been collected around that first actor, and nothing on screen said why.</p>
     */
    WorldCollider.Window buildGround(SceneCast cast)
    {
        List<double[]> centers = new ArrayList<>(1);

        centers.add(new double[] {this.scene.getOriginX(), this.scene.getOriginY(), this.scene.getOriginZ()});

        for (SceneCast.Member member : cast)
        {
            Form root = member.entity.getForm();

            if (root != null && PhysicsForms.isSimulatedTree(root))
            {
                centers.add(new double[] {member.entity.getX(), member.entity.getY(), member.entity.getZ()});
            }
        }

        /* The blocks the film is actually shot among. Not drawn as debug boxes — there are thousands
         * of them and they are already visible as, well, the world. */
        WorldCollider.Window window = WorldCollider.build(this.world, MinecraftClient.getInstance().world,
            this.scene.getOriginX(), this.scene.getOriginY(), this.scene.getOriginZ(), centers);

        if (window.boxes() == 0)
        {
            /* No world to stand on — a scene built before the client has one, or a spot with nothing
             * solid nearby. A slab under the origin keeps bodies from falling forever, which would
             * look exactly like physics being broken. */
            BodyCreationSettings floor = new BodyCreationSettings(
                new BoxShape(16F, 0.5F, 16F), new RVec3(0D, -0.5D, 0D), Quat.sIdentity(),
                EMotionType.Static, PhysicsLayers.STATIC);

            int floorId = this.world.getBodies().createAndAddBody(floor, EActivation.DontActivate);

            this.scene.addFloor(new SceneBody(floorId, 16F, 0.5F, 16F, 0.35F, 0.35F, 0.4F));
        }

        return window;
    }

    /**
     * Builds the simulated side of every actor. An actor with nothing marked up and no modifier is
     * skipped entirely — that is the default state of a form, not a failure.
     */
    List<SceneActor> buildActors(SceneCast cast)
    {
        List<SceneActor> actors = new ArrayList<>();

        for (SceneCast.Member member : cast)
        {
            SceneActor actor = this.buildActor(member.entity);

            if (actor != null)
            {
                actors.add(actor);
            }
        }

        this.linkChainTargets(actors);

        return actors;
    }

    private SceneActor buildActor(IEntity entity)
    {
        Form root = entity.getForm();

        if (root == null)
        {
            return null;
        }

        /* Before anything is measured or evaluated, and that order is the whole point: a model BBS
         * has not finished loading measures to nothing and is also the likeliest reason the pose
         * evaluation below fails outright. Noted first, the scene knows to build itself again when
         * the model lands; noted after, an actor that failed to evaluate would stay unsimulated for
         * as long as the film is open, with nothing on screen saying why. */
        this.awaitModels(root);

        /* The pose the colliders are measured against. Evaluated before anything is built, because a
         * shape's size has to come out of the frame it will live in — a model at 2× collides at 2× —
         * and because a body welded out of several bones needs to know where those bones are
         * relative to it. */
        MatrixCache matrices = this.evaluate(entity, root);

        if (matrices == null)
        {
            return null;
        }

        Matrix4f actorWorld = this.scene.actorWorld(entity);
        List<SceneRig> rigs = new ArrayList<>();

        /* One collection of the actor's markup, divided between owners below. */
        List<CollisionCollector.Piece> pieces = CollisionCollector.collectActor(root, matrices);
        List<ChainModel> chainModels = chainModels(root);

        /* Sized before the markup is divided, because that division does not change how many bodies
         * there will be — only which half builds them. The chain modifier's bodies are counted on
         * top: they are not markup at all, and Jolt indexes this table unchecked, so a subgroup past
         * its size is memory nobody owns. Two per claimed bone — the segment, and at worst a pin of
         * its own for the bone above it. */
        ActorCollisionGroup actorGroup = new ActorCollisionGroup(this.group, pieces.size() + chainBudget(chainModels) * 2);

        this.group += 1;

        /* The chains take their bones before the ragdolls do, and the order is load bearing: a
         * hair bone the author marked up (so the strand collides, Р15) and ticked into the chain
         * modifier is, to the ragdoll, just another marked bone that is not excluded — claimed by
         * the ragdoll first, it became a falling part AND a strand, two bodies on one bone with
         * two owners writing its pose. Whoever the author named the strand's owner wins. */
        List<ClaimedChain> chains = claimChains(chainModels, pieces);
        List<ClaimedRagdoll> ragdolls = this.claimRagdolls(root, pieces);

        /* Whatever is left of the markup: the bones the animation keeps. */
        BoneRig bones = BoneRig.build(this.world, root, matrices, this.scene, pieces, actorGroup);

        if (bones != null)
        {
            rigs.add(bones);
        }

        for (ClaimedRagdoll claim : ragdolls)
        {
            /* The bones of this form the animation kept — what a part with no falling parent can be
             * attached to. Gathered from what is left after every claim, so a bone claimed by
             * another ragdoll of the same actor is never offered. */
            List<CollisionCollector.Piece> kinematic = new ArrayList<>(0);

            for (CollisionCollector.Piece piece : pieces)
            {
                if (RagdollWelds.isBonePiece(piece, claim.formPath()))
                {
                    kinematic.add(piece);
                }
            }

            RagdollRig ragdoll = RagdollRig.build(this.world, claim.form(), claim.formPath(), claim.claimed(),
                claim.welds(), kinematic, bones, matrices, actorWorld, this.scene, actorGroup);

            if (ragdoll == null)
            {
                /* Every claimed piece failed to become a body — a pose broken enough that no frame
                 * or shape came out of it. The bones are simply absent until the cast is next
                 * rebuilt; said out loud because absent collision is otherwise invisible. */
                BBSPhysics.LOGGER.warn("No ragdoll part of '{}' could be built; its bones have no bodies until the scene is rebuilt.", claim.form().getDisplayName());
            }
            else
            {
                rigs.add(ragdoll);
            }
        }

        this.buildFormRigs(root, matrices, actorWorld, rigs);

        /* After the kinematic bones, like the ragdolls: a strand hangs off the bone above it, and
         * that bone is usually one the animation kept. */
        for (ClaimedChain claim : chains)
        {
            BoneChainRig chain = BoneChainRig.build(this.world, claim.form(), claim.formPath(), claim.claimed(),
                bones, matrices, actorWorld, this.scene, actorGroup, claim.anchor());

            if (chain != null)
            {
                rigs.add(chain);
            }
        }

        if (rigs.isEmpty())
        {
            return null;
        }

        /* Both halves of the actor exist now, which is the first moment anything knows every ragdoll
         * part and every kinematic bone at once. */
        actorGroup.seal();

        SceneActor actor = new SceneActor(entity, rigs, actorGroup, new RigUpdate(this.world, this.scene));

        /* Placed outright rather than steered: bodies are created at the origin, and letting them
         * travel to their real spots would sweep them through the scene on the first tick. Simulated
         * bodies too — a crate that is already released at the film's opening frame has only its
         * keyframes to say where it starts, and without this it would begin its fall from the
         * scene's origin instead, with the author's coordinates never read at all. */
        actor.drive(this.scene, true);

        return actor;
    }

    /**
     * Every form in the tree that becomes a rig of its own — a rigid body anywhere, a sheet, a ball,
     * a rope — in one walk. Models whose model has not loaded are noted on the way past.
     *
     * <p>Nested bodies are still visited: a crate with a body, holding a lid with a body of its own,
     * is two bodies. What the outer one <em>collides</em> as stops at the inner one, which is the
     * collector's business, not this walk's.</p>
     */
    private void buildFormRigs(Form root, MatrixCache matrices, Matrix4f actorWorld, List<SceneRig> rigs)
    {
        List<Found> bodies = new ArrayList<>(0);
        List<Found> cloths = new ArrayList<>(0);
        List<Found> balloons = new ArrayList<>(0);
        List<Found> chains = new ArrayList<>(0);

        FormTreeWalk.walk(root, (form, path, anchor) ->
        {
            if (PhysicsForms.isBody(form))
            {
                bodies.add(new Found(form, path, anchor));
            }
            else if (form instanceof ClothForm)
            {
                cloths.add(new Found(form, path, anchor));
            }
            else if (form instanceof BalloonForm)
            {
                balloons.add(new Found(form, path, anchor));
            }
            else if (form instanceof ChainForm)
            {
                chains.add(new Found(form, path, anchor));
            }

            return true;
        });

        /* Built kind by kind rather than as they are found, and that is not cosmetic: the order
         * bodies enter the world is the order Jolt resolves a pile in, so it is part of what a film
         * looks like. Keeping it by kind keeps that answer the same as it has always been.
         *
         * The kinds are also in the order the drive needs them: the soft forms and the ropes are all
         * things that can hang off a bone, so they follow the bodies, which follow the ragdolls. */
        for (Found found : bodies)
        {
            rigs.add(BodyRig.build(this.world, found.form(), found.path(), matrices, this.scene, found.anchor()));
        }

        for (Found found : cloths)
        {
            /* Each sheet takes an id of its own from the same counter, so its stand-ins are excused
             * from it alone — see ClothProxy. Taken whether or not the sheet was built, and whether
             * or not it asked for stand-ins: an id spent is cheaper than an id reused by mistake. */
            add(rigs, ClothRig.build(this.world, (ClothForm) found.form(), found.path(), matrices, actorWorld, this.scene, this.group++, found.anchor()));
        }

        for (Found found : balloons)
        {
            add(rigs, BalloonRig.build(this.world, (BalloonForm) found.form(), found.path(), matrices, actorWorld, this.scene, found.anchor()));
        }

        for (Found found : chains)
        {
            /* An id from the same counter too — a strand's neighbours are excused from each other
             * through its own filter, and a shared id would read another rig's table. */
            add(rigs, ChainRig.build(this.world, (ChainForm) found.form(), found.path(), matrices, actorWorld, this.scene, this.group++, found.anchor()));
        }
    }

    private static void add(List<SceneRig> rigs, SceneRig rig)
    {
        if (rig != null)
        {
            rigs.add(rig);
        }
    }

    /** A form the walk found, with the two things building it needs: where it is, and what it hangs on. */
    private record Found(Form form, String path, String anchor)
    {}

    /**
     * The second build pass for the ropes' bottom ends: a disabled tie from every strand to every
     * simulated root body in the scene, so a keyframe can grab any of them mid-film without the
     * world ever changing shape mid-recording. Runs after the whole cast is built, because a rope on
     * the first actor may be tied to a crate on the last.
     */
    private void linkChainTargets(List<SceneActor> actors)
    {
        Map<Integer, Body> candidates = new HashMap<>(0);

        for (SceneActor actor : actors)
        {
            for (SceneRig rig : actor.getRigs())
            {
                if (rig instanceof BodyRig body && body.getPath().isEmpty())
                {
                    Body reference = this.bodyRef(body.getBodyId());

                    if (reference != null)
                    {
                        candidates.put(body.getBodyId(), reference);
                    }
                }
            }
        }

        if (candidates.isEmpty())
        {
            return;
        }

        for (SceneActor actor : actors)
        {
            for (SceneRig rig : actor.getRigs())
            {
                if (rig instanceof ChainRig chain)
                {
                    chain.linkTargets(this.world, candidates);
                }
            }
        }
    }

    /**
     * The {@code Body} reference behind an id — constraint settings take a body, not an id. The
     * pointer stays valid for as long as the body exists, which is as long as this scene does; the
     * lock is only for the lookup, since everything here runs on one thread.
     */
    private Body bodyRef(int id)
    {
        BodyLockWrite lock = new BodyLockWrite(this.world.getSystem().getBodyLockInterface(), id);

        try
        {
            return lock.succeeded() ? lock.getBody() : null;
        }
        finally
        {
            lock.releaseLock();
        }
    }

    /**
     * Takes the bone slots of every ragdoll-enabled model in the tree out of the actor's piece list.
     *
     * <p>A bone piece of the form at {@code formPath} has the path {@code formPath/bone} with the
     * bone as its label; the form's own slot is deliberately left behind — it is a shape, not a
     * bone, and stays a plain kinematic body. Bones the author left out of the ragdoll are left
     * behind for the same reason: they still have a shape and still collide, they simply ride the
     * animation instead of falling. That is the case this exists for — a body that walks on while
     * the head comes off. Unless they are welded, in which case they are claimed as well: a bone
     * nailed to a falling one is part of that body.</p>
     */
    private List<ClaimedRagdoll> claimRagdolls(Form root, List<CollisionCollector.Piece> pieces)
    {
        List<ClaimedRagdoll> claims = new ArrayList<>(0);

        FormTreeWalk.walk(root, (form, path, anchor) ->
        {
            if (PhysicsForms.isBody(form))
            {
                /* A form welded into one falling lump is not a ragdoll, whatever its bones say — and
                 * what is nested inside it belongs to that lump too. */
                return false;
            }

            /* Whether a model can carry a ragdoll at all is checked before its pieces are claimed,
             * because a claim can no longer be undone once the kinematic rig is built. */
            if (!(form instanceof ModelForm model) || !FormRagdolls.isEnabled(model) || !RagdollRig.supports(model))
            {
                return true;
            }

            FormRagdoll config = FormRagdolls.get(model);
            Map<String, String> welds = RagdollWelds.resolve(config, pieces, path, model);
            List<CollisionCollector.Piece> claimed = claim(pieces, path,
                (piece) -> config.isPart(piece.label()) || welds.containsKey(piece.label()));

            if (!claimed.isEmpty())
            {
                claims.add(new ClaimedRagdoll(model, path, config, welds, claimed));
            }

            return true;
        });

        return claims;
    }

    /**
     * Notes every model in the tree whose model BBS has not finished loading.
     *
     * <p>BBS loads models on a thread of its own and hands back null for one that has not arrived,
     * which the scene has no way to work around: a form with no model has no bones, no geometry to
     * measure collision from and no groups to hand a pose back through, so <em>nothing</em> is built
     * for it. A film opened from cold assembles its scene in the same moment its models are
     * requested, so this is the common case and not the rare one — and what made it a bug rather
     * than a hiccup was that the scene never looked again.</p>
     */
    private void awaitModels(Form root)
    {
        FormTreeWalk.walk(root, (form, path, anchor) ->
        {
            if (form instanceof ModelForm model && ModelFormRenderer.getModel(model) == null)
            {
                this.scene.await(model);
            }

            return true;
        });
    }

    /** Every model form carrying the chain modifier, with where it lives and what it hangs on. */
    private static List<ChainModel> chainModels(Form root)
    {
        List<ChainModel> found = new ArrayList<>(0);

        FormTreeWalk.walk(root, (form, path, anchor) ->
        {
            if (form instanceof ModelForm model && FormChains.isEnabled(model))
            {
                found.add(new ChainModel(model, path, anchor));
            }

            return true;
        });

        return found;
    }

    /**
     * Takes the marked-up slots of the bones the chain modifiers claim out of the actor's piece
     * list — exactly what {@link #claimRagdolls} does, and for the same reason. A claimed bone that
     * has no markup produces no piece at all: the strand still hangs, it simply collides with
     * nothing until the author gives it a shape in the Collision tab.
     */
    private static List<ClaimedChain> claimChains(List<ChainModel> models, List<CollisionCollector.Piece> pieces)
    {
        List<ClaimedChain> claims = new ArrayList<>(0);

        for (ChainModel found : models)
        {
            FormChain config = FormChains.get(found.form());

            claims.add(new ClaimedChain(found.form(), found.path(), found.anchor(),
                claim(pieces, found.path(), (piece) -> config.claims(piece.label()))));
        }

        return claims;
    }

    /** Removes the bone pieces of the form at {@code formPath} that {@code wanted} accepts. */
    private static List<CollisionCollector.Piece> claim(List<CollisionCollector.Piece> pieces, String formPath, java.util.function.Predicate<CollisionCollector.Piece> wanted)
    {
        List<CollisionCollector.Piece> claimed = new ArrayList<>(0);

        for (int i = pieces.size() - 1; i >= 0; i--)
        {
            CollisionCollector.Piece piece = pieces.get(i);

            if (RagdollWelds.isBonePiece(piece, formPath) && wanted.test(piece))
            {
                claimed.add(pieces.remove(i));
            }
        }

        java.util.Collections.reverse(claimed);

        return claimed;
    }

    /** How many bones the chain modifiers claim — what the collision filter table is sized for. */
    private static int chainBudget(List<ChainModel> models)
    {
        int count = 0;

        for (ChainModel found : models)
        {
            count += FormChains.get(found.form()).bones().size();
        }

        return count;
    }

    /**
     * One actor's pose at build time, where there is no rig yet to remember that this actor is
     * broken — a model that has not loaded reports the failure once and simply gets no bodies. It
     * gets them when the cast is next rebuilt, which is also what happens the moment the editor
     * touches a form.
     */
    private MatrixCache evaluate(IEntity entity, Form root)
    {
        try
        {
            return FilmScene.evaluatePose(entity, root);
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.warn("An actor's pose could not be evaluated while building the scene; it gets no physics until the cast is rebuilt.", e);

            return null;
        }
    }

    /**
     * One ragdoll's claim on an actor's markup, held between the moment its pieces are taken and the
     * moment it is built — the gap in which the kinematic bones are created, so that a falling part
     * can be jointed to a bone the animation kept.
     */
    private record ClaimedRagdoll(ModelForm form, String formPath, FormRagdoll config, Map<String, String> welds, List<CollisionCollector.Piece> claimed)
    {}

    /** The same for a chain modifier: the markup of its bones, taken before the kinematic rig. */
    private record ClaimedChain(ModelForm form, String formPath, String anchor, List<CollisionCollector.Piece> claimed)
    {}

    /** A model carrying the chain modifier: where it lives, and the bone it itself hangs on. */
    private record ChainModel(ModelForm form, String path, String anchor)
    {}
}
