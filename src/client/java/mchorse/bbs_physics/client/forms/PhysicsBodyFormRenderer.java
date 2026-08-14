package mchorse.bbs_physics.client.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_physics.forms.PhysicsBodyForm;
import mchorse.bbs_physics.forms.PhysicsBodyState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Draws a physics body — which means drawing whatever was put inside it, wherever the simulation
 * has ended up putting the body.
 *
 * <p>The whole of that happens in {@link #applyTransforms}: once the body is dynamic, the simulated
 * transform <em>replaces</em> the form's keyframed one, and everything nested inside rides along
 * because body parts are drawn in the frame this leaves on the stack. While the animation still
 * owns the body, this is an ordinary form and the keyframes are used untouched.</p>
 */
public class PhysicsBodyFormRenderer extends FormRenderer<PhysicsBodyForm>
{
    private static final Vector3f POSITION = new Vector3f();
    private static final Quaternionf ROTATION = new Quaternionf();

    private final IEntity entity = new StubEntity();

    public PhysicsBodyFormRenderer(PhysicsBodyForm form)
    {
        super(form);
    }

    /**
     * Hands the scene the frame this form's transform is applied in — everything on the stack
     * above the form: for a nested body the parent forms, the bone it hangs on and the part
     * transform; for a root form the identity. The simulation needs it to translate its world
     * answer into the local transform this renderer will substitute, and the walk is the only
     * place the chain exists.
     */
    @Override
    public void collectMatrices(IEntity entity, MatrixStack stack, MatrixCache matrices, String prefix, float transition)
    {
        PhysicsBodyState state = this.form.state;

        if (state != null)
        {
            state.captureWalkParentFrame(stack.peek().getPositionMatrix());
        }

        super.collectMatrices(entity, stack, matrices, prefix, transition);
    }

    /**
     * Whether the simulation is currently in charge of where this form is. It is not, in the form
     * editor's preview (no scene has claimed the form) or while the animation owns the body.
     */
    private PhysicsBodyState getSimulated()
    {
        PhysicsBodyState state = this.form.state;

        return state == null || this.form.isKinematic() ? null : state;
    }

    @Override
    protected void applyTransforms(MatrixStack stack, boolean origin, float transition)
    {
        PhysicsBodyState state = this.getSimulated();

        if (state == null)
        {
            super.applyTransforms(stack, origin, transition);

            return;
        }

        state.getPosition(transition, POSITION);

        if (origin)
        {
            stack.translate(POSITION.x, POSITION.y, POSITION.z);

            return;
        }

        stack.translate(POSITION.x, POSITION.y, POSITION.z);
        stack.multiply(state.getRotation(transition, ROTATION));

        /* Scale stays the author's: physics has no opinion about how big the thing is drawn, only
         * about where it is and which way up. */
        Vector3f scale = this.form.transform.get().scale;

        stack.scale(scale.x, scale.y, scale.z);
    }

    @Override
    protected void applyTransforms(Matrix4f matrix, float transition)
    {
        PhysicsBodyState state = this.getSimulated();

        if (state == null)
        {
            super.applyTransforms(matrix, transition);

            return;
        }

        Vector3f scale = this.form.transform.get().scale;

        matrix.translate(state.getPosition(transition, POSITION));
        matrix.rotate(state.getRotation(transition, ROTATION));
        matrix.scale(scale.x, scale.y, scale.z);
    }

    /**
     * In the UI a physics body is only its contents — an empty one has nothing to show, since the
     * body itself is a collision shape rather than something visible.
     */
    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        if (this.form.parts.getAll().isEmpty())
        {
            return;
        }

        MatrixStack stack = context.batcher.getContext().getMatrices();
        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        stack.push();

        this.applyTransforms(uiMatrix, context.getTransition());
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(180F));
        stack.peek().getNormalMatrix().getScale(Vectors.EMPTY_3F);
        stack.peek().getNormalMatrix().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        this.renderBodyParts(new FormRenderingContext()
            .set(FormRenderType.ENTITY, this.entity, stack, LightmapTextureManager.pack(15, 15), OverlayTexture.DEFAULT_UV, context.getTransition())
            .inUI());

        stack.pop();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
    }
}
