package wemppy.bbs_physics.client.clips;

import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;
import wemppy.bbs_physics.actions.ImpulseActionClip;

import java.util.EnumSet;

/** Adapts the impulse's world point to the same move handles used by replay placement. */
public class ImpulseGizmo extends UIPropTransform
{
    public static final String TARGET_PREFIX = "bbs_physics:impulse/";
    public static final Gizmo.HandleMask MASK = Gizmo.HandleMask.of(
        EnumSet.of(Gizmo.Op.MOVE, Gizmo.Op.SCREEN), EnumSet.noneOf(Axis.class));

    private final Transform scratch = new Transform();
    private ImpulseActionClip clip;

    public ImpulseGizmo(UIFilmPanel panel)
    {
        this.callbacks(
            () -> this.clip.point.preNotify(),
            () ->
            {
                this.clip.point.set(new Point(this.scratch.translate.x, this.scratch.translate.y, this.scratch.translate.z));
                this.clip.point.postNotify();
                panel.actionEditor.fillData();
            },
            /* The controller is constructed before actionEditor; resolve it when the gesture ends. */
            () -> panel.actionEditor.markLastUndoNoMerging());

        /* This adapter has no rendered fields; GizmoInteraction must pump its hidden gesture. */
        this.setVisible(false);
    }

    public static ImpulseActionClip selected(UIFilmPanel panel)
    {
        return panel.isReplayEditorSelected() && panel.actionEditor.isVisible()
            && panel.actionEditor.getClip() instanceof ImpulseActionClip impulse && impulse.enabled.get()
            ? impulse : null;
    }

    public FilmTarget select(ImpulseActionClip clip)
    {
        if (!this.isEditing())
        {
            this.clip = clip;
            Point point = clip.point.get();
            this.scratch.translate.set((float) point.x, (float) point.y, (float) point.z);
            this.setTransform(this.scratch);
        }

        return new FilmTarget(FilmTarget.Kind.ROOT, TARGET_PREFIX + clip.getId(), TransformSpace.WORLD);
    }

    @Override
    public TransformSpace getSpace()
    {
        return TransformSpace.WORLD;
    }
}
