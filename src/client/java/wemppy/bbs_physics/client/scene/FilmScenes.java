package wemppy.bbs_physics.client.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import wemppy.bbs_physics.BBSPhysics;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.engine.JoltEngine;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.actions.ImpulseActionClip;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps one {@link FilmScene} per running film and takes the four calls BBS makes into it.
 *
 * <p>Scenes are keyed by the controller object rather than by the film's id, because the editor
 * throws its controller away and builds a new one whenever the cast changes — two controllers for
 * the same film are two different scenes, and treating them as one would hand a rebuilt cast the
 * old simulation.</p>
 *
 * <p>Nothing in here is allowed to take BBS down with it. The calls arrive from inside BBS's own
 * tick and render — it posts them there itself (see {@code FilmEvents}) — so an exception would
 * land in the middle of the host's frame. Every entry point therefore catches, reports once and drops the scene: a film
 * without physics is a far better outcome than a film that crashes the game.</p>
 */
public class FilmScenes
{
    private static final Map<BaseFilmController, FilmScene> SCENES = new IdentityHashMap<>();
    private static final Map<String, FilmScene.Recording> RETAINED = new java.util.HashMap<>();

    private static boolean manual(BaseFilmController controller)
    {
        return controller instanceof mchorse.bbs_mod.ui.film.controller.FilmEditorController
            && BBSPhysicsSettings.manualCalculation != null && BBSPhysicsSettings.manualCalculation.get();
    }

    /** Films with no simulation: do not rescan their form trees on every tick. */
    private static final Set<BaseFilmController> EMPTY = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Controllers whose scene threw on the way up or on the way forward.
     *
     * <p>Without this the report above is not the whole story. {@link #onTick} builds a scene for a
     * controller it has not seen yet, so a film that throws while assembling is assembled again on
     * the very next tick — and assembling collects the world's blocks, tens of thousands of boxes,
     * from scratch each time. What the author sees is not a line in the log but a game that
     * crawls, which is how one broken form reads as "the addon is slow". So a failure is
     * remembered: reported once, then the film plays without physics until something happens that
     * could plausibly have fixed it — the cast is rebuilt, or the author edits the film.</p>
     */
    private static final Set<BaseFilmController> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());

    /** When the last frame's share of the recording began, and how long it took — see {@link #onFrame}. */
    private static long frameStarted;
    private static long frameSpent;

    /** A frame's length when there is no previous frame to measure it by. */
    private static final long FRAME = 16_666_667L;

    /** How long without a frame before the ticks take the recording back — see {@link FilmScene#tick}. */
    private static final long FRAMES_GONE = 250_000_000L;

    /** The most a frame ever gives the recording, however much the shares below would allow. */
    private static final long MAX_BUDGET = 50_000_000L;

    private FilmScenes()
    {}

    private static boolean isEnabled()
    {
        return BBSPhysicsSettings.enabled != null && BBSPhysicsSettings.enabled.get() && JoltEngine.available();
    }

    /** The film's cast was assembled or rebuilt: the old simulation no longer describes it. */
    public static void onSetup(BaseFilmController controller)
    {
        if (manual(controller) && controller.film != null)
        {
            for (var entry : SCENES.entrySet())
            {
                if (manual(entry.getKey()) && sameFilm(entry.getKey().film, controller.film))
                    RETAINED.putIfAbsent(controller.film.getId(), entry.getValue().recording());
            }
        }
        drop(controller);
        dropOthersOf(controller);

        /* Construction is deferred until onTick. Repeated cast notifications before that tick
         * must not each allocate a Jolt world and collect the same Minecraft blocks. */
    }

    private static void create(BaseFilmController controller)
    {
        try
        {
            dropOthersOf(controller);

            if (!hasSimulation(controller.film))
            {
                EMPTY.add(controller);
                return;
            }

            if (!isEnabled())
            {
                FAILED.add(controller);
                return;
            }

            FilmScene scene = new FilmScene(controller,
                manual(controller) ? RETAINED.get(controller.film.getId()) : null);

            SCENES.put(controller, scene);

            BBSPhysics.LOGGER.info("Physics scene is up for film \"{}\": {} bodies around ({}, {}, {}), on world collision from {} blocks.",
                controller.film == null ? "?" : controller.film.getId(),
                scene.getWorld().getBodyCount(),
                scene.getOriginX(), scene.getOriginY(), scene.getOriginZ(),
                scene.getWorldBoxes());
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.error("Failed to build a physics scene for a film, it will play without physics.", e);

            fail(controller);
        }
    }

    /** The film reached {@code tick} and every actor is already updated to it. */
    public static void onTick(BaseFilmController controller, int tick)
    {
        if (controller instanceof mchorse.bbs_mod.ui.film.controller.FilmEditorController
            && !manual(controller) && controller.film != null)
            RETAINED.remove(controller.film.getId());
        if (BBSPhysicsSettings.enabled == null || !BBSPhysicsSettings.enabled.get())
        {
            /* Switched off while a film was running. The scene goes now rather than at shutdown:
             * a Jolt world is native memory, and "physics is off" should mean the addon is not
             * holding any. It builds itself again on the next tick if it is switched back on. */
            drop(controller);

            return;
        }

        FilmScene scene = SCENES.get(controller);

        if (scene == null)
        {
            if (FAILED.contains(controller) || EMPTY.contains(controller))
            {
                return;
            }

            /* A controller that started ticking without ever announcing its cast — build the scene
             * on first sight rather than never. */
            create(controller);

            scene = SCENES.get(controller);

            if (scene == null)
            {
                return;
            }
        }

        else if (scene.needsRebuild())
        {
            /* The author changed how much of the world takes part. That is the set of bodies, not
             * their state, so no amount of re-simulating fixes it — the scene is assembled again,
             * here, rather than at the next time the cast happens to change. */
            onSetup(controller);
            create(controller);

            scene = SCENES.get(controller);

            if (scene == null)
            {
                return;
            }
        }

        try
        {
            scene.tick(tick, paced(), exporting());
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.error("A physics scene failed to reach tick {} and was dropped.", tick, e);

            fail(controller);
        }
    }

    /**
     * A frame is about to be drawn: every scene records in the time this frame can spare.
     *
     * <p>Recording used to happen on the film's tick, twenty times a second, twelve milliseconds at a
     * time while the cursor waited and four once it had its frame — never more than a quarter of the
     * time, and a run ahead no faster than the film plays. Here it takes a <em>share</em> of the
     * time instead, measured against what the rest of the frame costs, so a fast machine records
     * fast and a slow one keeps its frame rate. How big a share depends on who is waiting (see
     * {@link #budget}), and the most urgent scene sets it for the frame.</p>
     *
     * <p>Before the world is drawn, so a frame recorded here is the frame drawn now. A video being
     * recorded is the exception to every budget: each of its frames gets its physics, whatever it
     * costs.</p>
     */
    public static void onFrame()
    {
        long now = System.nanoTime();
        long interval = frameStarted == 0 ? FRAME : now - frameStarted;
        long other = Math.max(1_000_000L, Math.min(100_000_000L, interval - frameSpent));

        frameStarted = now;
        frameSpent = 0;

        if (SCENES.isEmpty() || !isEnabled())
        {
            return;
        }

        boolean exporting = exporting();
        List<Map.Entry<BaseFilmController, FilmScene>> scenes = new ArrayList<>(SCENES.entrySet());

        /* Most urgent first: the frame's budget is the one its most urgent scene asks for. */
        scenes.sort(Comparator.comparing((Map.Entry<BaseFilmController, FilmScene> entry) -> entry.getValue().demand()).reversed());

        long deadline = 0L;

        for (Map.Entry<BaseFilmController, FilmScene> entry : scenes)
        {
            FilmScene scene = entry.getValue();

            try
            {
                if (exporting)
                {
                    scene.recordCursor();

                    continue;
                }

                FilmScene.Demand demand = scene.demand();

                if (demand == FilmScene.Demand.NONE)
                {
                    continue;
                }

                if (deadline == 0L)
                {
                    deadline = now + budget(demand, scene.isEditor(), scene.isEditing(), other);
                }

                scene.advance(deadline);
            }
            catch (Throwable e)
            {
                BBSPhysics.LOGGER.error("A physics scene failed while recording and was dropped.", e);

                fail(entry.getKey());
            }
        }

        frameSpent = System.nanoTime() - now;
    }

    /**
     * How long a frame may record, given what the rest of the frame costs ({@code other}).
     *
     * <p>Shares of the time rather than fixed amounts. Most for a calculation the author asked for
     * by name; a little less for the frame an author is looking at; a third of the time for running
     * ahead in the editor; the old, thin shares for a film playing in the world, where the frame rate
     * belongs to the audience. While an edit is still landing — a slider being dragged — the cursor
     * gets only what it used to, so the editor stays smooth under the hand, and the full share once it
     * lets go. Each share stops where the frame would fall below thirty frames a second (fifteen for
     * an asked-for calculation), but never below what the old per-tick schedule gave: nothing records
     * slower than it did.</p>
     */
    static long budget(FilmScene.Demand demand, boolean editor, boolean editing, long other)
    {
        double share;
        double floor;
        long frame;

        switch (demand)
        {
            case REQUESTED ->
            {
                share = 0.75D;
                floor = 0.24D;
                frame = 66_000_000L;
            }
            case CATCH_UP ->
            {
                share = !editor ? 0.35D : editing ? 0.24D : 0.6D;
                floor = 0.24D;
                frame = 33_000_000L;
            }
            case BACKGROUND ->
            {
                share = editor ? 0.4D : 0.1D;
                floor = 0.08D;
                frame = 33_000_000L;
            }
            default ->
            {
                return 0L;
            }
        }

        long wanted = Math.min((long) (other * share / (1D - share)), frame - other);
        long least = (long) (other * floor / (1D - floor));

        return Math.min(MAX_BUDGET, Math.max(wanted, least));
    }

    /** Whether frames are being drawn, and so are doing the recording. */
    private static boolean paced()
    {
        return frameStarted != 0L && System.nanoTime() - frameStarted < FRAMES_GONE;
    }

    /** Whether BBS is recording a video, every frame of which must carry its physics. */
    private static boolean exporting()
    {
        try
        {
            return BBSModClient.getVideoRecorder().isRecording();
        }
        catch (Throwable e)
        {
            return false;
        }
    }

    /**
     * The editor changed one value of a film — a keyframe, a form, a track.
     *
     * <p>Only the scenes of <em>that</em> film start over, and only when the value is something the
     * simulation reads (see {@link SceneEdits}): a label, a colour or a shadow changes nothing
     * physical, and a re-simulation for it was the bar going grey for no reason. A value that
     * belongs to no film — the model editor, a setting — is not a film edit at all.</p>
     */
    public static void onFilmEdited(BaseValue value)
    {
        if (value == null)
        {
            return;
        }

        Film film = SceneEdits.filmOf(value);

        if (film == null || (value != film && !SceneEdits.matters(value.getPath().strings)))
        {
            return;
        }

        onFilmEdited(film, List.of(value));
    }

    public static void onFilmEdited(Film film, List<BaseValue> values)
    {
        boolean significant = false;
        boolean impulseEdit = true;
        for (BaseValue value : values)
        {
            if (value != film && !SceneEdits.matters(value.getPath().strings)) continue;
            significant = true;
            boolean impulse = false;
            for (BaseValue parent = value; parent != null && parent != film; parent = parent.getParent())
            {
                if (parent instanceof ImpulseActionClip) { impulse = true; break; }
            }
            impulseEdit &= impulse;
        }
        if (!significant) return;

        FAILED.removeIf(controller -> sameFilm(controller.film, film));
        EMPTY.removeIf(controller -> sameFilm(controller.film, film));

        Iterator<Map.Entry<BaseFilmController, FilmScene>> scenes = SCENES.entrySet().iterator();

        while (scenes.hasNext())
        {
            Map.Entry<BaseFilmController, FilmScene> entry = scenes.next();
            Film other = entry.getKey().film;

            /* Identity first, then the id: the editor and its controller normally share the very
             * same film object, but a controller rebuilt around a reloaded film would not. */
            if (sameFilm(other, film))
            {
                if (manual(entry.getKey()) || hasSimulation(other))
                {
                    /* Only when the edit reaches what this scene reads: moving the keys of an
                     * actor nothing simulated touches leaves the recording standing. */
                    if (entry.getValue().reads(film, values))
                    {
                        entry.getValue().invalidate(impulseEdit);
                    }
                }
                else
                {
                    BaseFilmController controller = entry.getKey();
                    entry.getValue().close();
                    scenes.remove();
                    EMPTY.add(controller);
                }
            }
        }
    }

    /** The film's actors have been drawn; the scene may draw its own things into the same pass. */
    public static void onRender(BaseFilmController controller, WorldRenderContext context)
    {
        FilmScene scene = SCENES.get(controller);

        if (scene == null || BBSPhysicsSettings.debug == null || !BBSPhysicsSettings.debug.get() || !isEnabled())
        {
            return;
        }

        try
        {
            SceneDebugRenderer.render(scene, context);
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.error("A physics scene failed to draw its debug overlay and was dropped.", e);

            /* Remembered as a failure, not merely dropped: rebuilding the scene is no answer to a
             * drawing bug, so the next tick would build it again only for the next frame to throw
             * again — the same crawl as a scene that cannot be assembled. */
            fail(controller);
        }
    }

    public static void requestCalculation(BaseFilmController controller)
    {
        if (controller == null || !isEnabled()) return;
        // Only an explicit calculation replaces the retained recording.
        if (controller.film != null) RETAINED.remove(controller.film.getId());
        drop(controller);
        dropOthersOf(controller);
        create(controller);
        FilmScene scene = SCENES.get(controller);
        if (scene != null) scene.requestCalculation(controller.getTick());
    }

    /** The live scene of this controller, or null when it has none. */
    public static FilmScene get(BaseFilmController controller)
    {
        return controller == null ? null : SCENES.get(controller);
    }

    /**
     * What the simulation of this film is doing, or null when it has none — physics switched off,
     * a controller without a scene, or a film that failed to build one.
     */
    public static SceneStatus getStatus(BaseFilmController controller)
    {
        FilmScene scene = controller == null ? null : SCENES.get(controller);

        return scene == null ? null : scene.getStatus();
    }

    /**
     * The same, found by the film itself rather than by its controller — for the editor's UI, which
     * knows which film it is showing but not which controller is playing it.
     *
     * <p>Identity first, then the film's id: the editor's panel and its controller normally hold
     * the very same object, but a controller rebuilt around a reloaded film would not, and a cache
     * bar that silently vanished after a reload would be a puzzle rather than a signal.</p>
     */
    public static SceneStatus getStatus(Film film)
    {
        if (film == null)
        {
            return null;
        }

        FilmScene byId = null;

        for (Map.Entry<BaseFilmController, FilmScene> entry : SCENES.entrySet())
        {
            BaseFilmController controller = entry.getKey();

            if (controller.film == film)
            {
                return entry.getValue().getStatus();
            }

            if (controller.film != null && controller.film.getId().equals(film.getId()))
            {
                byId = entry.getValue();
            }
        }

        return byId == null ? null : byId.getStatus();
    }

    /** The film is gone. */
    public static void onShutdown(BaseFilmController controller)
    {
        if (manual(controller) && controller.film != null) RETAINED.remove(controller.film.getId());
        drop(controller);
    }

    private static void drop(BaseFilmController controller)
    {
        FilmScene scene = SCENES.remove(controller);

        if (scene != null)
        {
            scene.close();
        }

        /* Forgetting the controller means forgetting why it has no scene as well. Every fresh
         * attempt starts here — {@link #onSetup} drops before it builds — so the mark being set
         * after the drop, by {@link #fail}, is what makes an attempt one attempt. */
        FAILED.remove(controller);
        EMPTY.remove(controller);
    }

    /** The scene is gone and is not to be built again until an edit or a rebuilt cast says so. */
    private static void fail(BaseFilmController controller)
    {
        drop(controller);

        FAILED.add(controller);
    }

    /**
     * Closes scenes belonging to earlier controllers of the same film.
     *
     * <p>The film editor does not reuse its controller: every rebuild of the cast constructs a
     * brand new {@code FilmEditorController} and drops the previous one on the floor, without ever
     * shutting it down. Keyed by controller identity, that would leave a Jolt world — native
     * memory a garbage collector will never come back for — behind on every edit. One film has one
     * live controller, so any other controller holding a scene for this film is a leftover.</p>
     */
    private static void dropOthersOf(BaseFilmController controller)
    {
        if (controller.film == null)
        {
            return;
        }

        String filmId = controller.film.getId();

        SCENES.entrySet().removeIf((entry) ->
        {
            BaseFilmController other = entry.getKey();
            boolean stale = isStale(controller, filmId, other);

            if (stale)
            {
                entry.getValue().close();
            }

            return stale;
        });

        /* A leftover that failed holds no scene, so the sweep above never sees it — and a mark left
         * behind keeps a discarded controller, its film and its cast alive for as long as the game
         * runs. The mark is the leftover's only trace, so it is swept on the same terms. */
        FAILED.removeIf((other) -> isStale(controller, filmId, other));
        EMPTY.removeIf((other) -> isStale(controller, filmId, other));
    }

    /** Reads authored flags only: no model loading, matrix evaluation or native initialization. */
    private static boolean hasSimulation(Film film)
    {
        if (film != null)
        {
            for (Replay replay : film.replays.getList())
            {
                if (replay.enabled.get() && PhysicsForms.isSimulatedTree(replay.form.get()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean sameFilm(Film other, Film film)
    {
        return other == film || other != null && other.getId().equals(film.getId());
    }

    private static boolean isStale(BaseFilmController controller, String filmId, BaseFilmController other)
    {
        return other != controller && other.film != null && filmId.equals(other.film.getId());
    }

    /**
     * Closes every scene. Called when the client leaves a world, where controllers are dropped
     * without shutting down and their native worlds would otherwise be left behind.
     */
    public static void clear()
    {
        Iterator<FilmScene> it = SCENES.values().iterator();

        while (it.hasNext())
        {
            it.next().close();
            it.remove();
        }

        FAILED.clear();
        EMPTY.clear();
        RETAINED.clear();
    }

    public static int getSceneCount()
    {
        return SCENES.size();
    }
}
