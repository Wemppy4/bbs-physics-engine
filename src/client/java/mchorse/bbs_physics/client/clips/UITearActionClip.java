package mchorse.bbs_physics.client.clips;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.actions.UIActionClip;
import mchorse.bbs_mod.ui.film.clips.modules.UIPointModule;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_physics.actions.TearActionClip;
import mchorse.bbs_physics.client.forms.PhysicsKeys;
import mchorse.bbs_physics.collision.FormCollisions;
import mchorse.bbs_physics.ragdoll.FormRagdoll;
import mchorse.bbs_physics.ragdoll.FormRagdolls;

import java.util.ArrayList;
import java.util.List;

/**
 * The tear clip's panel: which bone comes off, and the kick it gets on the way out.
 *
 * <p>The bone is typed or picked — a right-click on the field lists the ragdoll parts of the actor
 * this clip's timeline belongs to, gathered the way the scene gathers them: collision-marked bones
 * of ragdoll-enabled models, minus the ones the author unchecked. Typing stays possible because the
 * list needs the film in a settled state and a name is sometimes quicker anyway.</p>
 */
public class UITearActionClip extends UIActionClip<TearActionClip>
{
    public UITextbox bone;
    public UITrackpad strength;
    public UIPointModule direction;

    public UITearActionClip(TearActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.bone = new UITextbox(120, (name) -> this.editor.editMultiple(this.clip.bone, (bone) -> bone.set(name)));
        this.bone.tooltip(PhysicsKeys.CLIP_BONE_TOOLTIP);
        this.bone.context((menu) ->
        {
            for (String candidate : this.candidates())
            {
                menu.action(Icons.LIMB, IKey.raw(candidate), () ->
                {
                    this.editor.editMultiple(this.clip.bone, (bone) -> bone.set(candidate));
                    this.bone.setText(candidate);
                });
            }
        });

        this.strength = new UITrackpad((v) -> this.editor.editMultiple(this.clip.strength, (strength) -> strength.set(v.floatValue())));
        this.strength.limit(0F).tooltip(PhysicsKeys.CLIP_KICK_TOOLTIP);

        this.direction = new UIPointModule(this.editor, PhysicsKeys.CLIP_DIRECTION);
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(PhysicsKeys.CLIP_BONE, this.bone));
        this.panels.add(this.section(PhysicsKeys.CLIP_KICK, this.strength));
        this.panels.add(this.direction);
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.bone.setText(this.clip.bone.get());
        this.strength.setValue(this.clip.strength.get());
        this.direction.fill(this.clip.direction);
    }

    /**
     * The bones this clip could tear: every ragdoll part of the actor whose action timeline the
     * clip sits on. The replay is found by asking the film which one holds this clip — the clip
     * editor deliberately does not know, and the search is over a handful of lists.
     */
    private List<String> candidates()
    {
        List<String> bones = new ArrayList<>();
        Film film = this.editor.getFilm();

        if (film == null)
        {
            return bones;
        }

        for (Replay replay : film.replays.getList())
        {
            if (replay.actions.get().contains(this.clip))
            {
                collectBones(replay.form.get(), bones);

                break;
            }
        }

        return bones;
    }

    /** Ragdoll parts, gathered the way the scene gathers them: marked bones minus unchecked ones. */
    private static void collectBones(Form form, List<String> bones)
    {
        if (form == null)
        {
            return;
        }

        if (form instanceof ModelForm && FormRagdolls.isEnabled(form))
        {
            FormRagdoll config = FormRagdolls.get(form);

            for (String slot : FormCollisions.get(form).slots().keySet())
            {
                if (!slot.isEmpty() && config.isPart(slot) && !bones.contains(slot))
                {
                    bones.add(slot);
                }
            }
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            collectBones(part.getForm(), bones);
        }
    }
}
