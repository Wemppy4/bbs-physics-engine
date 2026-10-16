package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import org.joml.Matrix4f;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.forms.FormTreeWalk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The poses a film's actors were walked into on each tick when the walk took in nothing simulated,
 * kept across runs of the recording — and across rebuilt scenes, since it belongs to the film.
 *
 * <p>Such a walk is the animation alone (see {@link PoseEvaluation#takesIn}): what the keyframes,
 * the form and its tracks say on that tick. A physics edit — a mass, a muscle, the film's gravity —
 * leaves all of that as it was, so recording again from the opening frame needs none of those walks,
 * and the dearest part of a step is read from here. An edit that changes the animation takes back
 * what it changed: a replay's keys from the first tick they change, a form whose looks changed in
 * full.</p>
 *
 * <p>Each actor's poses are checked against its replay whenever a scene takes them up (see
 * {@link #actor}): the form with its physics left out, and the models it was walked with. That is
 * what lets them outlive the cast BBS rebuilds on every undo and every closed form editor, physics
 * edits included.</p>
 */
final class ScenePoses
{
    /** Past this, poses are no longer kept: a long film then walks its later ticks as before. */
    static final long MAX_BYTES = 128L << 20;

    /** Roughly what an object and its array header cost, for the count kept against the limit. */
    private static final long OVERHEAD = 64;

    private final Map<String, Actor> actors = new HashMap<>();
    private final long maxBytes;
    private long bytes;

    ScenePoses()
    {
        this(MAX_BYTES);
    }

    ScenePoses(long maxBytes)
    {
        this.maxBytes = maxBytes;
    }

    /**
     * The poses kept for {@code replay}'s actor — or null when they may not be kept: a model of the
     * actor has not loaded yet, so a walk now is not the walk the actor will have.
     *
     * <p>The models are checked every time, which costs a lookup each. The replay is compared in full
     * when asked to, which a new scene does once, and whenever nothing says what the poses were taken
     * with; in between, every edit to it is followed as it happens (see {@link #edited}).</p>
     *
     * @param root  the actor's form as the scene walks it
     * @param check whether to compare the replay in full even when its edits were followed
     */
    Actor actor(Replay replay, Form root, boolean check)
    {
        if (replay == null || root == null)
        {
            return null;
        }

        List<Object> models = models(root);

        if (models == null)
        {
            return null;
        }

        Actor actor = this.entry(replay.getId());

        if (check || actor.signature == null)
        {
            actor.check(signature(replay), models);
        }
        else if (!Actor.sameModels(models, actor.models))
        {
            actor.forget();
            actor.check(signature(replay), models);
        }

        return actor;
    }

    /** The poses kept for the replay of this id, as they are — {@link #actor} checks them first. */
    Actor entry(String id)
    {
        return this.actors.computeIfAbsent(id, (key) -> new Actor());
    }

    /** Takes back what an edit changed of the animation — see {@link SceneChanges.Change}. */
    void edited(SceneChanges.Change change)
    {
        switch (change.poses())
        {
            case STAND -> {}
            case ALL -> this.clear();
            case FROM -> this.ifKept(change.replay(), (actor) -> actor.truncate(change.tick()));
            case FORM -> this.ifKept(change.replay(), (actor) -> actor.check(signature(change.replay()), actor.models));
            case REPLAY -> this.ifKept(change.replay(), Actor::forget);
        }
    }

    /** Applies {@code action} to the replay's poses if any are kept; with no replay, forgets all. */
    private void ifKept(Replay replay, Consumer<Actor> action)
    {
        Actor actor = replay == null ? null : this.actors.get(replay.getId());

        if (actor != null)
        {
            action.accept(actor);
        }
        else if (replay == null)
        {
            this.clear();
        }
    }

    void clear()
    {
        for (Actor actor : this.actors.values())
        {
            actor.forget();
        }

        this.actors.clear();
    }

    long bytes()
    {
        return this.bytes;
    }

    /**
     * What an actor's walks depend on that its keys do not say: its form with its physics left out,
     * and the replay's own settings that place it.
     *
     * <p>Physics is left out because a walk that takes in nothing simulated reads none of it — save
     * the strand modifier's list of bones, which decides what BBS's own chains leave alone, and so
     * stays in. The keys, the tracks and the clips are left out because the edits to them are
     * followed as they happen, to the tick (see {@link SceneChanges}). The replay's looks are left out
     * because nothing walked reads them.</p>
     */
    static MapType signature(Replay replay)
    {
        MapType data = new MapType();

        for (BaseValue value : replay.getAll())
        {
            String id = value.getId();

            if (value == replay.keyframes || value == replay.properties || value == replay.actions
                || isPhysics(id) || SceneEdits.isCosmeticReplayValue(id))
            {
                continue;
            }

            if (value == replay.form)
            {
                Form form = replay.form.get();

                data.put(id, form == null ? new MapType() : withoutPhysics(FormUtils.toData(form)));
            }
            else
            {
                data.put(id, value.toData());
            }
        }

        return data;
    }

    /** {@code data} with every value of the addon's own taken out, but the strand bones — in place. */
    static BaseType withoutPhysics(BaseType data)
    {
        if (data instanceof MapType map)
        {
            for (String key : new ArrayList<>(map.keys()))
            {
                if (isPhysics(key))
                {
                    map.remove(key);
                }
                else
                {
                    withoutPhysics(map.get(key));
                }
            }
        }
        else if (data instanceof ListType list)
        {
            for (BaseType element : list.elements)
            {
                withoutPhysics(element);
            }
        }

        return data;
    }

    /** The addon's own values, but the one a walk reads: which bones the strand modifier owns. */
    static boolean isPhysics(String id)
    {
        return id != null && id.startsWith("bbs_physics:") && !id.equals(FormChains.KEY);
    }

    /** The model of every model form in the tree, in order, or null while one has not loaded. */
    private static List<Object> models(Form root)
    {
        List<Object> models = new ArrayList<>();
        boolean[] missing = {false};

        FormTreeWalk.walk(root, (form, path, anchor) ->
        {
            if (form instanceof ModelForm model)
            {
                Object instance = ModelFormRenderer.getModel(model);

                if (instance == null)
                {
                    missing[0] = true;
                }

                models.add(instance);
            }

            return true;
        });

        return missing[0] ? null : models;
    }

    /** One actor's poses, by tick. */
    final class Actor
    {
        private MapType signature;
        private List<Object> models;
        private final ArrayList<Pose> ticks = new ArrayList<>();

        /** The paths of the last pose kept, so that consecutive ticks share one array of them. */
        private String[] paths;

        /** Forgets everything when what the walks depend on is not what they were taken with. */
        private void check(MapType signature, List<Object> models)
        {
            if (!signature.equals(this.signature) || !sameModels(models, this.models))
            {
                this.forget();
            }

            this.signature = signature;
            this.models = models;
        }

        /** The pose kept for {@code tick}, or null. */
        Pose get(int tick)
        {
            return tick >= 0 && tick < this.ticks.size() ? this.ticks.get(tick) : null;
        }

        /** Keeps the walk just taken for {@code tick}, while there is room. */
        void put(int tick, MatrixCache matrices, Matrix4f actorWorld, String[] frames, Matrix4f[] parentFrames)
        {
            if (tick < 0 || this.get(tick) != null)
            {
                return;
            }

            Pose pose = Pose.of(matrices, actorWorld, frames, parentFrames, this.paths);

            if (ScenePoses.this.bytes + pose.bytes > ScenePoses.this.maxBytes)
            {
                return;
            }

            while (this.ticks.size() <= tick)
            {
                this.ticks.add(null);
            }

            this.ticks.set(tick, pose);
            this.paths = pose.paths;
            ScenePoses.this.bytes += pose.bytes;
        }

        /** Forgets the poses from {@code tick} on: the animation is different there now. */
        void truncate(int tick)
        {
            for (int i = this.ticks.size() - 1; i >= Math.max(0, tick); i--)
            {
                Pose pose = this.ticks.remove(i);

                if (pose != null)
                {
                    ScenePoses.this.bytes -= pose.bytes;
                }
            }
        }

        /** Forgets every pose, and what they were taken with, so the next scene checks afresh. */
        void forget()
        {
            this.truncate(0);
            this.signature = null;
        }

        static boolean sameModels(List<Object> a, List<Object> b)
        {
            if (a == null || b == null || a.size() != b.size())
            {
                return false;
            }

            for (int i = 0; i < a.size(); i++)
            {
                if (a.get(i) != b.get(i))
                {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * One walk, packed: the matrix of every path it left, where the actor stood, and the parent frame
     * it left on every form with a body state.
     */
    static final class Pose
    {
        final String[] paths;
        private final float[] matrices;
        private final float[] actorWorld = new float[16];
        final String[] frames;
        private final float[] parentFrames;
        private final long bytes;

        private Pose(String[] paths, float[] matrices, String[] frames, float[] parentFrames)
        {
            this.paths = paths;
            this.matrices = matrices;
            this.frames = frames;
            this.parentFrames = parentFrames;
            this.bytes = OVERHEAD * 4 + 4L * (matrices.length + parentFrames.length + 16);
        }

        /**
         * @param previous the paths of the pose kept before, shared when this walk left the same ones
         */
        static Pose of(MatrixCache matrices, Matrix4f actorWorld, String[] frames, Matrix4f[] parentFrames, String[] previous)
        {
            List<String> present = new ArrayList<>();

            for (String path : matrices.keySet())
            {
                if (matrices.get(path).matrix() != null)
                {
                    present.add(path);
                }
            }

            String[] paths = present.toArray(new String[0]);

            Arrays.sort(paths);

            if (Arrays.equals(paths, previous))
            {
                paths = previous;
            }

            float[] data = new float[paths.length * 16];

            for (int i = 0; i < paths.length; i++)
            {
                matrices.get(paths[i]).matrix().get(data, i * 16);
            }

            float[] parents = new float[frames.length * 16];

            for (int i = 0; i < frames.length; i++)
            {
                parentFrames[i].get(parents, i * 16);
            }

            Pose pose = new Pose(paths, data, frames, parents);

            actorWorld.get(pose.actorWorld, 0);

            return pose;
        }

        /** The walk's matrices, each a fresh object, as a walk would hand them out. */
        MatrixCache matrices()
        {
            MatrixCache cache = new MatrixCache();

            for (int i = 0; i < this.paths.length; i++)
            {
                cache.put(this.paths[i], new Matrix4f().set(this.matrices, i * 16), null);
            }

            return cache;
        }

        void actorWorld(Matrix4f into)
        {
            into.set(this.actorWorld, 0);
        }

        void parentFrame(int index, Matrix4f into)
        {
            into.set(this.parentFrames, index * 16);
        }
    }
}
