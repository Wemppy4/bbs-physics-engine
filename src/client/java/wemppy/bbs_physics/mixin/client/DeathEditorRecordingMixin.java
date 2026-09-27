package wemppy.bbs_physics.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.ui.film.controller.FilmRecordingController;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wemppy.bbs_physics.client.ragdoll.PhysicsDeaths;
import wemppy.bbs_physics.client.ragdoll.DeathRecordingUndo;
import java.util.List;

@Mixin(value = FilmRecordingController.class, remap = false)
public abstract class DeathEditorRecordingMixin
{
    @Shadow @Final private UIFilmController controller;
    @Shadow private boolean recording;
    @Shadow private int recordingCountdown;
    @Unique private Film bbs_physics$film;
    @Unique private int bbs_physics$replay;
    @Unique private int bbs_physics$start;
    @Unique private DeathRecordingUndo bbs_physics$undo;

    @WrapMethod(method = "startRecording")
    private void bbs_physics$beginUndo(List<String> groups, Operation<Void> original)
    {
        var replay = this.controller.panel.replayEditor.getReplay();
        var handler = this.controller.panel.getUndoHandler();
        if (!this.recording && replay != null && handler != null && (groups == null || !groups.contains("outside")))
        {
            this.bbs_physics$undo = (DeathRecordingUndo) handler;
            this.bbs_physics$undo.bbs_physics$beginRecording(replay);
        }
        try { original.call(groups); }
        catch (RuntimeException | Error error) { this.bbs_physics$endUndo(); PhysicsDeaths.prepareEditor(null); throw error; }
        finally { if (!this.recording) this.bbs_physics$endUndo(); }
    }

    @WrapMethod(method = "stopRecording")
    private void bbs_physics$finishUndo(Operation<Void> original)
    {
        try { original.call(); }
        finally { this.bbs_physics$endUndo(); }
    }

    @Unique private void bbs_physics$endUndo()
    {
        var undo = this.bbs_physics$undo;
        this.bbs_physics$undo = null;
        if (undo != null) undo.bbs_physics$endRecording();
    }

    @Inject(method = "startRecording", at = @At("HEAD"))
    private void bbs_physics$begin(List<String> groups, CallbackInfo ci)
    {
        if (this.recording || (groups != null && groups.contains("outside"))) return;
        this.bbs_physics$film = this.controller.panel.getData();
        this.bbs_physics$replay = this.bbs_physics$film.replays.getList().indexOf(this.controller.panel.replayEditor.getReplay());
        this.bbs_physics$start = this.controller.panel.getCursor();
        PhysicsDeaths.prepareEditor(this.bbs_physics$film);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void bbs_physics$tick(RunnerCameraController runner, CallbackInfo ci)
    {
        if (PhysicsDeaths.isTaking() && this.bbs_physics$film != null && this.recording && this.recordingCountdown <= 0 && runner.isRunning())
            PhysicsDeaths.tick(this.bbs_physics$film, this.bbs_physics$replay, this.bbs_physics$start, this.controller.panel.getCursor());
    }

    @Inject(method = "stopRecording", at = @At("HEAD"))
    private void bbs_physics$end(CallbackInfo ci)
    {
        if (this.bbs_physics$film == null) return;
        PhysicsDeaths.end();
        PhysicsDeaths.prepareEditor(null);
        this.bbs_physics$film = null;
    }
}
