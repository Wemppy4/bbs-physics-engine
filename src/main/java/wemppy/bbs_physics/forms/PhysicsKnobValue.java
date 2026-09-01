package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

import java.util.function.Predicate;

/**
 * One numeric knob of a physics modifier, as a value of the form itself — which is what makes it a
 * timeline track.
 *
 * <p>BBS turns every <em>visible</em> numeric value of a form into a track in the replay editor,
 * and drives it with keyframes through the value's runtime slot; the animation-strength handle
 * ({@link PhysicsAuthorityValue}) has worked that way from the start. The modifiers' numbers used
 * to live inside an invisible data blob, where no keyframe could reach them. Now each of them is
 * one of these, and the blob keeps only what cannot be animated — flags, bone sets, joints.</p>
 *
 * <p>Hidden while its modifier is not on the form, exactly as the handle hides: a crate that will
 * never fall should not offer twelve physics tracks. Keyframes already recorded survive a toggle —
 * the channel lives in the replay, not here.</p>
 *
 * <p>Read through {@code get()}, which answers with the keyframed value while a track drives it and
 * the stored one otherwise; the rigs already re-read their modifier every tick and push what
 * changed into the live bodies, so a keyframe on any of these takes effect on the tick it lands.</p>
 */
public class PhysicsKnobValue extends ValueFloat
{
    private final float fallback;
    private final Predicate<Form> shown;

    public PhysicsKnobValue(String id, float fallback, float min, float max, Predicate<Form> shown)
    {
        super(id, fallback, min, max);

        this.fallback = fallback;
        this.shown = shown;

        this.slider();
    }

    public float getFallback()
    {
        return this.fallback;
    }

    @Override
    public boolean isVisible()
    {
        return super.isVisible() && this.getParent() instanceof Form form && this.shown.test(form);
    }

    /**
     * Whether this is worth a line in the file: it is while the modifier is on the form, or while
     * the value differs from its default — so a form that never had physics stays byte-identical
     * to one the addon never saw, and a form that lost its modifier keeps the numbers the author
     * had dialled in.
     */
    public boolean isWorthStoring()
    {
        return this.isVisible() || this.getOriginalValue() != this.fallback;
    }
}
