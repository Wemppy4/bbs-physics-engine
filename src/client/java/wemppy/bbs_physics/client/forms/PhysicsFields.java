package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.utils.UICropOverlayPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Color;
import wemppy.bbs_physics.forms.ITexturedForm;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The rows every physics form panel has in common, built once.
 *
 * <p>Two of them, and both were copied by hand into each panel that needed them: the six controls
 * that describe a texture, and the animation-strength handle. The handle especially — it is one
 * handle with one meaning everywhere (§4), so it has no business being four widgets that happen to
 * agree about their limits.</p>
 */
public final class PhysicsFields
{
    private PhysicsFields()
    {}

    /**
     * The animation-strength handle: 1 is the animation's, 0 is the simulation's, and the fade
     * between them is the whole design (§4).
     */
    public static UITrackpad authority(Consumer<Float> edit)
    {
        UITrackpad trackpad = new UITrackpad((value) -> edit.accept(value.floatValue()));

        trackpad.limit(0D, 1D).increment(0.1D);
        trackpad.tooltip(PhysicsKeys.AUTHORITY_TOOLTIP);

        return trackpad;
    }

    /**
     * What a soft form's skin looks like — the picture form's own six controls, so a texture atlas
     * and the habits that go with it carry straight over.
     */
    public static final class Texture
    {
        public final UIButton pick;
        public final UIColor color;
        public final UIToggle linear;
        public final UIToggle mipmap;
        public final UIToggle shading;
        public final UIButton openCrop;

        /**
         * @param form    the form being edited right now — a supplier, because a panel outlives the
         *                form it was opened on
         * @param context where an overlay opened from here belongs
         */
        public Texture(Supplier<ITexturedForm> form, Supplier<UIContext> context)
        {
            this.pick = new UIButton(UIKeys.FORMS_EDITORS_BILLBOARD_PICK_TEXTURE, (b) ->
            {
                UITexturePicker.open(context.get(), form.get().getTexture().get(), (l) -> form.get().getTexture().set(l));
            });
            this.color = new UIColor((value) -> form.get().getColor().set(Color.rgba(value))).direction(Direction.LEFT).withAlpha();
            this.linear = new UIToggle(UIKeys.TEXTURES_LINEAR, false, (b) -> form.get().getLinear().set(b.getValue()));
            this.mipmap = new UIToggle(UIKeys.TEXTURES_MIPMAP, false, (b) -> form.get().getMipmap().set(b.getValue()));
            this.shading = new UIToggle(UIKeys.FORMS_EDITORS_BILLBOARD_SHADING, false, (b) -> form.get().getShading().set(b.getValue()));
            this.openCrop = new UIButton(UIKeys.FORMS_EDITORS_BILLBOARD_EDIT_CROP, (b) ->
            {
                UIOverlay.addOverlay(context.get(), new UICropOverlayPanel(form.get().getTexture().get(), form.get().getCrop().get()), 0.5F, 0.5F);
            });
        }

        public void addTo(UIElement options)
        {
            options.add(this.pick, this.color, this.linear, this.mipmap, this.shading, this.openCrop);
        }

        /** Loads the form's own values into the controls, when the editor is opened on one. */
        public void sync(ITexturedForm form)
        {
            this.color.setColor(form.getColor().get().getARGBColor());
            this.linear.setValue(form.getLinear().get());
            this.mipmap.setValue(form.getMipmap().get());
            this.shading.setValue(form.getShading().get());
        }
    }
}
