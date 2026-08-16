package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.PickedBone;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.presets.UIDataContextMenu;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;
import mchorse.bbs_physics.ragdoll.RagdollIO;
import mchorse.bbs_physics.ragdoll.RagdollJoint;
import mchorse.bbs_physics.ragdoll.RagdollJointKind;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The ragdoll modifier's body: which bones fall, and how the selected one bends.
 *
 * <p>The tick in the list is the whole reason the collision tab went back to being its own tab. A
 * marked-up bone is a shape; whether the ragdoll claims it is a separate answer, and an author who
 * wants a body that keeps walking while its head comes off needs to give it. An unticked bone is not
 * excluded from physics — it stays a kinematic body, riding the animation and shoving what it
 * touches — it simply does not fall.</p>
 *
 * <p>A bone with no markup has no tick to give: it has no shape, so there is no body to claim. That
 * is the whole of the old "this bone is not marked up in Collision" warning, said by the absence of
 * a control instead of by a line of grey text.</p>
 *
 * <p>Only the rows the selected joint actually uses are built. A cone has a spread and a twist; a
 * hinge has an axis and two limits; a weld has neither. Showing all of them and greying out two
 * thirds was most of what made this panel unreadable.</p>
 */
public class UIRagdollSection extends UIElement
{
    /** How far from the list's right edge the tick sits — clear of the scrollbar. */
    private static final int TICK_RIGHT = 20;
    private static final int TICK_SIZE = 7;

    public UIBoneTreeList bones;
    public UISearchList<String> bonesSearch;

    public UILabel boneTitle;

    public UICirculate kind;
    private final UIElement kindRow;

    public UITrackpad swing;
    private final UIElement swingRow;
    public UITrackpad twistMin;
    public UITrackpad twistMax;
    private final UIElement twistRow;

    public UICirculate hingeAxis;
    private final UIElement hingeAxisRow;
    public UITrackpad hingeMin;
    public UITrackpad hingeMax;
    private final UIElement hingeRow;

    public UIButton attachTo;
    private final UIElement attachRow;
    public UIButton resetBone;

    /**
     * Told when the rows change, so the column outside can lay itself out again. This block grows
     * and shrinks with the selected joint — a cone and a hinge do not have the same rows — and the
     * scroll view above it has no way of noticing on its own.
     */
    private final Runnable relayout;

    private Form form;
    private FormRagdoll ragdoll = FormRagdoll.EMPTY;
    private String bone = "";
    private ModelInstance model;
    private String presetGroup = "";
    private boolean syncing;

    public UIRagdollSection(Runnable relayout)
    {
        this.relayout = relayout;

        this.column(UIConstants.MARGIN).vertical().stretch();

        this.bones = new UIBoneTreeList((l) ->
        {
            if (this.model != null && !l.isEmpty())
            {
                this.bone = l.get(0);

                PickedBone.set(this.bone);
            }

            this.updateLabels();
        })
        {
            @Override
            public void renderListElement(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected)
            {
                super.renderListElement(context, element, i, x, y, hover, selected);

                UIRagdollSection.this.renderTick(context, element, y);
            }

            @Override
            public boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context) && context.mouseX >= this.area.ex() - TICK_RIGHT - TICK_SIZE && context.mouseX <= this.area.ex() - TICK_RIGHT + TICK_SIZE)
                {
                    int index = this.getIndexAtCursor(context);

                    if (index >= 0 && index < this.getList().size() && UIRagdollSection.this.toggleBone(this.getList().get(index)))
                    {
                        return true;
                    }
                }

                return super.subMouseClicked(context);
            }
        };
        this.bones.background();
        this.bonesSearch = new UISearchList<>(this.bones);
        this.bonesSearch.label(UIKeys.GENERAL_SEARCH);
        this.bonesSearch.h(20 + UIConstants.LIST_ITEM_HEIGHT * 8);
        this.bones.context(() -> new UIDataContextMenu(RagdollPresetManager.INSTANCE, this.presetGroup, this::toPresetData, this::applyPresetData).tooltips("_CopyRagdoll",
            PhysicsKeys.RAGDOLL_CONTEXT_COPY,
            PhysicsKeys.RAGDOLL_CONTEXT_PASTE,
            PhysicsKeys.RAGDOLL_CONTEXT_RESET,
            PhysicsKeys.RAGDOLL_CONTEXT_SAVE,
            PhysicsKeys.RAGDOLL_CONTEXT_NAME
        ));

        this.boneTitle = UI.label(IKey.EMPTY, UIConstants.LIST_ITEM_HEIGHT, Colors.LIGHTER_GRAY);
        this.boneTitle.labelAnchor(0F, 0.5F);

        this.kind = new UICirculate((b) ->
        {
            if (!this.syncing)
            {
                this.editJoint((joint) -> joint.withKind(RagdollJointKind.values()[b.getValue()]));
                this.updateLabels();
            }
        });

        for (RagdollJointKind value : RagdollJointKind.values())
        {
            this.kind.addLabel(PhysicsKeys.jointKind(value));
        }

        this.kind.tooltip(PhysicsKeys.RAGDOLL_KIND_TOOLTIP);
        this.kindRow = UI.labelRow(PhysicsKeys.RAGDOLL_KIND, this.kind);

        this.swing = this.degrees(0D, 180D, (joint, v) -> joint.withSwing(v));
        this.swing.tooltip(PhysicsKeys.RAGDOLL_SWING_TOOLTIP);
        this.swingRow = UI.labelRow(PhysicsKeys.RAGDOLL_SWING, this.swing);

        this.twistMin = this.degrees(-180D, 180D, (joint, v) -> joint.withTwist(v, joint.twistMax()));
        this.twistMax = this.degrees(-180D, 180D, (joint, v) -> joint.withTwist(joint.twistMin(), v));
        this.twistRow = UI.column(UIConstants.MARGIN, 0, UI.label(PhysicsKeys.RAGDOLL_TWIST), UI.row(this.twistMin, this.twistMax));

        this.hingeAxis = new UICirculate((b) ->
        {
            if (!this.syncing)
            {
                this.editJoint((joint) -> joint.withHingeAxis(b.getValue()));
            }
        });
        this.hingeAxis.addLabel(IKey.constant("X"));
        this.hingeAxis.addLabel(IKey.constant("Y"));
        this.hingeAxis.addLabel(IKey.constant("Z"));
        this.hingeAxis.tooltip(PhysicsKeys.RAGDOLL_HINGE_AXIS_TOOLTIP);
        this.hingeAxisRow = UI.labelRow(PhysicsKeys.RAGDOLL_HINGE_AXIS, this.hingeAxis);

        this.hingeMin = this.degrees(-180D, 180D, (joint, v) -> joint.withHinge(v, joint.hingeMax()));
        this.hingeMax = this.degrees(-180D, 180D, (joint, v) -> joint.withHinge(joint.hingeMin(), v));
        this.hingeRow = UI.column(UIConstants.MARGIN, 0, UI.label(PhysicsKeys.RAGDOLL_HINGE), UI.row(this.hingeMin, this.hingeMax));

        this.attachTo = new UIButton(PhysicsKeys.RAGDOLL_ATTACH_AUTO, (b) -> this.openAttachMenu());
        this.attachTo.tooltip(PhysicsKeys.RAGDOLL_ATTACH_TOOLTIP);
        this.attachRow = UI.labelRow(PhysicsKeys.RAGDOLL_ATTACH, this.attachTo);

        this.resetBone = new UIButton(PhysicsKeys.RAGDOLL_RESET_BONE, (b) ->
        {
            this.editJoint((joint) -> RagdollJoint.DEFAULT);
            this.updateLabels();
        });
    }

    private UITrackpad degrees(double min, double max, JointEdit edit)
    {
        UITrackpad pad = new UITrackpad((v) ->
        {
            if (!this.syncing)
            {
                this.editJoint((joint) -> edit.apply(joint, v.floatValue()));
            }
        });

        pad.limit(min, max).increment(5D).values(1D, 0.5D, 5D);

        return pad;
    }

    /* Editing */

    private RagdollJoint joint()
    {
        return this.ragdoll.get(this.bone);
    }

    private void editJoint(UnaryOperator<RagdollJoint> edit)
    {
        if (this.model == null || this.bone.isEmpty())
        {
            return;
        }

        this.setRagdoll(this.ragdoll.with(this.bone, edit.apply(this.joint())));
    }

    /**
     * Takes a bone into the ragdoll or leaves it out. Only a marked-up bone can be claimed — an
     * unmarked one has no shape, so there is no body either way, and a tick on it would be a
     * promise the markup cannot keep.
     *
     * @return whether the click was on a bone that has a tick to give
     */
    private boolean toggleBone(String bone)
    {
        if (this.form == null || this.model == null || !this.isMarked(bone))
        {
            return false;
        }

        this.setRagdoll(this.ragdoll.withPart(bone, !this.ragdoll.isPart(bone)));
        this.updateLabels();

        return true;
    }

    private boolean isMarked(String bone)
    {
        return this.form != null && FormCollisions.get(this.form).get(bone).mode() != CollisionMode.NONE;
    }

    private void setRagdoll(FormRagdoll ragdoll)
    {
        this.ragdoll = ragdoll;

        if (this.form != null)
        {
            FormRagdolls.set(this.form, ragdoll);
        }
    }

    /**
     * The attachment picker: automatic, or any bone that is itself a ragdoll part. A joint to a
     * body that does not exist is not a thing that can be wanted, so bones without markup — and
     * bones the author left out of the ragdoll — are not offered.
     */
    private void openAttachMenu()
    {
        if (this.model == null || this.form == null || this.bone.isEmpty())
        {
            return;
        }

        String current = this.joint().attachTo();

        this.getContext().replaceContextMenu((menu) ->
        {
            menu.action(Icons.REFRESH, PhysicsKeys.RAGDOLL_ATTACH_AUTO, current.isEmpty(), () ->
            {
                this.editJoint((joint) -> joint.withAttachTo(""));
                this.updateLabels();
            });

            for (String bone : this.bones.getList())
            {
                if (bone.equals(this.bone) || !this.isMarked(bone) || !this.ragdoll.isPart(bone))
                {
                    continue;
                }

                menu.action(Icons.LIMB, IKey.constant(bone), bone.equals(current), () ->
                {
                    this.editJoint((joint) -> joint.withAttachTo(bone));
                    this.updateLabels();
                });
            }
        });
    }

    /* Presets */

    private MapType toPresetData()
    {
        return RagdollIO.toData(this.ragdoll);
    }

    private void applyPresetData(MapType map)
    {
        this.setRagdoll(RagdollIO.fromData(map));
        this.updateLabels();
    }

    /* Syncing the UI */

    public void setForm(Form form)
    {
        this.form = form;
        this.ragdoll = FormRagdolls.get(form);
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;

        if (this.model != null && this.model.model != null)
        {
            this.presetGroup = this.model.getPoseGroup();

            this.bones.fillBones(this.model.model, this.model.getDisabledBones());
            this.bones.filter(this.bonesSearch.search.getText());

            if (!this.pickBoneInList(PickedBone.get()) && !this.bones.getList().isEmpty())
            {
                this.bone = this.bones.getList().get(0);
                this.bones.setCurrentScroll(this.bone);
            }
        }
        else
        {
            this.model = null;
            this.presetGroup = "";
            this.bone = "";
        }

        this.updateLabels();
    }

    public boolean pickBoneInList(String bone)
    {
        if (this.model == null || bone == null || bone.isEmpty() || !this.bones.getList().contains(bone))
        {
            return false;
        }

        this.bone = bone;

        PickedBone.set(bone);
        this.bones.setCurrentScroll(bone);
        this.updateLabels();

        return true;
    }

    private void updateLabels()
    {
        if (this.kind == null)
        {
            return;
        }

        RagdollJoint joint = this.joint();

        this.syncing = true;

        try
        {
            this.kind.setValue(joint.kind().ordinal());
            this.swing.setValue(joint.swing());
            this.twistMin.setValue(joint.twistMin());
            this.twistMax.setValue(joint.twistMax());
            this.hingeAxis.setValue(joint.hingeAxis());
            this.hingeMin.setValue(joint.hingeMin());
            this.hingeMax.setValue(joint.hingeMax());
        }
        finally
        {
            this.syncing = false;
        }

        this.attachTo.label = joint.attachTo().isEmpty() ? PhysicsKeys.RAGDOLL_ATTACH_AUTO : IKey.constant(joint.attachTo());
        this.boneTitle.label = this.bone.isEmpty() ? IKey.EMPTY : IKey.constant(this.bone);

        this.rebuild(joint);
    }

    /** Only the rows this joint has. See the class note: the greyed-out two thirds are gone. */
    private void rebuild(RagdollJoint joint)
    {
        boolean editable = this.model != null && !this.bone.isEmpty() && this.isMarked(this.bone) && this.ragdoll.isPart(this.bone);

        this.removeAll();
        this.add(this.bonesSearch);

        if (!editable)
        {
            this.relayout.run();

            return;
        }

        this.add(this.boneTitle, this.kindRow);

        if (joint.kind() == RagdollJointKind.CONE)
        {
            this.add(this.swingRow, this.twistRow);
        }
        else if (joint.kind() == RagdollJointKind.HINGE)
        {
            this.add(this.hingeAxisRow, this.hingeRow);
        }

        if (joint.kind() != RagdollJointKind.FREE)
        {
            this.add(this.attachRow);
        }

        this.add(this.resetBone);
        this.relayout.run();
    }

    /**
     * The tick, and the dot that says whether there is anything to tick.
     *
     * <p>Cyan for a bone measured from its own cubes, orange for one the author shaped by hand —
     * the same colours the collision tab draws, because it is the same fact. An unmarked bone gets
     * neither, which is what makes "this bone cannot fall, it has no shape" visible at a glance
     * across the whole rig.</p>
     */
    private void renderTick(UIContext context, String element, int y)
    {
        if (this.model == null || this.form == null)
        {
            return;
        }

        CollisionMode mode = FormCollisions.get(this.form).get(element).mode();

        if (mode == CollisionMode.NONE)
        {
            return;
        }

        int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2;
        int dot = this.bones.area.ex() - 8;

        context.batcher.box(dot, mid - 2, dot + 4, mid + 2, Colors.A100 | (mode == CollisionMode.AUTO ? Colors.CYAN : Colors.ORANGE));

        int x = this.bones.area.ex() - TICK_RIGHT - TICK_SIZE / 2;
        int top = mid - TICK_SIZE / 2;

        context.batcher.box(x, top, x + TICK_SIZE, top + TICK_SIZE, Colors.A50);

        if (this.ragdoll.isPart(element))
        {
            context.batcher.box(x + 1, top + 1, x + TICK_SIZE - 1, top + TICK_SIZE - 1, Colors.A100 | Colors.WHITE);
        }
    }

    /** One edit of a joint by a single number. */
    private interface JointEdit
    {
        RagdollJoint apply(RagdollJoint joint, float value);
    }
}
