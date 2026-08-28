package mchorse.bbs_physics.client.scene;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.List;
import java.util.Set;

/**
 * Decides whether an edit the author just made can change the film's physics — and so whether the
 * recording has to be thrown away and worked out again.
 *
 * <p>Until now every edit under a film's replays did: a renamed replay, a recoloured model, a
 * shadow toggled, a keyframe moved on an actor with no physics in the scene at all — each of them
 * restarted the simulation from the opening frame, and the bar under the timeline went grey for
 * nothing. Blender has the same rule of "any edit invalidates" only for the things the simulation
 * actually reads; this is that list for us.</p>
 *
 * <p><b>The list is of what is harmless, not of what matters.</b> Naming what physics reads would
 * mean keeping that list in step with every rig ever added, and a miss there is the worst kind of
 * bug — a recording that is quietly wrong. Naming what it provably ignores (a label, a colour, a
 * hitbox) means a miss costs one needless re-simulation, which is what happened before anyway.
 * Anything not on the list invalidates.</p>
 *
 * <p>Two things that look cosmetic are deliberately <em>not</em> here. A texture decides the
 * "by pixels" collision plates, so it is geometry to the simulation. And whether a form is visible
 * is read by the markup collector, so it stays an edit that counts.</p>
 */
public final class SceneEdits
{
    /** Ids of a replay's own values that physics never reads. */
    private static final Set<String> REPLAY_COSMETIC = Set.of(
        "category", "label", "name_tag",
        "shadow", "shadow_size", "shadow_follow", "shadow_offset",
        "axes_preview", "axes_preview_bone", "fp");

    /** Ids of a form's values that physics never reads — looked up on a form and on its tracks. */
    private static final Set<String> FORM_COSMETIC = Set.of(
        "color", "lighting", "name", "track_name", "uiScale", "shaderShadow", "additive_color",
        "hitbox", "hitboxWidth", "hitboxHeight", "hitboxSneakMultiplier", "hitboxEyeHeight",
        "hp", "movement_speed", "step_height", "keybind");

    private SceneEdits()
    {}

    /** The film an edited value belongs to, or null when it is not part of a film at all. */
    public static Film filmOf(BaseValue value)
    {
        for (BaseValue current = value; current != null; current = current.getParent())
        {
            if (current instanceof Film film)
            {
                return film;
            }
        }

        return null;
    }

    /**
     * Whether editing {@code value} can change what the simulation computes.
     *
     * @param path the value's path within its film, as segments — {@code replays/3/form/color}
     */
    public static boolean matters(List<String> path)
    {
        int replays = path.indexOf("replays");

        if (replays < 0)
        {
            /* The camera, the film's own settings: none of it is physical. */
            return false;
        }

        /* replays / index / what */
        int what = replays + 2;

        if (what >= path.size())
        {
            /* The list of replays itself — one was added, removed or reordered — or a replay as a
             * whole, which is how a recording overwrites it. */
            return true;
        }

        String property = path.get(what);

        if (REPLAY_COSMETIC.contains(property))
        {
            return false;
        }

        String named;

        if (property.equals("form"))
        {
            /* A form's own value: the deepest id names it (replays/3/form, or deeper for a value
             * inside the tree). The form as a whole — which is how the editor commits — is
             * "form" itself, and that is never cosmetic. */
            named = path.get(path.size() - 1);
        }
        else if (property.equals("properties") && what + 1 < path.size())
        {
            /* A track: the segment after "properties" is the track's id, and a keyframe of it
             * sits deeper still. The id carries the form's path in front (0/1/color), so the
             * property is what follows the last slash. */
            named = path.get(what + 1);
        }
        else
        {
            return true;
        }

        int slash = named.lastIndexOf('/');

        return !FORM_COSMETIC.contains(slash < 0 ? named : named.substring(slash + 1));
    }
}
