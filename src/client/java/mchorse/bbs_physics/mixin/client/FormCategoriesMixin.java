package mchorse.bbs_physics.mixin.client;

import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.sections.FormSection;
import mchorse.bbs_physics.client.forms.PhysicsFormSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds the addon's section to the form palette.
 *
 * <p>This is the one place BBS offers no way in: its sections are a hardcoded list built in
 * {@code setup()}, with the field private and no method to add to it. Since the palette is the
 * only way a user can reach a new form, the list is appended to from here — after {@code setup()}
 * has finished with its own, so nothing is disturbed on the way.</p>
 */
@Mixin(FormCategories.class)
public class FormCategoriesMixin
{
    @Shadow
    private List<FormSection> sections;

    @Inject(method = "setup", at = @At("TAIL"))
    private void bbs_physics$addSection(CallbackInfo info)
    {
        FormCategories categories = (FormCategories) (Object) this;
        PhysicsFormSection section = new PhysicsFormSection(categories);

        /* setup() has already initiated everything it knew about, so this one initiates itself. */
        section.initiate();

        this.sections.add(section);

        categories.markDirty();
    }
}
