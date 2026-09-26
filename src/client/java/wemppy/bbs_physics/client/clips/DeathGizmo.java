package wemppy.bbs_physics.client.clips;

import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3f;
import wemppy.bbs_physics.actions.DeathActionClip;
import java.util.EnumSet;

/** World-space translation edits the contact; rotation edits only the incoming direction. */
public final class DeathGizmo extends UIPropTransform
{
    public static final String TARGET_PREFIX = "bbs_physics:death/";
    public static final Gizmo.HandleMask MASK = Gizmo.HandleMask.of(
        EnumSet.of(Gizmo.Op.MOVE, Gizmo.Op.SCREEN, Gizmo.Op.ROTATE, Gizmo.Op.VIEW, Gizmo.Op.TRACKBALL),
        EnumSet.allOf(Axis.class));
    private final Transform scratch = new Transform();
    private final Vector3f originalDirection = new Vector3f();
    private DeathActionClip clip;

    public DeathGizmo(UIFilmPanel panel)
    {
        this.callbacks(() -> this.clip.preNotify(), () ->
        {
            this.clip.point.set(new Point(this.scratch.translate.x, this.scratch.translate.y, this.scratch.translate.z));
            Vector3f direction = this.scratch.createRotation().transform(new Vector3f(this.originalDirection));
            this.clip.direction.set(new Point(direction.x, direction.y, direction.z));
            this.clip.postNotify();
            panel.actionEditor.fillData();
        }, () -> panel.actionEditor.markLastUndoNoMerging());
        this.setVisible(false);
    }

    public static DeathActionClip selected(UIFilmPanel panel)
    {
        return panel.isReplayEditorSelected() && panel.actionEditor.isVisible()
            && panel.actionEditor.getClip() instanceof DeathActionClip death && death.enabled.get() && !death.baked.get()
            ? death : null;
    }

    public FilmTarget select(DeathActionClip clip)
    {
        if (!this.isEditing())
        {
            this.clip = clip;
            this.scratch.identity();
            Point point = clip.point.get();
            Point direction = clip.direction.get();
            this.scratch.translate.set((float) point.x, (float) point.y, (float) point.z);
            this.originalDirection.set((float) direction.x, (float) direction.y, (float) direction.z);
            this.setTransform(this.scratch);
        }
        return new FilmTarget(FilmTarget.Kind.ROOT, TARGET_PREFIX + clip.getId(), TransformSpace.WORLD);
    }

    @Override public TransformSpace getSpace() { return TransformSpace.WORLD; }
}
