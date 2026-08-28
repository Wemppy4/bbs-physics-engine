package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_physics.chain.FormChains;
import mchorse.bbs_physics.client.ragdoll.RagdollPoseApplier;
import mchorse.bbs_physics.forms.IPhysicsForm;
import mchorse.bbs_physics.forms.PhysicsForms;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns the recording of one form's physics into ordinary keyframes of its replay — Blender's
 * "Bake to Keyframes", the last open item of Э3 (§9.3 of the concept).
 *
 * <p><b>What is written.</b> A rigid body becomes keys on the form's own {@code transform}, on the
 * ticks the simulation moved it (see {@link Track}), holding exactly the position and rotation the renderer was substituting. A ragdoll or a
 * strand of hair becomes keys on the per-bone pose tracks — the same tracks the author gets when
 * keyframing a bone by hand — holding the local rotation and shift that land each bone where the
 * simulation had it. After that the film plays the same with the simulation switched off, and every
 * one of those keys can be dragged, deleted or retimed like any other.</p>
 *
 * <p><b>What is baked is what was drawn</b>, not what the world computed. The renderer weighs the
 * simulated pose against the animated one by the authority handle (§4), so on a tick the animation
 * owns outright the baked key is the animation's own value, untouched, and on a half-released
 * tick it is the blend the viewport was showing. Baking anything else would change the film in the
 * act of freezing it.</p>
 *
 * <p><b>Two passes, on purpose.</b> A bone track is <em>additive</em> — its value is composed on
 * top of the form's pose and of whatever the track already held — so a key written on tick 10
 * would change what tick 11 evaluates to before tick 11 had been read. Every tick is therefore
 * worked out against the film as it stands, and only then is anything written, inside one edit
 * of the film so that the whole bake is one step of the undo history.</p>
 *
 * <p><b>Rotations are stored as quaternions</b> where the simulation had a say. The concept
 * warned that Euler conventions are where this kind of thing goes wrong (§10.1), and the
 * {@code Transform} of BBS can hold a rotation either way; a quaternion key composes with the
 * animation exactly as the ragdoll applier composed it, with no angle to unwrap. On ticks the
 * animation owned the key stays in Euler form, so a bone nobody released is left byte for byte as
 * the author had it.</p>
 *
 * <p><b>Afterwards the handle is set to 1</b> and its own track is emptied: the animation owns the
 * form again, which is the whole point. The modifier itself stays — its settings are kept, and a
 * body the animation owns still collides with everything around it, so a baked crate goes on
 * pushing the crates that were not baked. Blender removes the rigid body instead; keeping it is
 * the more useful of the two here and costs nothing.</p>
 */
public final class PhysicsBake
{
    /** What a bake did, for the message the author gets. */
    public record Result(int ticks, int keys, int channels)
    {}

    private final Film film;
    private final Replay replay;
    private final String formPath;

    /** The tick being worked out — of the film, and of the replay (which may loop). */
    private int tick;
    private int local;

    /** Model forms whose bones answered on this tick, with the path each sits at. */
    private final Map<ModelForm, String> models = new LinkedHashMap<>();

    /** Every key worked out so far, per track. */
    private final Map<String, Track> staged = new LinkedHashMap<>();

    /**
     * A track's worked-out values, tick by tick, and which of those ticks the simulation actually
     * had a say on.
     *
     * <p>The distinction is what keeps the bake from flooding the film with keys (the first
     * thing Вемпи saw): a tick the animation owned outright needs no key at all — its value is
     * the animation's own, which is already on the track — and a tick on which a body lay still
     * needs no key either, since the keys either side of the still stretch say the same thing.
     * Only the ticks the simulation moved something on are written, plus one on each side so the
     * interpolation into and out of the simulated stretch is anchored, and a flat run inside
     * collapses to its two ends.</p>
     */
    private static final class Track
    {
        final IKeyframeFactory<?> factory;
        final TreeMap<Integer, Transform> values = new TreeMap<>();
        final Set<Integer> simulated = new HashSet<>();

        Track(IKeyframeFactory<?> factory)
        {
            this.factory = factory;
        }
    }

    /** The tracks the replay already has, read for the values the bake composes on top of. */
    private final Map<String, KeyframeChannel> existing = new HashMap<>();

    /** The paths of the forms whose handle is to be set to 1 once the keys are in. */
    private final Set<String> baked = new LinkedHashSet<>();

    private int ticks;

    PhysicsBake(Film film, Replay replay, String formPath)
    {
        this.film = film;
        this.replay = replay;
        this.formPath = formPath;
    }

    /** Points the collector at the tick that has just been posed. */
    void at(int tick)
    {
        this.tick = tick;
        this.local = this.replay.getTick(tick);
        this.models.clear();
    }

    /**
     * A rigid body's recorded answer for the tick: where the renderer would put the form,
     * relative to the frame its transform is applied in.
     */
    public void body(Form form, String path, Vector3f position, Quaternionf rotation, float authority)
    {
        if (!path.equals(this.formPath))
        {
            return;
        }

        Transform animated = form.transform.get();
        Transform value = new Transform();

        if (authority >= 1F)
        {
            /* The renderer draws the keyframes outright on a tick the animation owns (it never
             * substitutes a kinematic body), so the key is the animation's own value — not the
             * body's driven pose, which merely chases it. */
            value.copy(animated);
        }
        else
        {
            value.translate.set(position);
            value.scale.set(animated.scale);
            value.rotationMode = Transform.RotationMode.QUATERNION;
            value.quat.set(rotation).normalize();
        }

        this.stage(this.transformKey(path), KeyframeFactories.TRANSFORM, value, authority < 1F);
        this.baked.add(path);
    }

    /**
     * A ragdoll or chain rig has put its recorded pose for the tick into the model's state — the
     * bones are worked out together once every rig of the tick has reported, see {@link #finishTick}.
     */
    public void bones(ModelForm form, String path)
    {
        if (path.equals(this.formPath))
        {
            this.models.put(form, path);
        }
    }

    /**
     * Works out this tick's bone keys: runs the very substitution the renderer runs, then reads off
     * each simulated bone the local rotation and shift it ended up with, and expresses them as the
     * value a per-bone track has to hold for the pose pipeline to compose the same frame.
     *
     * <p>The composition being mirrored is {@code Model.applyPose}: a bone's evaluated orientation
     * is the animation channels' rotation times the combined pose's rotation, and the combined pose
     * is the form's pose with the track's value multiplied on the right. The track value is solved
     * for from there; the pose the bone is composed on top of is read with the track's current
     * value divided back out, which is what makes re-baking over an earlier bake come out right.</p>
     */
    void finishTick()
    {
        this.ticks++;

        for (Map.Entry<ModelForm, String> entry : this.models.entrySet())
        {
            ModelForm form = entry.getKey();
            ModelInstance instance = ModelFormRenderer.getModel(form);

            if (instance == null || !(instance.model instanceof Model cubic) || !(FormUtilsClient.getRenderer(form) instanceof ModelFormRenderer renderer))
            {
                continue;
            }

            RagdollState ragdoll = FormRagdolls.getState(form);
            RagdollState chain = FormChains.getState(form);
            Pose combined = renderer.getPose();
            Map<ModelGroup, Vector3f> shifts = new HashMap<>();

            /* The shift each bone had before the substitution — the IK stretch's, where there was
             * one. The substitution blends on top of it, and the key has to carry only the
             * difference, since the stretch is applied again at draw time. */
            for (String id : cubic.getAllGroupKeys())
            {
                ModelGroup group = cubic.getGroup(id);

                if (group != null)
                {
                    shifts.put(group, group.offset == null ? new Vector3f() : new Vector3f(group.offset));
                }
            }

            RagdollPoseApplier.apply(form, instance, 1F);

            for (Map.Entry<ModelGroup, Vector3f> bone : shifts.entrySet())
            {
                ModelGroup group = bone.getKey();
                float weight = Math.max(weight(ragdoll, group.id), weight(chain, group.id));

                if (!has(ragdoll, group.id) && !has(chain, group.id))
                {
                    continue;
                }

                String key = PerLimbService.toPoseBoneKey(entry.getValue(), group.id);
                PoseTransform old = this.existing(key);
                PoseTransform value = new PoseTransform();

                if (old != null)
                {
                    value.copy(old);
                }

                if (weight > 0F)
                {
                    this.solve(group, combined.get(group.id), old, bone.getValue(), value);
                }

                this.stage(key, KeyframeFactories.POSE_TRANSFORM, value, weight > 0F);
            }

            this.baked.add(entry.getValue());
        }
    }

    /**
     * The one track value that composes to the bone's substituted frame.
     *
     * @param group    the bone, holding the substituted orientation and shift
     * @param combined the form's combined pose for the bone, the track's current value included
     * @param old      the track's current value, or null when the track is empty
     * @param before   the bone's shift before the substitution
     * @param value    the key to fill, already holding the old value's colour and the like
     */
    private void solve(ModelGroup group, PoseTransform combined, PoseTransform old, Vector3f before, PoseTransform value)
    {
        /* The animation channels alone: applyPose added the pose's Euler readback onto the
         * channels, so it is subtracted back out. */
        Vector3f readback = combined == null ? new Vector3f() : combined.getEulerRotation(new Vector3f());

        readback.set((float) Math.toDegrees(readback.x), (float) Math.toDegrees(readback.y), (float) Math.toDegrees(readback.z));

        Quaternionf channels = Matrices.toLocalRotationZYXDegrees(new Vector3f(group.current.rotate).sub(readback));

        /* The pose the track is composed onto — the combined pose with the track's own value
         * taken back out. Two Euler poses were added angle by angle, so they are separated the
         * same way; anything involving a quaternion was a product and is divided. */
        Quaternionf base;

        if (combined == null)
        {
            base = new Quaternionf();
        }
        else if (old != null && combined.rotationMode == Transform.RotationMode.EULER && old.rotationMode == Transform.RotationMode.EULER)
        {
            base = Matrices.toLocalRotationZYXRadians(new Vector3f(combined.rotate).sub(old.rotate));
        }
        else
        {
            base = combined.createRotation();

            if (old != null)
            {
                base.mul(old.createRotation().invert());
            }
        }

        /* channels × base × key = substituted, solved for the key. */
        Quaternionf key = channels.mul(base).invert().mul(group.evaluatedRotation()).normalize();

        value.rotationMode = Transform.RotationMode.QUATERNION;
        value.quat.set(key);
        value.rotate.set(0F, 0F, 0F);

        /* The shift, in the parent frame, is applied by the renderer as a translate of the pose
         * channels — sixteenths, with the cubic X flip — so the difference the substitution made
         * goes into the translate on top of whatever the track already moved the bone by. */
        Vector3f after = group.offset == null ? new Vector3f() : group.offset;

        value.translate.add(
            -(after.x - before.x) * 16F,
            (after.y - before.y) * 16F,
            (after.z - before.z) * 16F);
    }

    /**
     * Writes everything worked out into the replay, as one edit of the film, and hands the baked
     * forms back to their animation.
     */
    Result write()
    {
        Form root = this.replay.form.get();

        if (root == null)
        {
            return new Result(this.ticks, 0, 0);
        }

        int[] keys = new int[1];

        BaseValue.edit(this.film, (film) ->
        {
            for (Map.Entry<String, Track> entry : this.staged.entrySet())
            {
                Track track = entry.getValue();
                List<List<Integer>> runs = runs(track);

                if (runs.isEmpty())
                {
                    /* The simulation never had a say on this track — the handle stood at 1 the
                     * whole film — so the author's keys are left exactly as they were. */
                    continue;
                }

                KeyframeChannel channel = this.replay.properties.getOrCreate(root, entry.getKey());

                if (channel == null)
                {
                    continue;
                }

                /* The track is rebuilt as one piece: the author's keys outside the simulated
                 * stretches, the bake's keys inside them. Two notifications rather than one per
                 * key, which on a long film is thousands. */
                KeyframeChannel merged = new KeyframeChannel(entry.getKey(), channel.getFactory());

                for (Object o : channel.getKeyframes())
                {
                    Keyframe<?> keyframe = (Keyframe<?>) o;

                    if (!inside(runs, keyframe.getTick()))
                    {
                        Keyframe copy = new Keyframe<>(keyframe.getId(), keyframe.getFactory());

                        copy.fromData(keyframe.toData());
                        merged.add(copy);
                    }
                }

                for (List<Integer> run : runs)
                {
                    for (int tick : run)
                    {
                        merged.insert(tick, track.values.get(tick));
                        keys[0]++;
                    }
                }

                merged.sort();
                channel.removeAll();
                channel.copyOver(merged, 0);
            }

            for (String path : this.baked)
            {
                Form form = FormUtils.getForm(root, path);

                if (!(form instanceof IPhysicsForm physics))
                {
                    continue;
                }

                PhysicsForms.setAuthority(form, 1F);

                KeyframeChannel authority = this.channel(FormUtils.getPropertyPath(physics.bbs_physics$getAuthority()));

                if (authority != null)
                {
                    authority.removeAll();
                }
            }
        });

        return new Result(this.ticks, keys[0], this.staged.size());
    }

    /* Bookkeeping */

    /**
     * Puts a key on a track's staging copy. The real track is only touched by {@link #write}: a
     * track made here would be a change to the film outside the one edit the bake is meant to be.
     */
    private void stage(String key, IKeyframeFactory<?> factory, Transform value, boolean simulated)
    {
        Track track = this.staged.computeIfAbsent(key, (k) -> new Track(factory));

        track.values.put(this.local, value);

        if (simulated)
        {
            track.simulated.add(this.local);
        }
    }

    /**
     * The keys a track actually gets: the simulated ticks with one anchor on each side, in runs of
     * consecutive ticks, each run with its flat stretches collapsed to their two ends.
     */
    private static List<List<Integer>> runs(Track track)
    {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> run = null;
        int previous = Integer.MIN_VALUE;

        for (int tick : track.values.keySet())
        {
            boolean kept = track.simulated.contains(tick) || track.simulated.contains(tick - 1) || track.simulated.contains(tick + 1);

            if (!kept)
            {
                continue;
            }

            if (run == null || tick != previous + 1)
            {
                run = new ArrayList<>();
                runs.add(run);
            }

            run.add(tick);
            previous = tick;
        }

        for (List<Integer> each : runs)
        {
            prune(track, each);
        }

        return runs;
    }

    /**
     * Drops the keys inside a flat stretch: a key equal to the last one kept and to the next one
     * says nothing the two of them do not, and a crate that has come to rest would otherwise get
     * a key per tick for the rest of the film. Compared against the last <em>kept</em> key rather
     * than the previous one, so a slow drift cannot slip under the tolerance one tick at a time.
     */
    private static void prune(Track track, List<Integer> run)
    {
        if (run.size() < 3)
        {
            return;
        }

        List<Integer> kept = new ArrayList<>(run.size());
        Transform last = track.values.get(run.get(0));

        kept.add(run.get(0));

        for (int i = 1; i < run.size() - 1; i++)
        {
            Transform value = track.values.get(run.get(i));
            Transform next = track.values.get(run.get(i + 1));

            if (same(last, value) && same(value, next))
            {
                continue;
            }

            kept.add(run.get(i));
            last = value;
        }

        kept.add(run.get(run.size() - 1));
        run.clear();
        run.addAll(kept);
    }

    /** Whether a tick falls inside any of the stretches the bake is writing. */
    private static boolean inside(List<List<Integer>> runs, float tick)
    {
        for (List<Integer> run : runs)
        {
            if (tick >= run.get(0) && tick <= run.get(run.size() - 1))
            {
                return true;
            }
        }

        return false;
    }

    /** Whether two transforms draw the same thing, to a tolerance no viewer can see. */
    private static boolean same(Transform a, Transform b)
    {
        return a.translate.distanceSquared(b.translate) < 1e-8F
            && a.scale.distanceSquared(b.scale) < 1e-8F
            && Math.abs(a.createRotation().dot(b.createRotation())) > 1F - 1e-6F;
    }

    /** The value a bone track holds on the current tick, or null when it holds nothing. */
    private PoseTransform existing(String key)
    {
        KeyframeChannel channel = this.channel(key);

        if (channel == null || channel.isEmpty())
        {
            return null;
        }

        Object value = channel.interpolate(this.local);

        return value instanceof PoseTransform transform ? transform : null;
    }

    /** The replay's own track at this key, or null when it has none — never creates one. */
    private KeyframeChannel channel(String key)
    {
        return this.existing.computeIfAbsent(key, (k) -> this.replay.properties.get(k) instanceof KeyframeChannel channel ? channel : null);
    }

    /** The key of a form's transform track, by the path the form sits at in the replay. */
    private String transformKey(String path)
    {
        Form form = FormUtils.getForm(this.replay.form.get(), path);

        return FormUtils.getPropertyPath(form.transform);
    }

    private static boolean has(RagdollState state, String bone)
    {
        return state != null && state.has(bone);
    }

    private static float weight(RagdollState state, String bone)
    {
        return state == null ? 0F : state.getWeight(bone, 1F);
    }
}
