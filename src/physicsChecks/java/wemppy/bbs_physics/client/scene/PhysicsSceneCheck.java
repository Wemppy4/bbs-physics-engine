package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.chain.ChainForm;
import wemppy.bbs_physics.engine.FilmPhysics;
import wemppy.bbs_physics.engine.JoltEngine;
import wemppy.bbs_physics.engine.PhysicsWorld;
import wemppy.bbs_physics.forms.FormTreeWalk;
import wemppy.bbs_physics.forms.PhysicsForms;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs without a Minecraft client or native physics world. */
public final class PhysicsSceneCheck
{
    public static void main(String[] args) throws Exception
    {
        BBSSettings.recordingTransformOverlays = new ValueInt("overlays", 0);
        BBSPhysicsSettings.enabled = new ValueBoolean("enabled", true);
        BBSPhysicsSettings.debug = new ValueBoolean("debug", true);
        Film film = null;
        Controller controller = new Controller(film);
        FilmScenes.onSetup(controller);

        for (int tick = 0; tick < 100; tick++)
        {
            FilmScenes.onTick(controller, tick);
            FilmScenes.onRender(controller, null);
        }

        require(empty().contains(controller), "Empty film must stay dormant");
        require(FilmScenes.getSceneCount() == 0 && FilmScenes.getStatus(controller) == null,
            "Empty film must not have a simulation or cache status");
        require(!attemptedJolt(), "Empty film must not initialize Jolt, even with debug enabled");

        Form root = new Form() {};
        checkUnanchoredRender(root);
        checkPoseCache(controller, root);
        require(PhysicsForms.isSimulatedTree(new ChainForm()), "Rope must be detected as physics");
        require(!PhysicsForms.isSimulatedTree(new Form() {}), "Plain form must not be simulated");
        int[] visits = {0};
        require(FormTreeWalk.any(root, form -> { visits[0]++; return true; }) && visits[0] == 1,
            "Presence search must stop at its first match");

        FilmScenes.onSetup(controller);
        require(!empty().contains(controller), "Cast setup must allow a fresh demand check");
        FilmScenes.onTick(controller, 100);
        FilmScenes.onShutdown(controller);
        require(empty().isEmpty(), "Shutdown must release dormant controllers");

        require(!SceneEdits.matters(List.of("replays", "actor", "form", "color_overlay")), "Overlay is cosmetic");
        require(!SceneEdits.matters(List.of("replays", "actor", "properties", "nested/render_layer", "0")), "Layer track is cosmetic");
        require(SceneEdits.matters(List.of("replays", "actor", "form", "visible")), "Visibility affects collisions");
        require(SceneEdits.matters(List.of("replays", "actor", "properties", "nested/transform", "0")), "Transform affects physics");
        require(SceneEdits.matters(List.of(FilmPhysics.KEY, "gravity")), "Film physics knobs affect physics");
        require(!SceneEdits.matters(List.of("camera", "0", "duration")), "Camera is not physical");
        checkFilmPhysics();
        checkFrameBudget();
        FilmScenes.clear();
        System.out.println("PhysicsSceneCheck passed: absent films, form presence, lifecycle cleanup, edit filtering, film physics knobs and frame budgets.");
    }

    /** The knobs travel with the film: saved, loaded, reset; a film without them reads the defaults. */
    private static void checkFilmPhysics()
    {
        FilmPhysics physics = new FilmPhysics();

        physics.gravity.set(1.62F);
        physics.collisionSteps.set(5);
        physics.worldBelow.set(128);

        FilmPhysics loaded = new FilmPhysics();

        loaded.fromData(physics.toData());
        require(loaded.gravity.get() == 1.62F && loaded.collisionSteps.get() == 5 && loaded.worldBelow.get() == 128,
            "Film physics must survive saving");

        loaded.resetAll();
        require(loaded.gravity.get() == PhysicsWorld.EARTH_GRAVITY && loaded.collisionSteps.get() == PhysicsWorld.COLLISION_STEPS
            && loaded.worldBelow.get() == 32, "Reset must restore the defaults");

        /* The lock travels with the film, but is not a knob: no edit of it takes a recording back,
         * a kept recording is not checked against it, and a reset leaves it alone. */
        physics.locked.set(true);
        loaded.fromData(physics.toData());
        require(loaded.locked.get(), "The physics lock must survive saving");
        require(!physics.knobs().has(FilmPhysics.LOCKED) && physics.knobs().has("gravity"),
            "What a recording is made from must leave the lock out");
        loaded.resetAll();
        require(loaded.locked.get(), "Reset must leave the lock alone");
        require(!SceneEdits.matters(List.of(FilmPhysics.KEY, FilmPhysics.LOCKED)) && SceneEdits.matters(List.of(FilmPhysics.KEY, "gravity")),
            "Locking must not count as a physics edit");

        FilmPhysics absent = FilmPhysics.of(null);
        require(absent.gravity.get() == PhysicsWorld.EARTH_GRAVITY && absent.speed.get() == 1F && absent.worldRadius.get() == 32,
            "A film without knobs must read the defaults");
    }

    /** A frame's share: the right order of urgency, never slower than the old per-tick pace, capped. */
    private static void checkFrameBudget()
    {
        long other = 10_000_000L;
        long none = FilmScenes.budget(FilmScene.Demand.NONE, true, false, other);
        long catchUp = FilmScenes.budget(FilmScene.Demand.CATCH_UP, true, false, other);
        long dragging = FilmScenes.budget(FilmScene.Demand.CATCH_UP, true, true, other);
        long background = FilmScenes.budget(FilmScene.Demand.BACKGROUND, true, false, other);
        long world = FilmScenes.budget(FilmScene.Demand.BACKGROUND, false, false, other);
        long asked = FilmScenes.budget(FilmScene.Demand.REQUESTED, true, false, other);

        require(none == 0L, "A scene with nothing to record takes no time");
        require(Math.abs(catchUp - 15_000_000L) < 10_000L, "The waited-for frame takes three fifths of the time: " + catchUp);
        require(dragging < catchUp && background < catchUp && world < background && asked > catchUp,
            "Urgency orders the shares: " + dragging + " " + background + " " + world + " " + asked);

        long slow = 60_000_000L;

        require(FilmScenes.budget(FilmScene.Demand.CATCH_UP, true, false, slow) >= (long) (slow * 0.24D / 0.76D) - 1L,
            "A slow frame still records at the old per-tick pace");
        require(FilmScenes.budget(FilmScene.Demand.REQUESTED, true, false, 1_000_000_000L) <= 50_000_000L,
            "No frame gives the recording more than fifty milliseconds");
    }

    /** BBS stores anchors on replays; a plain preview entity has no anchor at all. */
    private static void checkUnanchoredRender(Form form)
    {
        IEntity entity = (IEntity) Proxy.newProxyInstance(IEntity.class.getClassLoader(),
            new Class<?>[] {IEntity.class}, (proxy, method, args) ->
            {
                if (method.getName().equals("getForm")) return form;
                throw new AssertionError("Unexpected entity access: " + method.getName());
            });
        SceneActor.prepareRender(entity, Map.of(), 0.5F);
        SceneActor.prepareRender(entity, Map.of(), 0.5F, false);
        require(SceneActor.releaseAnchor(null) == null, "An absent replay anchor must stay absent");
    }

    /**
     * Catch-up ticks share a render frame and silent track writes keep the pose version. FS keys
     * every pose it keeps for the frame (a model form's own pose, the form tree's matrices) by the
     * frame's epoch, so a simulation step and a viewport restore have to move it.
     */
    private static void checkPoseCache(Controller controller, Form form)
    {
        SceneCast cast = new SceneCast(controller);

        for (int tick = 0; tick < 3; tick++)
        {
            long epoch = RenderFrame.getEpoch();

            cast.apply(tick);
            require(RenderFrame.getEpoch() != epoch,
                "A simulation tick must not reuse the previous tick's pose");
        }

        long epoch = RenderFrame.getEpoch();

        cast.restore(20);
        require(RenderFrame.getEpoch() != epoch,
            "Viewport restore must not reuse the simulation's pose");
    }

    private static Set<?> empty() throws Exception
    {
        Field field = FilmScenes.class.getDeclaredField("EMPTY");
        field.setAccessible(true);
        return (Set<?>) field.get(null);
    }

    private static boolean attemptedJolt() throws Exception
    {
        Field field = JoltEngine.class.getDeclaredField("attempted");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Controller extends BaseFilmController
    {
        Controller(Film film) { super(film); }
        @Override public Map<String, Integer> getActors() { return Map.of(); }
        @Override public int getTick() { return 0; }
    }
}
