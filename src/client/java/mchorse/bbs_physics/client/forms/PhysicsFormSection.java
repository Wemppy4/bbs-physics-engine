package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.FormSection;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_physics.BBSPhysics;
import mchorse.bbs_physics.cloth.ClothForm;

import java.util.Collections;
import java.util.List;

/**
 * The addon's own section in the form palette, so a sheet of cloth can be picked like any other
 * form.
 *
 * <p>A section of its own rather than an entry in BBS's "Extra": those are BBS's own forms, and a
 * separate heading says plainly where this one comes from — and which mod to blame when it
 * misbehaves.</p>
 */
public class PhysicsFormSection extends FormSection
{
    private FormCategory category;

    public PhysicsFormSection(FormCategories parent)
    {
        super(parent);
    }

    @Override
    public void initiate()
    {
        ClothForm cloth = new ClothForm();

        /* A texture, because the palette draws the form itself as its own preview and a sheet with
         * no texture draws nothing at all — an empty slot where the entry should be. BBS does the
         * same for its picture and block entries; ours is a woven fabric so that the entry reads as
         * cloth at a glance rather than as another picture. */
        cloth.texture.set(new Link(BBSPhysics.ASSETS, "textures/cloth.png"));

        this.category = new FormCategory(PhysicsKeys.CATEGORY, this.parent.visibility.get("bbs_physics"));
        this.category.addForm(cloth);
    }

    @Override
    public List<FormCategory> getCategories()
    {
        return this.category == null ? Collections.emptyList() : Collections.singletonList(this.category);
    }
}
