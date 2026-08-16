package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.HashMap;
import java.util.Map;

/**
 * A folding section with a cross in its header — Blender's modifier panel, where the thing that
 * removes the modifier lives on the modifier itself.
 *
 * <p><b>Why the header needs help.</b> BBS draws a section's header with a label whose {@code render}
 * is replaced outright: it paints the fold arrow and the title and stops there, without the pass
 * that draws an element's children. A cross added to the header is therefore laid out, resized and
 * clickable — and invisible. That is exactly what happened to the previous one, and it is why this
 * class draws the icon itself, after the section has drawn everything else.</p>
 *
 * <p>Clicking needs nothing: BBS offers a click to an element's children before the element itself,
 * so the cross takes it before the header can fold the section under it.</p>
 */
public class UIModifierSection extends UISection
{
    /**
     * Fold state per modifier, for the session. The form editor is rebuilt from scratch on things
     * as small as a viewport bone click, so a section built at its default every time would keep
     * re-folding under the author.
     */
    private static final Map<String, Boolean> FOLDS = new HashMap<>();

    public final UIIcon remove;

    public UIModifierSection(IKey title, String id, Runnable action)
    {
        super(title);

        this.setExpanded(FOLDS.getOrDefault(id, true));
        this.onToggle((s) -> FOLDS.put(id, s.isExpanded()));

        this.remove = new UIIcon(Icons.CLOSE, (b) -> action.run());
        this.remove.tooltip(PhysicsKeys.PHYSICS_REMOVE);

        /* Left of the fold arrow, which the header draws at its own right end. */
        this.remove.relative(this.title).x(1F, -10).y(0.5F).wh(12, 12).anchor(1F, 0.5F);

        this.title.add(this.remove);
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        this.remove.render(context);
    }
}
