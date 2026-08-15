package mchorse.bbs_physics.ragdoll;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * The ragdoll's animation-strength handle, visible only while the ragdoll is switched on.
 *
 * <p>Visibility is what makes a form value a timeline track in BBS, so overriding it is how the
 * track appears the moment the author enables the ragdoll and disappears when they turn it off —
 * without a "ragdoll strength" row cluttering every model that never ragdolls. Keyframes already
 * recorded on the track survive a toggle: the channel lives in the replay, not here.</p>
 */
public class RagdollAuthorityValue extends ValueFloat
{
    public RagdollAuthorityValue(String id)
    {
        super(id, 1F, 0F, 1F);
    }

    @Override
    public boolean isVisible()
    {
        return super.isVisible() && this.getParent() instanceof Form form && FormRagdolls.isEnabled(form);
    }
}
