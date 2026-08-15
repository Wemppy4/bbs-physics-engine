package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_physics.collision.CollisionKind;

/** The addon's own UI strings, resolved from its language files. */
public class PhysicsKeys
{
    public static final IKey CATEGORY = L10n.lang("bbs_physics.forms.category");
    public static final IKey BODY_TITLE = L10n.lang("bbs_physics.forms.body.title");

    public static final IKey AUTHORITY = L10n.lang("bbs_physics.forms.body.authority");
    public static final IKey AUTHORITY_TOOLTIP = L10n.lang("bbs_physics.forms.body.authority_tooltip");
    public static final IKey MASS = L10n.lang("bbs_physics.forms.body.mass");
    public static final IKey FRICTION = L10n.lang("bbs_physics.forms.body.friction");
    public static final IKey RESTITUTION = L10n.lang("bbs_physics.forms.body.restitution");
    public static final IKey SHAPE_NONE = L10n.lang("bbs_physics.forms.body.shape_none");
    public static final IKey SHAPE_FROM = L10n.lang("bbs_physics.forms.body.shape_from");

    public static final IKey COLLISION_TITLE = L10n.lang("bbs_physics.forms.collision.title");
    public static final IKey COLLISION_PREVIEW = L10n.lang("bbs_physics.forms.collision.preview");

    public static final IKey COLLISION_MARKUP = L10n.lang("bbs_physics.forms.collision.markup");
    public static final IKey COLLISION_MODE = L10n.lang("bbs_physics.forms.collision.mode");
    public static final IKey COLLISION_MODE_TOOLTIP = L10n.lang("bbs_physics.forms.collision.mode_tooltip");
    public static final IKey COLLISION_MODE_NONE = L10n.lang("bbs_physics.forms.collision.mode_none");
    public static final IKey COLLISION_MODE_AUTO = L10n.lang("bbs_physics.forms.collision.mode_auto");
    public static final IKey COLLISION_MODE_SHAPES = L10n.lang("bbs_physics.forms.collision.mode_shapes");

    public static final IKey COLLISION_SUMMARY_NONE = L10n.lang("bbs_physics.forms.collision.summary_none");
    public static final IKey COLLISION_SUMMARY_AUTO = L10n.lang("bbs_physics.forms.collision.summary_auto");
    public static final IKey COLLISION_SUMMARY_SHAPES = L10n.lang("bbs_physics.forms.collision.summary_shapes");

    public static final IKey COLLISION_SHAPES = L10n.lang("bbs_physics.forms.collision.shapes");
    public static final IKey COLLISION_SHAPE_ADD = L10n.lang("bbs_physics.forms.collision.shape_add");
    public static final IKey COLLISION_SHAPE_REMOVE = L10n.lang("bbs_physics.forms.collision.shape_remove");
    public static final IKey COLLISION_SHAPE_KIND = L10n.lang("bbs_physics.forms.collision.shape_kind");
    public static final IKey COLLISION_OFFSET = L10n.lang("bbs_physics.forms.collision.offset");
    public static final IKey COLLISION_ROTATION = L10n.lang("bbs_physics.forms.collision.rotation");
    public static final IKey COLLISION_SIZE = L10n.lang("bbs_physics.forms.collision.size");

    public static final IKey COLLISION_AUTO = L10n.lang("bbs_physics.forms.collision.auto");
    public static final IKey COLLISION_AUTO_THRESHOLD = L10n.lang("bbs_physics.forms.collision.auto_threshold");
    public static final IKey COLLISION_AUTO_THRESHOLD_TOOLTIP = L10n.lang("bbs_physics.forms.collision.auto_threshold_tooltip");
    public static final IKey COLLISION_AUTO_MARK = L10n.lang("bbs_physics.forms.collision.auto_mark");
    public static final IKey COLLISION_AUTO_MARK_TOOLTIP = L10n.lang("bbs_physics.forms.collision.auto_mark_tooltip");
    public static final IKey COLLISION_FIT = L10n.lang("bbs_physics.forms.collision.fit");
    public static final IKey COLLISION_FIT_TOOLTIP = L10n.lang("bbs_physics.forms.collision.fit_tooltip");
    public static final IKey COLLISION_CLEAR = L10n.lang("bbs_physics.forms.collision.clear");

    public static final IKey COLLISION_CONTEXT_COPY = L10n.lang("bbs_physics.forms.collision.context.copy");
    public static final IKey COLLISION_CONTEXT_PASTE = L10n.lang("bbs_physics.forms.collision.context.paste");
    public static final IKey COLLISION_CONTEXT_RESET = L10n.lang("bbs_physics.forms.collision.context.reset");
    public static final IKey COLLISION_CONTEXT_SAVE = L10n.lang("bbs_physics.forms.collision.context.save");
    public static final IKey COLLISION_CONTEXT_NAME = L10n.lang("bbs_physics.forms.collision.context.name");

    private static final IKey KIND_BOX = L10n.lang("bbs_physics.forms.collision.kind.box");
    private static final IKey KIND_SPHERE = L10n.lang("bbs_physics.forms.collision.kind.sphere");
    private static final IKey KIND_CAPSULE = L10n.lang("bbs_physics.forms.collision.kind.capsule");
    private static final IKey KIND_CYLINDER = L10n.lang("bbs_physics.forms.collision.kind.cylinder");

    public static IKey kind(CollisionKind kind)
    {
        return switch (kind)
        {
            case BOX -> KIND_BOX;
            case SPHERE -> KIND_SPHERE;
            case CAPSULE -> KIND_CAPSULE;
            case CYLINDER -> KIND_CYLINDER;
        };
    }
}
