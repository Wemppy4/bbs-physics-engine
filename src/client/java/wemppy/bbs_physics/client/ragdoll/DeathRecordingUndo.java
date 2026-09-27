package wemppy.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.film.replays.Replay;

/** Explicit take boundaries, independent of the editor's per-frame undo flush and timers. */
public interface DeathRecordingUndo
{
    void bbs_physics$beginRecording(Replay replay);
    void bbs_physics$endRecording();
}
