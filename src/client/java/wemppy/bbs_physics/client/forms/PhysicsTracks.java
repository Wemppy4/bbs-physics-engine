package wemppy.bbs_physics.client.forms;

import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import wemppy.bbs_physics.forms.PhysicsAuthorityValue;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.forms.PhysicsForms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation only: disabled physics keeps its animation capability and saved channels. */
public final class PhysicsTracks
{
    public static final String DISABLED_SECTION = "bbs_physics/disabled/";

    private PhysicsTracks()
    {}

    public static boolean isPhysics(BaseValueBasic property)
    {
        return property instanceof PhysicsKnobValue || property instanceof PhysicsAuthorityValue;
    }

    public static void filter(List<TrackDescriptor> tracks)
    {
        /* BBS 2.8 separates animation capability from field visibility. Filtering the catalog
         * instead of isAnimatable keeps existing channels editable and playable after a toggle. */
        tracks.removeIf(track -> isPhysics(track.property()) && !track.property().isVisible()
            && (track.channel() == null || track.channel().getKeyframes().isEmpty()));
    }

    public static void group(List<UIKeyframeSheet> sheets)
    {
        Map<String, List<UIKeyframeSheet>> groups = new LinkedHashMap<>();
        Map<String, UIKeyframeSheet.Section> sections = new LinkedHashMap<>();

        for (UIKeyframeSheet sheet : sheets)
        {
            if (sheet.descriptor == null || !isPhysics(sheet.property)) continue;

            boolean active = sheet.property.isVisible();
            String path = sheet.descriptor.id().formPath();
            String id = (active ? "bbs_physics/active/" : DISABLED_SECTION) + path;
            sheet.section = sections.computeIfAbsent(id, key ->
            {
                IKey title = active ? PhysicsKeys.PHYSICS_TITLE : L10n.lang("bbs_physics.tracks.disabled");
                /* All-parts mode must still identify whose physics these tracks belong to. */
                IKey label = path.isEmpty() ? title : () -> title.get() + " · " + sheet.form.getDisplayName();
                var type = PhysicsForms.getType(sheet.form);
                var icon = !active ? Icons.PHYSICS : switch (type)
                {
                    case BODY -> Icons.BLOCK;
                    case RAGDOLL -> Icons.POSE;
                    case CHAIN -> Icons.CURVES;
                    default -> Icons.PHYSICS;
                };
                int color = !active ? 0x888888 : switch (type)
                {
                    case RAGDOLL -> 0xb28be0;
                    case CHAIN -> 0x58bec9;
                    default -> 0xe5a45b;
                };
                return new UIKeyframeSheet.Section(key, label, icon, color);
            });
            sheet.icon(null);
            groups.computeIfAbsent(id, key -> new ArrayList<>()).add(sheet);
        }

        /* Sections must be contiguous even when body and model properties interleave. */
        sheets.removeIf(sheet -> sheet.descriptor != null && isPhysics(sheet.property));
        groups.values().forEach(sheets::addAll);
    }
}
