package wemppy.bbs_physics.client.clips;

import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.gizmo.TransformOrientation;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.pose.Transform;
import wemppy.bbs_physics.actions.ImpulseActionClip;

/**
 * Moves the impulse's world point with the film editor's own gizmo.
 *
 * <p>CML only draws a gizmo for the bone the replay keyframe editor has picked, so the selected
 * impulse clip poses as that bone ({@link #TARGET}): the film controller then draws the handles
 * and runs the pick → drag lifecycle as usual, and the mixins place them at the point instead of
 * on a bone. This transform is never mounted in the UI; the controller's HUD pass pumps its drag.</p>
 */
public class ImpulseGizmo extends UIPropTransform
{
    public static final String TARGET = "bbs_physics:impulse";

    private static ImpulseGizmo instance;

    private final UIFilmPanel panel;
    private final Transform scratch = new Transform();
    private ImpulseActionClip clip;

    private ImpulseGizmo(UIFilmPanel panel)
    {
        this.panel = panel;

        this.callbacks(
            () -> this.clip.point.preNotify(),
            () ->
            {
                this.clip.point.set(new Point(this.scratch.translate.x, this.scratch.translate.y, this.scratch.translate.z));
                this.clip.point.postNotify();
                panel.actionEditor.fillData();
            });

        this.tune();
    }

    public static ImpulseGizmo get(UIFilmPanel panel)
    {
        if (instance == null || instance.panel != panel)
        {
            instance = new ImpulseGizmo(panel);
        }

        return instance;
    }

    /** The clip whose point the gizmo shows, or null when none has been selected yet. */
    public static ImpulseActionClip current()
    {
        return instance == null ? null : instance.clip;
    }

    public static boolean isDragging()
    {
        return instance != null && instance.isGizmoEditing();
    }

    public static ImpulseActionClip selected(UIFilmPanel panel)
    {
        return panel != null && panel.actionEditor != null && panel.actionEditor.isVisible()
            && panel.actionEditor.getClip() instanceof ImpulseActionClip impulse && impulse.enabled.get()
            ? impulse : null;
    }

    public static boolean isTarget(String bone)
    {
        return TARGET.equals(bone);
    }

    public Pair<String, TransformOrientation> select(ImpulseActionClip clip)
    {
        if (!this.isGizmoEditing())
        {
            Point point = clip.point.get();

            this.clip = clip;
            this.scratch.translate.set((float) point.x, (float) point.y, (float) point.z);
            this.setTransform(this.scratch);
        }

        return new Pair<>(TARGET, TransformOrientation.GLOBAL);
    }

    /**
     * World axes, blocks, no sign flips. Reapplied after CML's drag preparation, which tunes the
     * transform for whatever replay keyframe happens to be selected at the time.
     */
    public void tune()
    {
        this.setModel(false);
        this.configurePoseRingTuning(true);
        this.setInvertFilmPoseGizmoAxes(false);
        this.translationScale(1F);
        this.setAxisProjectedTranslation(true);
        this.setOrientation(TransformOrientation.GLOBAL);
    }

    @Override
    public void acceptChanges()
    {
        super.acceptChanges();

        /* One gesture is one undo step. */
        this.panel.actionEditor.markLastUndoNoMerging();
    }

    /** A point has no rotation or scale: whatever mode the gizmo is in, its handles are the move ones. */
    public static Gizmo.Mode forceTranslate()
    {
        Gizmo.Mode previous = Gizmo.INSTANCE.getMode();

        Gizmo.INSTANCE.setMode(Gizmo.Mode.TRANSLATE);

        return previous;
    }

    public static void restoreMode(Gizmo.Mode previous)
    {
        Gizmo.INSTANCE.setMode(previous);
    }
}
