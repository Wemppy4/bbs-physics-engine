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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the recording of one form's physics into ordinary keyframes of its replay — Blender's
 * "Bake to Keyframes", the last open item of Э3 (§9.3 of the concept).
 *
 * <p><b>What is written.</b> A rigid body becomes keys on the form's own {@code transform}: one
 * per tick, holding exactly the position and rotation the renderer was substituting. A ragdoll or a
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
    private float local;

    /** Model forms whose bones answered on this tick, with the path each sits at. */
    private final Map<ModelForm, String> models = new LinkedHashMap<>();

    /** Every key worked out so far: a channel's staging copy per track. */
    private final Map<String, KeyframeChannel> staged = new LinkedHashMap<>();

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

        this.stage(this.transformKey(path), KeyframeFactories.TRANSFORM, value);
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

                this.stage(key, KeyframeFactories.POSE_TRANSFORM, value);
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
            for (Map.Entry<String, KeyframeChannel> entry : this.staged.entrySet())
            {
                KeyframeChannel channel = this.replay.properties.getOrCreate(root, entry.getKey());

                if (channel == null)
                {
                    continue;
                }

                /* The whole film is baked, so the whole track is the bake's: what was on it before
                 * is exactly what the bake replaces. */
                channel.removeAll();
                channel.copyOver(entry.getValue(), 0);

                keys[0] += entry.getValue().getKeyframes().size();
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
    private void stage(String key, IKeyframeFactory<?> factory, Transform value)
    {
        this.staged.computeIfAbsent(key, (k) -> new KeyframeChannel(k, factory)).insert(this.local, value);
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
