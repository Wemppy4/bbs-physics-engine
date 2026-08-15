package mchorse.bbs_physics.collision;

import mchorse.bbs_mod.settings.values.core.ValueData;

/**
 * The collision markup carried by every {@code Form}, reached through the mixin that puts it
 * there. Implemented by {@code mchorse.bbs_physics.mixin.FormMixin}; nothing else implements it.
 */
public interface IFormCollision
{
    ValueData bbs_physics$getCollision();
}
