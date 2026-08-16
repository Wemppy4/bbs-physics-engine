package mchorse.bbs_physics.client.collision;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.utils.PickedBone;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.presets.UIDataContextMenu;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.BBSPhysicsSettings;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.collision.CollisionIO;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.CollisionShape;
import mchorse.bbs_physics.collision.CollisionSlot;
import mchorse.bbs_physics.collision.FormCollision;
import mchorse.bbs_physics.collision.FormCollisions;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The collision tab: what shape this form is.
 *
 * <p><b>A tab again, and on purpose.</b> Collision and ragdoll were merged into one tab in Э4 and
 * split back apart here, which is not a retraction of that decision but a consequence of a case it
 * did not cover: a character whose body and head both collide, but only the head comes off. Shape
 * and participation turned out to be two questions, and once the ragdoll has its own answer, the two
 * screens stop being the same screen written twice. What is left here is a job done once and
 * forgotten — mark the model up, close the tab — while the physics tab is the one an author returns
 * to every shot.</p>
 *
 * <p>Modelled on the IK tab down to the preset menu, because it is the same job: describing a model
 * bone by bone. A model is marked up per bone; every other form has one slot of its own and no list
 * to choose from, so it simply does not get one.</p>
 */
public class UICollisionFormPanel extends UIFormPanel<Form>
{
    /** One model pixel, in blocks — the step every distance here scrolls by. */
    private static final double PIXEL = 1D / 16D;

    /**
     * The size a bone has to reach to be marked up automatically, in blocks. Kept for the session
     * rather than saved with the form: it is a property of the pass being run, not of the model,
     * and the one thing an author does want is for it to still be there on the next model.
     */
    private static float threshold = 0.25F;

    public UIToggle preview;

    public UIBoneTreeList bones;
    public UISearchList<String> bonesSearch;

    public ModeCirculate mode;
    private final UIElement modeRow;

    public UIStringList shapes;
    public UIIcon addShape;
    public UIIcon removeShape;

    public UICirculate kind;
    public UITrackpad offsetX;
    public UITrackpad offsetY;
    public UITrackpad offsetZ;
    public UITrackpad rotateX;
    public UITrackpad rotateY;
    public UITrackpad rotateZ;
    public UITrackpad sizeX;
    public UITrackpad sizeY;
    public UITrackpad sizeZ;

    public UITrackpad autoThreshold;
    private final UIElement thresholdRow;
    public UIButton autoMark;
    public UIButton fitBounds;
    public UIButton clearAll;

    private final UISection primitives;

    private FormCollision collision = FormCollision.EMPTY;

    /** The slot being edited: a bone name for a model, or the empty key for the form itself. */
    private String slot = FormCollision.SELF;

    private ModelInstance model;
    private String presetGroup = "";
    private boolean syncing;

    public UICollisionFormPanel(UIForm editor)
    {
        super(editor);

        this.preview = new UIToggle(PhysicsKeys.COLLISION_PREVIEW, (b) -> BBSPhysicsSettings.collisionPreview.set(b.getValue()));

        this.bones = new UIBoneTreeList((l) ->
        {
            if (this.model != null)
            {
                this.slot = l.isEmpty() ? FormCollision.SELF : l.get(0);

                PickedBone.set(this.slot);
            }

            this.selectShape(0);
            this.updateLabels();
        })
        {
            @Override
            public void renderListElement(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected)
            {
                super.renderListElement(context, element, i, x, y, hover, selected);

                UICollisionFormPanel.this.renderSlotMark(context, element, y);
            }
        };
        this.bones.background();
        this.bonesSearch = new UISearchList<>(this.bones);
        this.bonesSearch.label(UIKeys.GENERAL_SEARCH);
        this.bonesSearch.h(20 + UIConstants.LIST_ITEM_HEIGHT * 8);
        this.bones.context(() -> new UIDataContextMenu(CollisionPresetManager.INSTANCE, this.presetGroup, this::toPresetData, this::applyPresetData).tooltips("_CopyCollision",
            PhysicsKeys.COLLISION_CONTEXT_COPY,
            PhysicsKeys.COLLISION_CONTEXT_PASTE,
            PhysicsKeys.COLLISION_CONTEXT_RESET,
            PhysicsKeys.COLLISION_CONTEXT_SAVE,
            PhysicsKeys.COLLISION_CONTEXT_NAME
        ));

        this.mode = new ModeCirculate((b) ->
        {
            if (this.syncing)
            {
                return;
            }

            this.setSlot(this.slot().withMode(CollisionMode.values()[b.getValue()]));
            this.updateLabels();
        });
        this.mode.addLabel(PhysicsKeys.COLLISION_MODE_NONE);
        this.mode.addLabel(PhysicsKeys.COLLISION_MODE_AUTO);
        this.mode.addLabel(PhysicsKeys.COLLISION_MODE_SHAPES);
        this.mode.tooltip(PhysicsKeys.COLLISION_MODE_TOOLTIP);
        this.modeRow = UI.labelRow(PhysicsKeys.COLLISION_MODE, this.mode);

        this.shapes = new UIStringList((l) -> this.updateLabels());
        this.shapes.background();
        this.shapes.h(UIConstants.LIST_ITEM_HEIGHT * 4);

        this.addShape = new UIIcon(Icons.ADD, (b) -> this.addShape());
        this.addShape.tooltip(PhysicsKeys.COLLISION_SHAPE_ADD);
        this.removeShape = new UIIcon(Icons.REMOVE, (b) -> this.removeShape());
        this.removeShape.tooltip(PhysicsKeys.COLLISION_SHAPE_REMOVE);

        this.kind = new UICirculate((b) ->
        {
            if (this.syncing)
            {
                return;
            }

            CollisionKind picked = CollisionKind.values()[b.getValue()];

            this.editShape((shape) -> new CollisionShape(picked, shape.offset(), shape.rotation(), shape.size()));
            this.updateLabels();
        });

        for (CollisionKind value : CollisionKind.values())
        {
            this.kind.addLabel(PhysicsKeys.kind(value));
        }

        this.offsetX = this.distance(Colors.RED, (shape, v) -> new CollisionShape(shape.kind(), new Vector3f(v, shape.offset().y, shape.offset().z), shape.rotation(), shape.size()));
        this.offsetY = this.distance(Colors.GREEN, (shape, v) -> new CollisionShape(shape.kind(), new Vector3f(shape.offset().x, v, shape.offset().z), shape.rotation(), shape.size()));
        this.offsetZ = this.distance(Colors.BLUE, (shape, v) -> new CollisionShape(shape.kind(), new Vector3f(shape.offset().x, shape.offset().y, v), shape.rotation(), shape.size()));

        this.rotateX = this.degrees(Colors.RED, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), new Vector3f(v, shape.rotation().y, shape.rotation().z), shape.size()));
        this.rotateY = this.degrees(Colors.GREEN, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), new Vector3f(shape.rotation().x, v, shape.rotation().z), shape.size()));
        this.rotateZ = this.degrees(Colors.BLUE, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), new Vector3f(shape.rotation().x, shape.rotation().y, v), shape.size()));

        this.sizeX = this.size(Colors.RED, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), shape.rotation(), new Vector3f(v, shape.size().y, shape.size().z)));
        this.sizeY = this.size(Colors.GREEN, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), shape.rotation(), new Vector3f(shape.size().x, v, shape.size().z)));
        this.sizeZ = this.size(Colors.BLUE, (shape, v) -> new CollisionShape(shape.kind(), shape.offset(), shape.rotation(), new Vector3f(shape.size().x, shape.size().y, v)));

        this.autoThreshold = new UITrackpad((v) -> threshold = v.floatValue());
        this.autoThreshold.limit(0D, 8D).increment(PIXEL).values(PIXEL, PIXEL, PIXEL * 4);
        this.autoThreshold.tooltip(PhysicsKeys.COLLISION_AUTO_THRESHOLD_TOOLTIP);
        this.thresholdRow = UI.labelRow(PhysicsKeys.COLLISION_AUTO_THRESHOLD, this.autoThreshold);

        this.autoMark = new UIButton(PhysicsKeys.COLLISION_AUTO_MARK, (b) -> this.autoMark());
        this.autoMark.tooltip(PhysicsKeys.COLLISION_AUTO_MARK_TOOLTIP);
        this.fitBounds = new UIButton(PhysicsKeys.COLLISION_FIT, (b) -> this.fitBounds());
        this.fitBounds.tooltip(PhysicsKeys.COLLISION_FIT_TOOLTIP);
        this.clearAll = new UIButton(PhysicsKeys.COLLISION_CLEAR, (b) -> this.clearAll());

        /* Folded, and below the automatic pass: automation is the answer for the common case, hand
         * placement is the correction. One level of folding and no deeper — a panel with sections
         * inside sections is a panel nobody can find anything in. */
        this.primitives = this.section(PhysicsKeys.COLLISION_SHAPES, "collision.shapes", false);

        this.primitives.fields.add(
            this.shapes,
            UI.row(this.addShape, this.removeShape),
            UI.labelRow(PhysicsKeys.COLLISION_SHAPE_KIND, this.kind),
            UI.label(PhysicsKeys.COLLISION_OFFSET),
            UI.row(this.offsetX, this.offsetY, this.offsetZ),
            UI.label(PhysicsKeys.COLLISION_ROTATION),
            UI.row(this.rotateX, this.rotateY, this.rotateZ),
            UI.label(PhysicsKeys.COLLISION_SIZE),
            UI.row(this.sizeX, this.sizeY, this.sizeZ)
        );
    }

    private UITrackpad distance(int color, ShapeEdit edit)
    {
        UITrackpad pad = new UITrackpad(this.callback(edit));

        pad.limit(-64D, 64D).increment(PIXEL).values(PIXEL, PIXEL, PIXEL * 4);
        pad.textbox.setColor(color);

        return pad;
    }

    private UITrackpad degrees(int color, ShapeEdit edit)
    {
        UITrackpad pad = new UITrackpad(this.callback(edit));

        pad.limit(-180D, 180D).increment(5D).values(1D, 0.5D, 5D);
        pad.textbox.setColor(color);

        return pad;
    }

    private UITrackpad size(int color, ShapeEdit edit)
    {
        UITrackpad pad = new UITrackpad(this.callback(edit));

        pad.limit(PIXEL, 64D).increment(PIXEL).values(PIXEL, PIXEL, PIXEL * 4);
        pad.textbox.setColor(color);

        return pad;
    }

    private Consumer<Double> callback(ShapeEdit edit)
    {
        return (v) ->
        {
            if (!this.syncing)
            {
                this.editShape((shape) -> edit.apply(shape, v.floatValue()));
            }
        };
    }

    /* Editing */

    private CollisionSlot slot()
    {
        return this.collision.get(this.slot);
    }

    private void setSlot(CollisionSlot slot)
    {
        this.collision = this.collision.with(this.slot, slot);

        this.commit();
    }

    private void commit()
    {
        if (this.form != null)
        {
            FormCollisions.set(this.form, this.collision);
        }
    }

    private CollisionShape shape()
    {
        List<CollisionShape> shapes = this.slot().shapes();
        int index = this.shapes.getIndex();

        return index < 0 || index >= shapes.size() ? null : shapes.get(index);
    }

    /** Replaces the selected primitive with whatever {@code edit} makes of it. */
    private void editShape(UnaryOperator<CollisionShape> edit)
    {
        List<CollisionShape> shapes = new ArrayList<>(this.slot().shapes());
        int index = this.shapes.getIndex();

        if (index < 0 || index >= shapes.size())
        {
            return;
        }

        shapes.set(index, edit.apply(shapes.get(index)));

        this.setSlot(this.slot().withShapes(shapes));
    }

    private void addShape()
    {
        CollisionSlot slot = this.slot();

        /* A fresh primitive is a quarter-block box: big enough to see in the preview, small enough
         * not to swallow the bone it was just added to. */
        this.setSlot(slot.plus(CollisionShape.of(CollisionKind.BOX, 0.25F)));

        this.selectShape(this.slot().shapes().size() - 1);
        this.updateLabels();
    }

    private void removeShape()
    {
        List<CollisionShape> shapes = new ArrayList<>(this.slot().shapes());
        int index = this.shapes.getIndex();

        if (index < 0 || index >= shapes.size())
        {
            return;
        }

        shapes.remove(index);

        this.setSlot(this.slot().withShapes(shapes));
        this.selectShape(Math.min(index, shapes.size() - 1));
        this.updateLabels();
    }

    private void selectShape(int index)
    {
        this.shapes.setIndex(index);
    }

    /* Automatic markup */

    /**
     * Marks up every bone big enough to be worth colliding with, and leaves the rest alone. The
     * threshold is the whole idea (§13: Unreal does the same, and a rig of sixty bones ends up with
     * ten or fifteen bodies): a hand full of finger bones adds contacts and changes nothing that
     * can be seen.
     *
     * <p>Bones the author placed primitives on by hand are never touched — the pass is a draft to
     * correct, not an answer, and throwing away hand work would make it unusable as a draft.</p>
     */
    private void autoMark()
    {
        IModel model = this.model == null ? null : this.model.model;

        if (model == null)
        {
            return;
        }

        Vector3f scale = this.model.getScale();
        FormCollision collision = this.collision;

        for (String bone : this.bones.getList())
        {
            CollisionSlot slot = collision.get(bone);

            if (slot.mode() == CollisionMode.SHAPES)
            {
                continue;
            }

            boolean big = CollisionShapes.boneSize(model, bone, scale) >= threshold;

            collision = collision.with(bone, big ? CollisionSlot.AUTO : CollisionSlot.NONE);
        }

        this.collision = collision;

        this.commit();
        this.updateLabels();
    }

    /**
     * Fills the form's own slot with one box the size of what it draws — the one-button start for
     * a crate, a barrel, a sign.
     *
     * <p>A block knows its own outline and is measured from it, so a slab gets a slab. Anything
     * else gets the block-sized box every other form is drawn inside, which is a starting point to
     * drag rather than an answer.</p>
     */
    private void fitBounds()
    {
        this.slot = FormCollision.SELF;

        this.setSlot(new CollisionSlot(CollisionMode.SHAPES, List.of(FormBounds.of(this.form))));
        this.selectShape(0);
        this.updateLabels();
    }

    private void clearAll()
    {
        this.collision = FormCollision.EMPTY;

        this.commit();
        this.selectShape(0);
        this.updateLabels();
    }

    /* Presets */

    private MapType toPresetData()
    {
        return CollisionIO.toData(this.collision);
    }

    private void applyPresetData(MapType map)
    {
        this.collision = CollisionIO.fromData(map);

        this.commit();
        this.selectShape(0);
        this.updateLabels();
    }

    /* Syncing the UI */

    @Override
    public void startEdit(Form form)
    {
        super.startEdit(form);

        this.preview.setValue(BBSPhysicsSettings.collisionPreview.get());
        this.collision = FormCollisions.get(form);
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;

        if (this.model != null && this.model.model != null)
        {
            this.presetGroup = this.model.getPoseGroup();

            this.bones.fillBones(this.model.model, this.model.getDisabledBones());
            this.bones.filter(this.bonesSearch.search.getText());

            if (!this.pickBoneInList(PickedBone.get()) && !this.bones.getList().isEmpty())
            {
                this.slot = this.bones.getList().get(0);
                this.bones.setCurrentScroll(this.slot);
            }
        }
        else
        {
            /* Anything that is not a model has one shape: its own. There is nothing to choose
             * between, so it gets no list at all rather than a list with one row greyed out. */
            this.model = null;
            this.presetGroup = form instanceof ModelForm modelForm ? modelForm.model.get() : "";
            this.slot = FormCollision.SELF;
        }

        this.autoThreshold.setValue(threshold);
        this.selectShape(0);
        this.rebuild();
        this.updateLabels();
    }

    @Override
    public boolean pickBoneInList(String bone)
    {
        if (this.model == null || bone == null || bone.isEmpty() || !this.bones.getList().contains(bone))
        {
            return false;
        }

        this.slot = bone;

        PickedBone.set(bone);
        this.bones.setCurrentScroll(bone);
        this.selectShape(0);
        this.updateLabels();

        return true;
    }

    /**
     * Puts the tab together out of the parts this form actually has. Rebuilt rather than hidden:
     * {@code setVisible} stops an element from drawing but leaves it holding its place in the
     * column, so a tab that hid what it did not need would be a tab full of unexplained gaps.
     */
    private void rebuild()
    {
        boolean model = this.model != null;

        this.options.removeAll();

        if (model)
        {
            this.options.add(this.bonesSearch);
        }

        this.options.add(this.modeRow, this.primitives);

        if (model)
        {
            this.options.add(this.thresholdRow, this.autoMark);
        }
        else
        {
            this.options.add(this.fitBounds);
        }

        this.options.add(this.clearAll, this.preview);
        this.options.resize();
    }

    private void updateLabels()
    {
        if (this.mode == null)
        {
            return;
        }

        CollisionSlot slot = this.slot();

        /* "Shapes by hand" is what a slot with shapes in it <em>is</em>, not a mode to select
         * beforehand. Selecting it on an empty slot could not work — a slot with no shapes is
         * dropped on save (see CollisionSlot.isEmpty), so the switch sprang straight back to
         * "Nothing" while the add button sat waiting for that very mode. Adding a shape is the way
         * in and sets the mode itself; the option is offered once it has something to mean.
         *
         * Which options exist has to be settled before the value is read back in: the switch skips
         * a disabled option, so a stale one would send the value somewhere else entirely. */
        boolean hasShapes = !slot.shapes().isEmpty();

        /* Automatic measuring reads a bone's own cubes, and a form that is not a model has
         * none — the option would be a button that quietly does nothing. */
        this.mode.allow(CollisionMode.AUTO.ordinal(), this.model != null);
        this.mode.allow(CollisionMode.SHAPES.ordinal(), hasShapes);

        this.syncing = true;

        try
        {
            this.mode.setValue(slot.mode().ordinal());
            this.fillShapes(slot);

            CollisionShape shape = this.shape();

            this.kind.setValue(shape == null ? 0 : shape.kind().ordinal());
            this.setVector(shape == null ? null : shape.offset(), this.offsetX, this.offsetY, this.offsetZ);
            this.setVector(shape == null ? null : shape.rotation(), this.rotateX, this.rotateY, this.rotateZ);
            this.setVector(shape == null ? null : shape.size(), this.sizeX, this.sizeY, this.sizeZ);
        }
        finally
        {
            this.syncing = false;
        }

        boolean hasShape = this.shape() != null;

        this.shapes.setEnabled(hasShapes);
        this.addShape.setEnabled(this.form != null);
        this.removeShape.setEnabled(hasShape);
        this.kind.setEnabled(hasShape);

        for (UITrackpad pad : List.of(this.offsetX, this.offsetY, this.offsetZ, this.rotateX, this.rotateY, this.rotateZ, this.sizeX, this.sizeY, this.sizeZ))
        {
            pad.setEnabled(hasShape);
        }
    }

    /**
     * Rebuilds the primitive list, keeping the selected row where it was — and only when the rows
     * actually changed. This runs from the list's own selection callback, and a list that clears
     * itself while it is handling a click is asking for trouble.
     */
    private void fillShapes(CollisionSlot slot)
    {
        List<CollisionShape> list = slot.shapes();
        List<String> rows = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++)
        {
            rows.add((i + 1) + ". " + PhysicsKeys.kind(list.get(i).kind()).get());
        }

        if (rows.equals(this.shapes.getList()))
        {
            return;
        }

        int index = this.shapes.getIndex();

        this.shapes.clear();
        this.shapes.add(rows);
        this.shapes.setIndex(Math.min(Math.max(index, 0), rows.size() - 1));
    }

    private void setVector(Vector3f vector, UITrackpad x, UITrackpad y, UITrackpad z)
    {
        x.setValue(vector == null ? 0D : vector.x);
        y.setValue(vector == null ? 0D : vector.y);
        z.setValue(vector == null ? 0D : vector.z);
    }

    /**
     * A dot at the end of a marked-up row. Reading a rig means knowing at a glance which bones
     * carry collision, and a list of sixty names with no marks on it does not tell you that.
     */
    private void renderSlotMark(UIContext context, String element, int y)
    {
        if (this.model == null)
        {
            return;
        }

        CollisionMode mode = this.collision.get(element).mode();

        if (mode == CollisionMode.NONE)
        {
            return;
        }

        int x = this.bones.area.ex() - 9;
        int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2 - 2;
        int color = mode == CollisionMode.AUTO ? Colors.CYAN : Colors.ORANGE;

        context.batcher.box(x, mid, x + 4, mid + 4, Colors.A100 | color);
    }

    @Override
    protected float getDefaultOptionsWidth()
    {
        return 0.3F;
    }

    /** One edit of a primitive by a single number. */
    private interface ShapeEdit
    {
        CollisionShape apply(CollisionShape shape, float value);
    }

    /**
     * The mode switch, with the automatic option offered only where it means something.
     *
     * <p>{@link UICirculate} can disable an option but not re-enable it, and this panel outlives
     * the form it was opened on — switching from a block to a model would otherwise leave the
     * option greyed out forever.</p>
     */
    public static class ModeCirculate extends UICirculate
    {
        public ModeCirculate(Consumer<UICirculate> callback)
        {
            super(callback);
        }

        public void allow(int value, boolean allow)
        {
            this.disabled.remove(value);

            if (!allow)
            {
                this.disable(value);
            }
        }
    }
}
