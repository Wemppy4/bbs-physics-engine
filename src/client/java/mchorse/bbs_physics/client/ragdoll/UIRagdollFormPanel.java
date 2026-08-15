package mchorse.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
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
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The ragdoll tab: whether this model ragdolls, and how each bone is jointed.
 *
 * <p>Deliberately <em>not</em> a second markup of the model. Which bones become bodies is the
 * collision tab's decision — a bone marked there is a ragdoll part, an unmarked one does not
 * exist to physics — and the list here shows that with the same dot the collision tab draws. This
 * tab only answers "and how does it bend": a soft cone unless said otherwise, a hinge for knees
 * and elbows, a weld for bones that should move as one piece.</p>
 *
 * <p>Modelled on the collision tab down to the preset menu, because they are halves of the same
 * job. The presets live beside the model, keyed to its rig — set the knees up once, apply it to
 * every character that shares the skeleton.</p>
 */
public class UIRagdollFormPanel extends UIFormPanel<Form>
{
    public UIToggle enabled;

    public UIBoneTreeList bones;
    public UISearchList<String> bonesSearch;

    public UICirculate kind;
    public UILabel summary;

    public UITrackpad swing;
    public UITrackpad twistMin;
    public UITrackpad twistMax;

    public UICirculate hingeAxis;
    public UITrackpad hingeMin;
    public UITrackpad hingeMax;

    public UIButton attachTo;
    public UIButton resetBone;

    private FormRagdoll ragdoll = FormRagdoll.EMPTY;
    private String bone = "";
    private ModelInstance model;
    private String presetGroup = "";
    private boolean syncing;

    public UIRagdollFormPanel(UIForm editor)
    {
        super(editor);

        this.enabled = new UIToggle(PhysicsKeys.RAGDOLL_ENABLED, (b) ->
        {
            if (!this.syncing)
            {
                this.setRagdoll(this.ragdoll.withEnabled(b.getValue()));
                this.updateLabels();
            }
        });
        this.enabled.tooltip(PhysicsKeys.RAGDOLL_ENABLED_TOOLTIP);

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

                UIRagdollFormPanel.this.renderBoneMark(context, element, y);
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

        this.summary = UI.label(IKey.EMPTY, UIConstants.LIST_ITEM_HEIGHT, Colors.LIGHTER_GRAY);
        this.summary.labelAnchor(0F, 0.5F);

        this.swing = this.degrees(0D, 180D, (joint, v) -> joint.withSwing(v));
        this.swing.tooltip(PhysicsKeys.RAGDOLL_SWING_TOOLTIP);
        this.twistMin = this.degrees(-180D, 180D, (joint, v) -> joint.withTwist(v, joint.twistMax()));
        this.twistMax = this.degrees(-180D, 180D, (joint, v) -> joint.withTwist(joint.twistMin(), v));

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

        this.hingeMin = this.degrees(-180D, 180D, (joint, v) -> joint.withHinge(v, joint.hingeMax()));
        this.hingeMax = this.degrees(-180D, 180D, (joint, v) -> joint.withHinge(joint.hingeMin(), v));

        this.attachTo = new UIButton(PhysicsKeys.RAGDOLL_ATTACH_AUTO, (b) -> this.openAttachMenu());
        this.attachTo.tooltip(PhysicsKeys.RAGDOLL_ATTACH_TOOLTIP);

        this.resetBone = new UIButton(PhysicsKeys.RAGDOLL_RESET_BONE, (b) ->
        {
            this.editJoint((joint) -> RagdollJoint.DEFAULT);
            this.updateLabels();
        });

        UISection joints = this.section(PhysicsKeys.RAGDOLL_JOINT, "ragdoll.joint", true);

        joints.fields.add(
            this.summary,
            UI.labelRow(PhysicsKeys.RAGDOLL_KIND, this.kind),
            UI.label(PhysicsKeys.RAGDOLL_SWING),
            this.swing,
            UI.label(PhysicsKeys.RAGDOLL_TWIST),
            UI.row(this.twistMin, this.twistMax),
            UI.labelRow(PhysicsKeys.RAGDOLL_HINGE_AXIS, this.hingeAxis),
            UI.label(PhysicsKeys.RAGDOLL_HINGE),
            UI.row(this.hingeMin, this.hingeMax),
            UI.label(PhysicsKeys.RAGDOLL_ATTACH),
            this.attachTo,
            this.resetBone
        );

        this.options.add(
            this.enabled,
            this.bonesSearch,
            joints
        );
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

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
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

    private void setRagdoll(FormRagdoll ragdoll)
    {
        this.ragdoll = ragdoll;

        if (this.form != null)
        {
            FormRagdolls.set(this.form, ragdoll);
        }
    }

    /**
     * The attachment picker: automatic, or any marked-up bone. Only marked bones are offered —
     * an unmarked one has no body, and a joint to a body that does not exist is not a thing that
     * can be wanted.
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
                if (bone.equals(this.bone) || FormCollisions.get(this.form).get(bone).mode() == CollisionMode.NONE)
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

    @Override
    public void startEdit(Form form)
    {
        super.startEdit(form);

        this.ragdoll = FormRagdolls.get(form);
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;

        if (this.model != null && this.model.model != null)
        {
            this.presetGroup = this.model.getPoseGroup();

            this.bones.fillBones(this.model.model, this.model.getDisabledBones());
            this.bones.filter(this.bonesSearch.search.getText());
            this.bones.setEnabled(true);
            this.bonesSearch.setEnabled(true);

            if (!this.pickBoneInList(PickedBone.get()) && !this.bones.getList().isEmpty())
            {
                this.bone = this.bones.getList().get(0);
                this.bones.setCurrentScroll(this.bone);
            }
        }
        else
        {
            /* A ragdoll is a property of having a skeleton; anything else sees the tab disabled
             * rather than absent, so it reads the same everywhere. */
            this.model = null;
            this.presetGroup = "";
            this.bone = "";

            this.bones.fillFlat(List.of(form.getDisplayName()));
            this.bones.setIndex(0);
            this.bones.setEnabled(false);
            this.bonesSearch.setEnabled(false);
        }

        this.updateLabels();
        this.options.resize();
    }

    @Override
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

        boolean isModel = this.model != null;
        RagdollJoint joint = this.joint();

        this.syncing = true;

        try
        {
            this.enabled.setValue(this.ragdoll.enabled());
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

        boolean marked = isModel && this.form != null && FormCollisions.get(this.form).get(this.bone).mode() != CollisionMode.NONE;

        this.summary.label = !isModel
            ? PhysicsKeys.RAGDOLL_ONLY_MODELS
            : (marked ? PhysicsKeys.RAGDOLL_SUMMARY_PART : PhysicsKeys.RAGDOLL_SUMMARY_UNMARKED);
        this.summary.color(marked ? Colors.WHITE : Colors.LIGHTER_GRAY);

        boolean editable = isModel && this.ragdoll.enabled() && !this.bone.isEmpty();
        boolean cone = joint.kind() == RagdollJointKind.CONE;
        boolean hinge = joint.kind() == RagdollJointKind.HINGE;

        this.enabled.setEnabled(isModel);
        this.kind.setEnabled(editable);
        this.swing.setEnabled(editable && cone);
        this.twistMin.setEnabled(editable && cone);
        this.twistMax.setEnabled(editable && cone);
        this.hingeAxis.setEnabled(editable && hinge);
        this.hingeMin.setEnabled(editable && hinge);
        this.hingeMax.setEnabled(editable && hinge);
        this.attachTo.label = joint.attachTo().isEmpty() ? PhysicsKeys.RAGDOLL_ATTACH_AUTO : IKey.constant(joint.attachTo());
        this.attachTo.setEnabled(editable);
        this.resetBone.setEnabled(editable);
    }

    /**
     * The same dot the collision tab draws, because it answers the same question here: which rows
     * of this list are real to physics. A bone with no collision markup gets no body, however its
     * joint is configured.
     */
    private void renderBoneMark(UIContext context, String element, int y)
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

        int x = this.bones.area.ex() - 9;
        int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2 - 2;

        boolean custom = this.ragdoll.joints().containsKey(element);

        context.batcher.box(x, mid, x + 4, mid + 4, Colors.A100 | (custom ? Colors.ORANGE : Colors.CYAN));
    }

    /** One edit of a joint by a single number. */
    private interface JointEdit
    {
        RagdollJoint apply(RagdollJoint joint, float value);
    }
}
