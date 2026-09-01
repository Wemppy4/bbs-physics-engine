package wemppy.bbs_physics.actions;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.clips.Clip;

/**
 * The impulse clip (Э5): "on this frame, at this point, a push of this strength" — the action
 * timeline's way of saying <em>the blast throws him off his feet</em>, which is the shot all of
 * this exists for.
 *
 * <p>Pure data. The physics scene reads the clip itself while it records (see
 * {@code FilmScene}), on the exact tick the clip sits at, which is what keeps the recording
 * deterministic: a re-recording replays the same push on the same tick, and the clip needs no
 * hand in either the server's action pass or the client's.</p>
 *
 * <p>Radial by default — an explosion at the point, everything inside the radius flying away from
 * it, fading with distance. Switched off, it is a shove: everything inside the radius is pushed
 * along the direction vector instead. The strength is the velocity change at the epicentre in
 * blocks per second, deliberately not a force: an author aims how fast things fly, and the same
 * number reads the same on a bottle and on a ragdoll.</p>
 *
 * <p>Only what physics owns takes the push — released bodies, falling ragdoll parts, cloth,
 * balloons. A body the animation holds (the handle at 1) ignores it: kicking the keyframes would
 * move nothing and lie about it.</p>
 */
public class ImpulseActionClip extends ActionClip
{
    /** Where the push happens, in world coordinates. */
    public final ValuePoint point = new ValuePoint("point", new Point(0D, 0D, 0D));

    /** Radial (an explosion away from the point) or directed (a shove along {@link #direction}). */
    public final ValueBoolean radial = new ValueBoolean("radial", true);

    /** The velocity change at the epicentre, in blocks per second. */
    public final ValueFloat strength = new ValueFloat("strength", 10F);

    /** How far the push reaches, in blocks; the strength fades linearly to nothing at the edge. */
    public final ValueFloat radius = new ValueFloat("radius", 3F);

    /** The way a directed push shoves, in world axes. Any length — normalized where it is used. */
    public final ValuePoint direction = new ValuePoint("direction", new Point(0D, 1D, 0D));

    public ImpulseActionClip()
    {
        super();

        this.add(this.point);
        this.add(this.radial);
        this.add(this.strength);
        this.add(this.radius);
        this.add(this.direction);
    }

    @Override
    protected Clip create()
    {
        return new ImpulseActionClip();
    }
}
