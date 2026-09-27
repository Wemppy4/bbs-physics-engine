package wemppy.bbs_physics.mixin.client;

import wemppy.bbs_physics.client.ragdoll.DeathRecordingUndo;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.utils.undo.UndoManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIFormUndoHandler.class, remap = false)
public abstract class DeathRecordingUndoMixin implements DeathRecordingUndo
{
    @Shadow protected UndoManager<ValueGroup> undoManager;
    @Shadow public abstract void submitUndo(boolean force);
    @Unique private BaseValue bbs_physics$recordingRoot;

    @Override
    public void bbs_physics$beginRecording(Replay replay)
    {
        this.submitUndo(true);
        this.undoManager.markLastUndoNoMerging();
        // Death events also edit other actors. Capture their common parent before the take,
        // not after the shooter's first samples have already been written.
        BaseValue root = replay.getParent() != null
            ? replay.getParent() : replay;
        root.preNotify(IValueListener.FLAG_BATCH | IValueListener.FLAG_UNMERGEABLE);
        this.bbs_physics$recordingRoot = root;
    }

    @Override
    public void bbs_physics$endRecording()
    {
        if (this.bbs_physics$recordingRoot == null) return;
        this.bbs_physics$recordingRoot = null;
        this.submitUndo(true);
    }

    @Inject(method = "handlePreValues", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$keepPreTakeSnapshot(BaseValue value, int flag, CallbackInfo info)
    {
        if (this.bbs_physics$recordingRoot == null) return;
        for (BaseValue parent = value; parent != null; parent = parent.getParent())
        {
            if (parent == this.bbs_physics$recordingRoot)
            {
                info.cancel();
                return;
            }
        }
    }

    @Inject(method = "submitUndo(Z)V", at = @At("HEAD"), cancellable = true)
    private void bbs_physics$holdTakeUntilFinished(boolean force, CallbackInfo info)
    {
        if (this.bbs_physics$recordingRoot != null) info.cancel();
    }
}
