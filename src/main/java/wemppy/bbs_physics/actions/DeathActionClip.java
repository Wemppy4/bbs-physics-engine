package wemppy.bbs_physics.actions;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.utils.clips.Clip;

/** A persistent release followed by one impact. Evaluated by the physics timeline, not gameplay. */
public class DeathActionClip extends ActionClip
{
    public final ValuePoint point = new ValuePoint("point", new Point(0, 0, 0));
    public final ValuePoint direction = new ValuePoint("direction", new Point(0, 0, 1));
    public final ValueFloat strength = new ValueFloat("strength", 3F, 0F, Float.MAX_VALUE);
    /** Recording owner, used to replace only this take's generated deaths. Empty for authored clips. */
    public final ValueString sourceReplay = new ValueString("sourceReplay", "");
    public final ValueBoolean baked = new ValueBoolean("baked", false);

    public DeathActionClip()
    {
        this.add(this.point);
        this.add(this.direction);
        this.add(this.strength);
        this.add(this.sourceReplay);
        this.add(this.baked);
        this.sourceReplay.invisible();
    }

    @Override
    protected Clip create() { return new DeathActionClip(); }
}
