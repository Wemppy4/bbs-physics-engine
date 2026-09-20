package wemppy.bbs_physics.client.structure;

import mchorse.bbs_mod.api.client.events.StructureRenderEvents;
import mchorse.bbs_mod.api.client.render.RenderAttachment;
import org.joml.Matrix4f;
import wemppy.bbs_physics.structure.StructureDestruction;

public final class PhysicsStructureRenderer
{
    private static final RenderAttachment<DestructionRender> CACHE = new RenderAttachment<>();

    public static void register()
    {
        StructureRenderEvents.RENDER.register((renderer, form, data, context) ->
        {
            var state = StructureDestruction.state(form);
            if (state == null || !StructureDestruction.isEnabled(form) || !state.structure.equals(form.structure.get()))
            {
                CACHE.remove(renderer);
                return false;
            }
            if (!state.isBroken()) return false;
            String biome = form.biome.get();
            DestructionRender render = CACHE.get(renderer);
            if (render == null || render.state != state || render.source != data || !render.biome.equals(biome))
            {
                render = new DestructionRender(form, state, data, biome);
                CACHE.set(renderer, render);
            }
            var detached = state.detached();
            var remainder = render.remainder(detached);
            if (remainder != null) remainder.renderer.render(context, null);
            Matrix4f matrix = new Matrix4f();
            for (int i = detached.nextSetBit(0); i >= 0; i = detached.nextSetBit(i + 1))
            {
                render.block(i).renderer.render(context, state.transform(i, context.transition, matrix));
            }
            return true;
        });
    }
    private PhysicsStructureRenderer() {}
}
