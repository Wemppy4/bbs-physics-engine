package wemppy.bbs_physics.forms;

import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.misc.ValueVector4f;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

/**
 * A form whose look is one texture stretched over a mesh the simulation owns — cloth and the
 * balloon, and whatever soft form comes next.
 *
 * <p>All six of these are the picture form's own knobs, kept name for name so that a texture atlas
 * built for a picture works here unchanged. What they are <em>not</em> is a description of the
 * shape: a picture takes its proportions from its pixels, while a sheet and a ball are sized in
 * blocks because they are physical objects. Crop picks a region of the image and nothing else.</p>
 *
 * <p>Declared as an interface rather than a shared base class deliberately: the values stay where
 * they are declared, in the order each form has always registered them, so nothing about how a film
 * is stored or how its tracks are listed moves. What this buys is that everything which merely
 * <em>reads</em> them — the renderer, the form panel — can be written once.</p>
 */
public interface ITexturedForm
{
    ValueLink getTexture();

    ValueColor getColor();

    ValueBoolean getLinear();

    ValueBoolean getMipmap();

    ValueBoolean getShading();

    ValueVector4f getCrop();
}
