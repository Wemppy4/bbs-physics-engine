package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches {@code UIModelRenderer.createCameraStack()} — the stack a form viewport draws its model
 * with, camera view baked into it because the preview is a framebuffer whose global model view is
 * left identity. The collision and ragdoll overlays have to be built against the same one or they
 * land somewhere else entirely.
 *
 * <p>It is protected, and a {@code @Shadow} would not do: a shadow is looked up in the target class
 * itself, and both viewports inherit this rather than declaring it — which is exactly how the first
 * run of the 1.21.11 port died, on <em>«was not located in the target class»</em>. An invoker points
 * at the class that <em>does</em> declare it, and every viewport is one of those.</p>
 */
@Mixin(UIModelRenderer.class)
public interface UIModelRendererInvoker
{
    @Invoker("createCameraStack")
    MatrixStack bbs_physics$createCameraStack();
}
