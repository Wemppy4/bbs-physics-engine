package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.engine.JoltEngine;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps one {@link FilmScene} per running film and takes the four calls the mixins make.
 *
 * <p>Scenes are keyed by the controller object rather than by the film's id, because the editor
 * throws its controller away and builds a new one whenever the cast changes — two controllers for
 * the same film are two different scenes, and treating them as one would hand a rebuilt cast the
 * old simulation.</p>
 *
 * <p>Nothing in here is allowed to take BBS down with it. The calls arrive from inside BBS's own
 * tick and render, injected there by a mixin, so an exception would land in the middle of the
 * host's frame. Every entry point therefore catches, reports once and drops the scene: a film
 * without physics is a far better outcome than a film that crashes the game.</p>
 */
public class FilmScenes
{
    private static final Map<BaseFilmController, FilmScene> SCENES = new IdentityHashMap<>();

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

    private FilmScenes()
    {}

    private static boolean isEnabled()
    {
        return BBSPhysicsSettings.enabled != null && BBSPhysicsSettings.enabled.get() && JoltEngine.available();
    }

    /** The film's cast was assembled or rebuilt: the old simulation no longer describes it. */
    public static void onSetup(BaseFilmController controller)
    {
        drop(controller);
        dropOthersOf(controller);

        if (!isEnabled())
        {
            return;
        }

        try
        {
            FilmScene scene = new FilmScene(controller);

            SCENES.put(controller, scene);

            BBSPhysics.LOGGER.info("Physics scene is up for film \"{}\": {} bodies around ({}, {}, {}).",
                controller.film == null ? "?" : controller.film.getId(),
                scene.getWorld().getBodyCount(),
                scene.getOriginX(), scene.getOriginY(), scene.getOriginZ());
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
        if (!isEnabled())
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
            if (FAILED.contains(controller))
            {
                return;
            }

            /* A controller that started ticking without ever announcing its cast — build the scene
             * on first sight rather than never. */
            onSetup(controller);

            scene = SCENES.get(controller);

            if (scene == null)
            {
                return;
            }
        }

        if (scene.needsRebuild())
        {
            /* The author changed how much of the world takes part. That is the set of bodies, not
             * their state, so no amount of re-simulating fixes it — the scene is assembled again,
             * here, rather than at the next time the cast happens to change. */
            onSetup(controller);

            scene = SCENES.get(controller);

            if (scene == null)
            {
                return;
            }
        }

        try
        {
            scene.tick(tick);
        }
        catch (Throwable e)
        {
            BBSPhysics.LOGGER.error("A physics scene failed to reach tick {} and was dropped.", tick, e);

            fail(controller);
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

        if (film == null || !SceneEdits.matters(value.getPath().strings))
        {
            return;
        }

        /* An edit is the one thing that can undo whatever made a scene fail — the author deleting
         * the form that threw, most plainly — so it also clears the failures. One retry per edit is
         * paced by a human hand, unlike one per tick. */
        FAILED.clear();

        for (Map.Entry<BaseFilmController, FilmScene> entry : SCENES.entrySet())
        {
            Film other = entry.getKey().film;

            /* Identity first, then the id: the editor and its controller normally share the very
             * same film object, but a controller rebuilt around a reloaded film would not. */
            if (other == film || other != null && other.getId().equals(film.getId()))
            {
                entry.getValue().invalidate();
            }
        }
    }

    /** The film's actors have been drawn; the scene may draw its own things into the same pass. */
    public static void onRender(BaseFilmController controller, WorldRenderContext context)
    {
        if (!isEnabled() || BBSPhysicsSettings.debug == null || !BBSPhysicsSettings.debug.get())
        {
            return;
        }

        FilmScene scene = SCENES.get(controller);

        if (scene == null)
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
    }

    public static int getSceneCount()
    {
        return SCENES.size();
    }
}
