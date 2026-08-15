package mchorse.bbs_physics.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * The animation-strength handle, visible only while the form has physics on it.
 *
 * <p>Visibility is what makes a form value a timeline track in BBS, so overriding it is how the
 * track appears the moment the author adds a body or a ragdoll and disappears when they remove it —
 * without an "animation strength" row cluttering every block that will never fall. Keyframes
 * already recorded survive a toggle: the channel lives in the replay, not here.</p>
 *
 * <p>One handle covers both modifiers (§4). A passive body still shows the track — hiding it would
 * be the harsher rule, since flipping back to active is one click and the author's keyframes should
 * be waiting where they left them.</p>
 */
public class PhysicsAuthorityValue extends ValueFloat
{
    public PhysicsAuthorityValue(String id)
    {
        super(id, 1F, 0F, 1F);
    }

    @Override
    public boolean isVisible()
    {
        return super.isVisible() && this.getParent() instanceof Form form && PhysicsForms.isSimulated(form);
    }
}
