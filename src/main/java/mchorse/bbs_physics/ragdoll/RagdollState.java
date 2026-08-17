package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the simulation has a ragdoll's bones, as the renderer needs them — the runtime answer, in
 * the model's own space, never saved.
 *
 * <p>Each bone carries its frame at the last two ticks so a drawn frame, which lands between
 * ticks, can be interpolated; the exact counterpart of {@code PhysicsBodyState} for a whole
 * skeleton. The frames are "pivot frames" in the model's flipped group space — the space the
 * render walk composes bone matrices in — so the renderer can turn them into the local rotation
 * and shift it substitutes without knowing where the actor stands.</p>
 *
 * <p>Null on a form until a scene claims it, which is also what "this model is not being
 * simulated" means — in the form editor's preview, for instance.</p>
 */
public class RagdollState
{
    private final Map<String, BoneState> bones = new HashMap<>();

    /**
     * The handle as it stood on the two ticks the drawn frame falls between, so the substitution's
     * weight can be interpolated like everything else here.
     *
     * <p>A full 1 on both means the animation owns the pose outright, which is the resting state of
     * a form nobody has released — hence the initial value.</p>
     */
    private float previousAuthority = 1F;
    private float authority = 1F;

    /** Whether the recording had anything to say about this ragdoll on the frame being drawn. */
    private boolean recorded;

    /**
     * Whether the substitution can have any weight at all on the frame being drawn.
     *
     * <p><b>A shortcut past the walk, not a decision about who owns the pose.</b> That distinction
     * is the whole of the fix to the handle: this used to answer "authority below 1?" and thereby
     * turn a continuous handle into a switch that flipped on the last step of the fade — invisible
     * going 1 → 0, where the bodies are standing exactly on the animation anyway, and a visible
     * jerk coming back, where they are merely near it. Now the weight is {@code 1 - authority} and
     * the walk simply is not worth running when it comes out zero. At a full 1 the animation still
     * draws itself untouched — same result, same cost, but as a consequence rather than a rule.</p>
     */
    public boolean isActive()
    {
        return this.recorded && (this.authority < 1F || this.previousAuthority < 1F);
    }

    /** The simulation's share of the drawn pose: 0 at a full handle, 1 at a released one. */
    public float getWeight(float transition)
    {
        float value = this.previousAuthority + (this.authority - this.previousAuthority) * transition;

        return MathUtils.clamp(1F - value, 0F, 1F);
    }

    public void setRecorded(boolean recorded)
    {
        this.recorded = recorded;
    }

    /**
     * Records the handle for the tick just read.
     *
     * @param teleport whether this tick does not follow the last one, in which case there is no
     *                 previous handle to fade from — the same rule the bone frames follow
     */
    public void setAuthority(float authority, boolean teleport)
    {
        float value = MathUtils.clamp(authority, 0F, 1F);

        this.previousAuthority = teleport ? value : this.authority;
        this.authority = value;
    }

    /**
     * Records where a bone's pivot frame is this tick, and the authority it was simulated under —
     * the bone's own, which since Э5 is not always the form's: a torn-off bone records 0 while the
     * body around it records the handle.
     *
     * @param teleport whether this position must not be interpolated from the previous one — a
     *                 seek or a restart, where "previous" is a place the bone never travelled from
     */
    public void set(String bone, Vector3f position, Quaternionf rotation, float authority, boolean teleport)
    {
        BoneState state = this.bones.computeIfAbsent(bone, (k) -> new BoneState());
        float value = MathUtils.clamp(authority, 0F, 1F);

        if (teleport)
        {
            state.prevPosition.set(position);
            state.prevRotation.set(rotation);
            state.prevAuthority = value;
        }
        else
        {
            state.prevPosition.set(state.position);
            state.prevRotation.set(state.rotation);
            state.prevAuthority = state.authority;
        }

        state.position.set(position);
        state.rotation.set(rotation);
        state.authority = value;
    }

    /**
     * This bone's own share of the drawn pose — {@link #getWeight(float)} answered per bone, which
     * is what lets a torn head be drawn wholly from the simulation while the body around it walks
     * its keyframes at a full handle. Bones the recording never spoke about weigh nothing.
     */
    public float getWeight(String bone, float transition)
    {
        BoneState state = this.bones.get(bone);

        if (state == null)
        {
            return 0F;
        }

        float value = state.prevAuthority + (state.authority - state.prevAuthority) * transition;

        return MathUtils.clamp(1F - value, 0F, 1F);
    }

    public boolean has(String bone)
    {
        return this.bones.containsKey(bone);
    }

    /**
     * The bone's frame at {@code transition} of the way from the previous tick to the current one.
     * Rotation is lerped along the shorter arc and normalized, which for two neighbouring ticks of
     * a physical motion is indistinguishable from the exact interpolation and much cheaper.
     *
     * @return false when the bone is not simulated, in which case the outputs are untouched
     */
    public boolean get(String bone, float transition, Vector3f position, Quaternionf rotation)
    {
        BoneState state = this.bones.get(bone);

        if (state == null)
        {
            return false;
        }

        position.set(state.prevPosition).lerp(state.position, transition);

        float dot = state.prevRotation.dot(state.rotation);

        rotation.set(state.prevRotation);

        if (dot < 0F)
        {
            rotation.set(-rotation.x, -rotation.y, -rotation.z, -rotation.w);
        }

        rotation.set(
            rotation.x + (state.rotation.x - rotation.x) * transition,
            rotation.y + (state.rotation.y - rotation.y) * transition,
            rotation.z + (state.rotation.z - rotation.z) * transition,
            rotation.w + (state.rotation.w - rotation.w) * transition);
        rotation.normalize();

        return true;
    }

    private static class BoneState
    {
        private final Vector3f prevPosition = new Vector3f();
        private final Quaternionf prevRotation = new Quaternionf();
        private final Vector3f position = new Vector3f();
        private final Quaternionf rotation = new Quaternionf();

        /* The bone's own authority on the two ticks the drawn frame falls between — the form-wide
         * handle everywhere, except on a torn bone, where it is 0 from the tear on. */
        private float prevAuthority = 1F;
        private float authority = 1F;
    }
}
