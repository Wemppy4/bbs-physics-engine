package mchorse.bbs_physics.ragdoll;

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
     * Whether the simulation, rather than the animation, currently owns the pose. Written every
     * tick from the authority handle: below a full 1 the renderer substitutes these frames, at 1
     * it draws the keyframes untouched — the bodies are standing on them anyway, and the animation
     * path is the smoother of the two while it is in charge.
     */
    private boolean active;

    public boolean isActive()
    {
        return this.active;
    }

    public void setActive(boolean active)
    {
        this.active = active;
    }

    /**
     * Records where a bone's pivot frame is this tick.
     *
     * @param teleport whether this position must not be interpolated from the previous one — a
     *                 seek or a restart, where "previous" is a place the bone never travelled from
     */
    public void set(String bone, Vector3f position, Quaternionf rotation, boolean teleport)
    {
        BoneState state = this.bones.computeIfAbsent(bone, (k) -> new BoneState());

        if (teleport)
        {
            state.prevPosition.set(position);
            state.prevRotation.set(rotation);
        }
        else
        {
            state.prevPosition.set(state.position);
            state.prevRotation.set(state.rotation);
        }

        state.position.set(position);
        state.rotation.set(rotation);
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
    }
}
