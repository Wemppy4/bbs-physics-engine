package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.sections.FormSection;
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
        this.category = new FormCategory(PhysicsKeys.CATEGORY, this.parent.visibility.get("bbs_physics"));
        this.category.addForm(new ClothForm());
    }

    @Override
    public List<FormCategory> getCategories()
    {
        return this.category == null ? Collections.emptyList() : Collections.singletonList(this.category);
    }
}
