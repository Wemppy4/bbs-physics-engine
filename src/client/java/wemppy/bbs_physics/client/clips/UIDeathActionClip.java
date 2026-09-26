package wemppy.bbs_physics.client.clips;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.film.clips.modules.UIPointModule;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import wemppy.bbs_physics.actions.DeathActionClip;

public class UIDeathActionClip extends UIActionClip<DeathActionClip>
{
    private UITrackpad strength;
    private UIPointModule point;
    private UIPointModule direction;
    private mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle baked;

    public UIDeathActionClip(DeathActionClip clip, IUIClipsDelegate editor) { super(clip, editor); }

    @Override protected void registerUI()
    {
        super.registerUI();
        this.strength = new UITrackpad(v -> this.editor.editMultiple(this.clip.strength, s -> s.set(v.floatValue())));
        this.strength.limit(0);
        this.point = new UIPointModule(this.editor, L10n.lang("bbs_physics.death.point"));
        this.direction = new UIPointModule(this.editor, L10n.lang("bbs_physics.death.direction"));
        this.baked = new mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle(L10n.lang("bbs_physics.death.baked"),
            b -> this.editor.editMultiple(this.clip.baked, v -> v.set(b.getValue())));
    }

    @Override protected void registerPanels()
    {
        super.registerPanels();
        this.panels.remove(this.frequency.getParent(mchorse.bbs_mod.ui.framework.elements.UISection.class));
        this.panels.add(this.section(L10n.lang("bbs_physics.death.strength"), this.strength), this.point, this.direction, this.baked);
    }

    @Override public void fillData()
    {
        super.fillData();
        this.strength.setValue(this.clip.strength.get());
        this.point.fill(this.clip.point);
        this.direction.fill(this.clip.direction);
        this.baked.setValue(this.clip.baked.get());
    }
}
