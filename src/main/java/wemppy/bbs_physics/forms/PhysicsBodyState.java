package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the simulation put a form carrying the rigid body modifier, expressed <em>relative to the
 * actor the form hangs on</em> — which is the frame the renderer draws in, so the renderer can
 * apply it without knowing anything about world coordinates or the physics origin.
 *
 * <p>Two ticks are kept so the frame between them can be drawn: physics runs at the film's 20 Hz
 * and the game draws far faster. The same reason {@link wemppy.bbs_physics.client.scene.SceneBody}
 * keeps two — this one is its counterpart on the form side, in the actor's frame instead of the
 * scene's.</p>
 */
public class PhysicsBodyState
{
    private final Vector3f previousPosition = new Vector3f();
    private final Vector3f position = new Vector3f();

    private final Quaternionf previousRotation = new Quaternionf();
    private final Quaternionf rotation = new Quaternionf();

    /**
     * The authority handle as the two recorded ticks stood under it — what decides how much of the
     * drawn frame is the simulation's, exactly as it does for a ragdoll's bones.
     *
     * <p>Read off the recording rather than off the form, for the same reason everything else here
     * is: the form holds the handle for the frame being drawn, at whatever fraction of a tick the
     * frame falls on, while these two transforms belong to whole ticks. Taking all three from the
     * same place is what keeps the blend from sliding against the pair it is blending.</p>
     */
    private float previousAuthority = 1F;
    private float authority = 1F;

    /**
     * Whether the recording actually has this body on the frame being drawn.
     *
     * <p>It does not while the physics of a film is still being worked out ahead of the cursor, and
     * the answer there is deliberate (Р8.1): an unrecorded frame shows <b>plain animation</b> — the
     * form stands exactly on its keyframes, as though it had no physics at all. Blender shows the
     * last computed state instead, which leaves a crate hanging in mid-air detached from everything
     * around it and reads as a bug. Showing the animation is honest about there being no answer yet,
     * and it costs nothing: no substitution, the renderer's ordinary path.</p>
     */
    private boolean simulated;

    /**
     * The frame this form's own transform is applied in, captured by the renderer as the matrix
     * walk passes through — for a nested body that is the whole chain above it (parent forms, the
     * bone it hangs on, the part transform), for a root form it is the identity. In actor-local
     * space, since the walk starts from a bare stack.
     *
     * <p>This is what lets a nested body work at all: the simulation happens in world space, but
     * the renderer replaces a <em>local</em> transform, so the world answer has to be carried back
     * through the frame it will be applied under. Scratch state — the walk overwrites it each
     * pass, and the rig copies it out right after the walk it initiated.</p>
     */
    private final Matrix4f walkParentFrame = new Matrix4f();

    /**
     * @param teleport whether the body jumped rather than moved — a scrub, a restore, or the very
     *                 first tick. Interpolating across a jump would draw the body sliding through
     *                 the scene to catch up
     */
    public void set(Vector3f position, Quaternionf rotation, float authority, boolean teleport)
    {
        this.previousPosition.set(this.position);
        this.previousRotation.set(this.rotation);
        this.previousAuthority = this.authority;

        this.position.set(position);
        this.rotation.set(rotation);
        this.authority = authority;

        if (teleport || !this.simulated)
        {
            /* Also when the body was not simulated a moment ago: the frame before an unrecorded one
             * is not a place this body travelled from, so interpolating out of it would draw the
             * thing sliding in from wherever the animation had left it. */
            this.previousPosition.set(this.position);
            this.previousRotation.set(this.rotation);
            this.previousAuthority = this.authority;
        }

        this.simulated = true;
    }

    /**
     * The simulation's share of the drawn frame: 0 while the animation owns the form outright, 1
     * once it has been let go, and the way across in between — the counterpart of the weight a
     * ragdoll's bones are drawn by, and now the whole of what makes a fade look like a fade.
     *
     * <p>Before this the substitution was all or nothing: below a full handle the body's own answer
     * was drawn outright, and the only thing standing between "in the hand" and "flying" was how
     * hard the pull happened to be holding the body that tick. It held hard almost to the very
     * bottom of the handle (see {@code PhysicsMath.grip}), so a fade drawn over ten frames spent
     * nine of them looking held and the tenth looking thrown.</p>
     */
    public float getWeight(float transition)
    {
        float value = this.previousAuthority + (this.authority - this.previousAuthority) * transition;

        return MathUtils.clamp(1F - value, 0F, 1F);
    }

    /** No recorded answer for this frame — the renderer draws the keyframes instead (Р8.1). */
    public void setUnsimulated()
    {
        this.simulated = false;
    }

    public boolean isSimulated()
    {
        return this.simulated;
    }

    public void captureWalkParentFrame(Matrix4f frame)
    {
        this.walkParentFrame.set(frame);
    }

    public Matrix4f getWalkParentFrame()
    {
        return this.walkParentFrame;
    }

    public Vector3f getPosition(float transition, Vector3f out)
    {
        return this.previousPosition.lerp(this.position, transition, out);
    }

    public Quaternionf getRotation(float transition, Quaternionf out)
    {
        return this.previousRotation.slerp(this.rotation, transition, out);
    }
}
