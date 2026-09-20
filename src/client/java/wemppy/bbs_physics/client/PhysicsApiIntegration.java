package wemppy.bbs_physics.client;

import wemppy.bbs_physics.client.clips.PhysicsFilmTool;
import wemppy.bbs_physics.client.structure.PhysicsStructureRenderer;

import mchorse.bbs_mod.api.client.events.*;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.BBSPhysicsSettings;
import wemppy.bbs_physics.client.chain.ChainMute;
import wemppy.bbs_physics.client.ragdoll.RagdollPoseApplier;
import wemppy.bbs_physics.client.scene.*;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsForms;

/** Integration with BBS's public evaluation/editor lifecycle, shared by render and matrix walks. */
public final class PhysicsApiIntegration
{
    public static void register()
    {
        FilmEditEvents.CHANGED.register((film, values, cause) -> FilmScenes.onFilmEdited(film, values));
        FormPoseEvents.TRANSFORM.register(PhysicsApiIntegration::applyBody);
        FormPoseEvents.PARENT_FRAME.register((form, entity, parent, path, transition) ->
        {
            PhysicsBodyState state = PhysicsForms.getState(form);
            if (state != null) state.captureWalkParentFrame(parent);
        });
        FormPoseEvents.MODEL_POSE.register((form, entity, model, transition, base, pass) -> RagdollPoseApplier.apply(form, model, transition));
        FormPoseEvents.CLAIM_CHAIN.register((form, model, bone) -> ChainMute.claims(form, bone.getId(), bone.physicsEnd.get(), null));
        FormPoseEvents.PIVOT_OFFSETS.register(model -> RagdollPoseApplier.isChainStretch());
        FormPoseEvents.ANCHOR.register(SceneActor::releaseAnchor);
        FormPoseEvents.ACTOR_BEFORE.register(context -> SceneActor.prepareRender(
            context.entity, context.entities, context.transition, context.replay == null || !context.relative));
        TimelineEvents.OVERLAY.register((film, context, area, toX) ->
        {
            if (BBSPhysicsSettings.enabled == null || !BBSPhysicsSettings.enabled.get()) return;
            SceneStatus status = FilmScenes.getStatus(film);
            if (status != null) CacheBar.render(context, area, status, tick -> toX.applyAsInt(tick));
        });
        FormPreviewEvents.OVERLAY.register((renderer, context) ->
        {
            UIFormEditor editor = renderer instanceof UIPickableFormRenderer ? renderer.getParent(UIFormEditor.class) : null;
            var entity = renderer instanceof UIPickableFormRenderer pickable ? pickable.getTargetEntity() : renderer.getEntity();
            EditorPreview.render(renderer.form, entity, ((wemppy.bbs_physics.mixin.client.UIModelRendererInvoker) renderer).bbs_physics$createCameraStack(), context, editor);
        });
        PhysicsStructureRenderer.register();
        FilmGizmoEvents.DRAW.register(PhysicsFilmTool::drawGizmo);
    }

    private static void applyBody(Form form, Transform transform, float transition)
    {
        PhysicsBodyState state = PhysicsForms.getState(form);
        if (state == null || !state.isSimulated() || RagdollPoseApplier.isEvaluating()
            || !PoseEvaluation.allows(form, PoseEvaluation.Kind.BODY)) return;
        float weight = state.getWeight(transition);
        if (weight <= 0F) return;
        Vector3f position = state.getPosition(transition, new Vector3f());
        Quaternionf rotation = state.getRotation(transition, new Quaternionf());
        if (weight < 1F)
        {
            transform.translate.lerp(position, weight, position);
            transform.createRotation().slerp(rotation, weight, rotation);
        }
        transform.translate.set(position);
        transform.quat.set(rotation);
        transform.rotationMode = Transform.RotationMode.QUATERNION;
    }
    private PhysicsApiIntegration() {}
}
