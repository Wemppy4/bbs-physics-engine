package wemppy.bbs_physics.actions;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.clips.Clip;

/**
 * The tear clip (Э5): "on this frame, this bone comes off" — the head torn away in the moment,
 * with a kick to send it flying.
 *
 * <p>Pure data, like the impulse clip: the physics scene reads it while recording, on the tick it
 * sits at, and answers by switching the bone's joint off, making its body dynamic whatever the
 * animation-strength handle says, and adding the kick to whatever velocity the bone carried. From
 * that tick to the end of the recording the bone belongs to the fall — its recorded authority is 0
 * — while the rest of the character keeps walking its keyframes. A restart of the recording (any
 * edit) puts the bone back on, because before the tear the tear has not happened.</p>
 *
 * <p>The clip acts on the replay it sits in: the bone is looked up among that actor's ragdolls.
 * Bones welded into the torn one travel with it — they were part of its body all along.</p>
 */
public class TearActionClip extends ActionClip
{
    /** The bone that comes off — a ragdoll part of this replay's actor. */
    public final ValueString bone = new ValueString("bone", "");

    /** The kick's speed in blocks per second, added to whatever the bone was already doing. */
    public final ValueFloat strength = new ValueFloat("strength", 5F);

    /** The kick's direction, in world axes. Any length — normalized where it is used. */
    public final ValuePoint direction = new ValuePoint("direction", new Point(0D, 1D, 0D));

    public TearActionClip()
    {
        super();

        this.add(this.bone);
        this.add(this.strength);
        this.add(this.direction);
    }

    @Override
    protected Clip create()
    {
        return new TearActionClip();
    }
}
