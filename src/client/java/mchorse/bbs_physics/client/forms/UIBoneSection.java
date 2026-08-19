package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.utils.PickedBone;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.bones.UIBoneTreeList;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_physics.collision.CollisionMode;
import mchorse.bbs_physics.collision.FormCollision;
import mchorse.bbs_physics.collision.FormCollisions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A modifier that is described bone by bone: the model's skeleton as a list, a tick in the margin
 * for the bones it claims, and whatever knobs belong to the selected one underneath.
 *
 * <p>Both modifiers that work this way — the ragdoll and the chain — used to carry their own copy of
 * all of it: the list, the search box, the tick's hit test, the dot that says a bone has a shape, and
 * the "am I syncing" flag that keeps a value callback from writing back what it was just handed. The
 * two copies had already started to differ in ways nobody chose.</p>
 *
 * <p><b>Many bones at a time.</b> The list is multi-select, with the gestures BBS's pose bone list
 * already taught — Shift for a run, Ctrl for one more — and a knob turned here writes into every
 * selected bone, not just the one the panel is showing. A rig is described in groups (all the
 * fingers bend the same way, all the hair hangs the same way), and doing that one bone at a time was
 * the same numbers typed twenty times. The values on screen are the <em>first</em> selected bone's:
 * a panel showing an average of twenty bones would be showing a number none of them has.</p>
 *
 * <p>A tick clicked with several bones selected takes them all in — or leaves them all out, matching
 * the row that was clicked rather than flipping each one, so the click has one visible outcome
 * instead of twenty different ones. A tick clicked <em>outside</em> the selection is about that row
 * alone: the author is pointing at it, not at what happens to be highlighted elsewhere.</p>
 *
 * <p><b>Two rules the subclasses inherit rather than rediscover.</b> A value callback may
 * <em>write</em> but must never change the layout: {@code UITrackpad} calls back while it is being
 * dragged, which happens inside the render pass, so rebuilding this element's children from there
 * tears the list the renderer is walking — a crash on the first drag of a slider. And the collision
 * markup is read once per frame here rather than per row: it is stored as one blob on the form, so
 * asking per row meant parsing the whole thing sixty times a second for every visible bone.</p>
 */
public abstract class UIBoneSection extends UIElement
{
    /** How far from the list's right edge the tick sits — clear of the scrollbar. */
    protected static final int TICK_RIGHT = 20;
    protected static final int TICK_SIZE = 7;

    public final UIBoneTreeList bones;
    public final UISearchList<String> bonesSearch;

    /**
     * Told when the rows change, so the column outside can lay itself out again. This block grows
     * and shrinks with what is selected, and the scroll view above it has no way of noticing.
     */
    private final Runnable relayout;

    protected Form form;
    protected ModelInstance model;

    /**
     * The first of the selected bones — the one whose values the knobs show — or empty when there
     * is no model to stand on one. What an edit is <em>written</em> to is {@link #targets}.
     */
    protected String bone = "";

    /**
     * The form's collision markup as of this frame — see the class note on why it is not read per
     * row. Refreshed by {@link #render}, so an edit made in the Collision tab shows up here on the
     * next frame without anything having to tell this panel about it.
     */
    protected FormCollision collision = FormCollision.EMPTY;

    /**
     * True while the panel is writing values into its own widgets, so their callbacks know not to
     * write them back into the form.
     */
    protected boolean syncing;

    protected UIBoneSection(Runnable relayout)
    {
        this.relayout = relayout;

        this.column(UIConstants.MARGIN).vertical().stretch();

        this.bones = new UIPhysicsBoneList((l) ->
        {
            if (this.model != null)
            {
                /* Ctrl-clicking the last selected row leaves nothing selected, and that is an
                 * honest state to be in: the knobs go away rather than describing a bone the
                 * author cannot see highlighted. */
                this.bone = l.isEmpty() ? "" : l.get(0);

                PickedBone.set(this.bone);
            }

            this.onBonePicked();
        })
        {
            @Override
            public void renderListElement(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected)
            {
                super.renderListElement(context, element, i, x, y, hover, selected);

                UIBoneSection.this.renderMargin(context, element, y);
            }

            @Override
            public boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context)
                    && context.mouseX >= this.area.ex() - TICK_RIGHT - TICK_SIZE
                    && context.mouseX <= this.area.ex() - TICK_RIGHT + TICK_SIZE)
                {
                    int index = this.getIndexAtCursor(context);

                    if (index >= 0 && index < this.getList().size() && UIBoneSection.this.toggleTick(this.getList().get(index)))
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
    }

    /* What a subclass fills in */

    /**
     * Takes every one of {@code bones} into the modifier, or leaves them all out. Handed the whole
     * run at once rather than one bone at a time, so a modifier writes itself back onto the form —
     * and lays its panel out again — once per click instead of once per bone.
     */
    protected abstract void setTicked(List<String> bones, boolean ticked);

    /** Whether this modifier claims {@code bone} — what the tick is filled in for. */
    protected abstract boolean isTicked(String bone);

    /** Whether {@code bone} has a tick to give at all — the ragdoll's unmarked bones have none. */
    protected boolean canTick(String bone)
    {
        return true;
    }

    /** The author moved to another bone in the list. */
    protected abstract void onBonePicked();

    /** Whether this modifier shows the collision dot beside the tick, as the ragdoll does. */
    protected boolean showsMarkup()
    {
        return false;
    }

    /* Shared behaviour */

    /**
     * Points this section at a form: its modifier, its model, and its bones.
     *
     * <p>Subclasses read their own modifier off the form first and then call this.</p>
     *
     * @param pick whether to leave the author standing on a bone — the ragdoll edits one at a time
     *             and needs one, the chain modifier edits the set and does not
     */
    protected void setForm(Form form, boolean pick)
    {
        this.form = form;
        this.model = form instanceof ModelForm modelForm ? ModelFormRenderer.getModel(modelForm) : null;
        this.collision = FormCollisions.get(form);

        if (this.model == null || this.model.model == null)
        {
            this.model = null;
            this.bone = "";

            return;
        }

        this.bones.fillBones(this.model.model, this.model.getDisabledBones());
        this.bones.filter(this.bonesSearch.search.getText());

        /* Filling the list drops its selection, so whatever bone was showing is no longer standing
         * on anything — and a name left over from the previous model is a bone this one may not
         * even have. The pick below puts a real one back. */
        this.bone = "";

        if (pick && !this.pickBoneInList(PickedBone.get()) && !this.bones.getList().isEmpty())
        {
            this.bone = this.bones.getList().get(0);
            this.bones.setCurrentScroll(this.bone);
        }
    }

    /**
     * The bones an edit lands on: everything selected in the list, the one showing in the knobs
     * first. Snapshotted because {@code getCurrent} hands back a buffer it reuses — the same guard
     * BBS's own pose editor takes, and for the same reason: an edit can re-enter the list.
     */
    protected List<String> targets()
    {
        List<String> targets = new ArrayList<>(this.bones.getCurrent());

        /* Nothing highlighted, but a bone is showing — the panel was pointed at one from outside
         * (a click in the viewport, a form just opened). That bone is the edit. */
        if (targets.isEmpty() && !this.bone.isEmpty())
        {
            targets.add(this.bone);
        }

        return targets;
    }

    /**
     * The tick was clicked on {@code clicked}: every selected bone follows it, and a bone that has
     * no tick to give is passed over rather than counted as a miss.
     *
     * @return whether the click ticked anything — a click that did not is left to fall through to
     *         the list, which selects the row instead
     */
    private boolean toggleTick(String clicked)
    {
        if (!this.canTick(clicked))
        {
            return false;
        }

        List<String> targets = this.targets();

        if (!targets.contains(clicked))
        {
            targets.clear();
            targets.add(clicked);
        }

        targets.removeIf((bone) -> !this.canTick(bone));

        if (targets.isEmpty())
        {
            return false;
        }

        this.setTicked(targets, !this.isTicked(clicked));

        return true;
    }

    /**
     * A body part clicked in the viewport should land on the bone list on screen, instead of
     * bouncing the author into the pose editor.
     *
     * @return whether this section has that bone to show
     */
    public boolean pickBoneInList(String bone)
    {
        if (this.model == null || bone == null || bone.isEmpty() || !this.bones.getList().contains(bone))
        {
            return false;
        }

        this.bone = bone;

        PickedBone.set(bone);
        this.bones.setCurrentScroll(bone);
        this.onBonePicked();

        return true;
    }

    /** Whether the Collision tab gave {@code bone} a shape at all. */
    protected boolean isMarked(String bone)
    {
        return this.collision.get(bone).mode() != CollisionMode.NONE;
    }

    /** Lays the outside column out again — call after changing which rows exist. */
    protected void relayout()
    {
        this.relayout.run();
    }

    /**
     * A knob that writes one number into the modifier.
     *
     * <p>Writes and stops, deliberately — see the class note: a trackpad's callback arrives inside
     * the render pass, and changing the layout from there takes the game down.</p>
     */
    protected UITrackpad knob(double min, double max, IKey tooltip, Consumer<Float> edit)
    {
        UITrackpad pad = new UITrackpad((v) ->
        {
            if (!this.syncing)
            {
                edit.accept(v.floatValue());
            }
        });

        pad.limit(min, max).increment(0.05D);
        pad.tooltip(tooltip);

        return pad;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.form != null)
        {
            /* Once a frame rather than once a row — see the class note. */
            this.collision = FormCollisions.get(this.form);
        }

        super.render(context);
    }

    /**
     * The tick that says this bone is claimed, and — for the ragdoll — the dot that says whether
     * there is anything to claim.
     *
     * <p>The dot is coloured by how the shape was described: measured from the bone's own cubes, cut
     * to one of its faces, or placed by hand. The same colours the Collision tab draws, because it
     * is the same fact; a bone with no dot cannot fall, and that is visible at a glance across a
     * whole rig instead of being discovered one click at a time.</p>
     */
    private void renderMargin(UIContext context, String element, int y)
    {
        if (this.model == null || this.form == null)
        {
            return;
        }

        CollisionMode mode = this.collision.get(element).mode();

        if (this.showsMarkup())
        {
            if (mode == CollisionMode.NONE)
            {
                /* No shape, so no tick either: a tick on it would be a promise the markup cannot
                 * keep. */
                return;
            }

            int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2;
            int dot = this.bones.area.ex() - 8;

            context.batcher.box(dot, mid - 2, dot + 4, mid + 2, Colors.A100 | PhysicsColors.markup(mode));
        }

        this.renderTick(context, y, this.isTicked(element));
    }

    private void renderTick(UIContext context, int y, boolean ticked)
    {
        int mid = y + UIConstants.LIST_ITEM_HEIGHT / 2;
        int x = this.bones.area.ex() - TICK_RIGHT - TICK_SIZE / 2;
        int top = mid - TICK_SIZE / 2;

        context.batcher.box(x, top, x + TICK_SIZE, top + TICK_SIZE, Colors.A50);

        if (ticked)
        {
            context.batcher.box(x + 1, top + 1, x + TICK_SIZE - 1, top + TICK_SIZE - 1, Colors.A100 | Colors.WHITE);
        }
    }
}
