package mchorse.bbs_physics.client.forms;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_physics.collision.CollisionKind;
import mchorse.bbs_physics.ragdoll.RagdollJointKind;

/** The addon's own UI strings, resolved from its language files. */
public class PhysicsKeys
{
    public static final IKey CATEGORY = L10n.lang("bbs_physics.forms.category");
    public static final IKey BODY_TITLE = L10n.lang("bbs_physics.forms.body.title");

    public static final IKey PHYSICS_TITLE = L10n.lang("bbs_physics.forms.physics.title");
    public static final IKey PHYSICS_ADD_BODY = L10n.lang("bbs_physics.forms.physics.add_body");
    public static final IKey PHYSICS_ADD_BODY_TOOLTIP = L10n.lang("bbs_physics.forms.physics.add_body_tooltip");
    public static final IKey PHYSICS_ADD_RAGDOLL = L10n.lang("bbs_physics.forms.physics.add_ragdoll");
    public static final IKey PHYSICS_ADD_RAGDOLL_TOOLTIP = L10n.lang("bbs_physics.forms.physics.add_ragdoll_tooltip");
    public static final IKey PHYSICS_ADD_OBSTACLE = L10n.lang("bbs_physics.forms.physics.add_obstacle");
    public static final IKey PHYSICS_ADD_CLOTH = L10n.lang("bbs_physics.forms.physics.add_cloth");
    public static final IKey PHYSICS_LATER = L10n.lang("bbs_physics.forms.physics.later");
    public static final IKey PHYSICS_REMOVE = L10n.lang("bbs_physics.forms.physics.remove");
    public static final IKey PHYSICS_RAGDOLL_HINT = L10n.lang("bbs_physics.forms.physics.ragdoll_hint");
    public static final IKey PHYSICS_NEXT_NONE = L10n.lang("bbs_physics.forms.physics.next_none");
    public static final IKey PHYSICS_NEXT_UNMARKED = L10n.lang("bbs_physics.forms.physics.next_unmarked");
    public static final IKey PHYSICS_NEXT_READY = L10n.lang("bbs_physics.forms.physics.next_ready");

    public static final IKey BODY_MASS_TOOLTIP = L10n.lang("bbs_physics.forms.body.mass_tooltip");
    public static final IKey BODY_TYPE = L10n.lang("bbs_physics.forms.body.type");
    public static final IKey BODY_TYPE_ACTIVE = L10n.lang("bbs_physics.forms.body.type_active");
    public static final IKey BODY_TYPE_PASSIVE = L10n.lang("bbs_physics.forms.body.type_passive");
    public static final IKey BODY_TYPE_TOOLTIP = L10n.lang("bbs_physics.forms.body.type_tooltip");

    public static final IKey SHAPE = L10n.lang("bbs_physics.forms.body.shape");
    public static final IKey SHAPE_NOTHING = L10n.lang("bbs_physics.forms.body.shape_nothing");
    public static final IKey SHAPE_AUTO = L10n.lang("bbs_physics.forms.body.shape_auto");
    public static final IKey SHAPE_TOOLTIP = L10n.lang("bbs_physics.forms.body.shape_tooltip");

    public static final IKey MATERIAL_PICK = L10n.lang("bbs_physics.forms.body.material_pick");
    public static final IKey MATERIAL_TOOLTIP = L10n.lang("bbs_physics.forms.body.material_tooltip");
    public static final IKey MATERIAL_CORK = L10n.lang("bbs_physics.forms.body.material.cork");
    public static final IKey MATERIAL_WOOD = L10n.lang("bbs_physics.forms.body.material.wood");
    public static final IKey MATERIAL_WATER = L10n.lang("bbs_physics.forms.body.material.water");
    public static final IKey MATERIAL_RUBBER = L10n.lang("bbs_physics.forms.body.material.rubber");
    public static final IKey MATERIAL_CONCRETE = L10n.lang("bbs_physics.forms.body.material.concrete");
    public static final IKey MATERIAL_STONE = L10n.lang("bbs_physics.forms.body.material.stone");
    public static final IKey MATERIAL_IRON = L10n.lang("bbs_physics.forms.body.material.iron");
    public static final IKey MATERIAL_GOLD = L10n.lang("bbs_physics.forms.body.material.gold");

    public static final IKey AUTHORITY_ENDS = L10n.lang("bbs_physics.forms.body.authority_ends");

    public static final IKey PHYSICS_MARKUP = L10n.lang("bbs_physics.forms.physics.markup");
    public static final IKey PHYSICS_MARKUP_NONE = L10n.lang("bbs_physics.forms.physics.markup_none");
    public static final IKey PHYSICS_REMARK = L10n.lang("bbs_physics.forms.physics.remark");
    public static final IKey PHYSICS_REMARK_TOOLTIP = L10n.lang("bbs_physics.forms.physics.remark_tooltip");
    public static final IKey PHYSICS_MANUAL = L10n.lang("bbs_physics.forms.physics.manual");
    public static final IKey PHYSICS_BY_BONES = L10n.lang("bbs_physics.forms.physics.by_bones");

    public static final IKey AUTHORITY = L10n.lang("bbs_physics.forms.body.authority");
    public static final IKey AUTHORITY_TOOLTIP = L10n.lang("bbs_physics.forms.body.authority_tooltip");
    public static final IKey MASS = L10n.lang("bbs_physics.forms.body.mass");
    public static final IKey FRICTION = L10n.lang("bbs_physics.forms.body.friction");
    public static final IKey RESTITUTION = L10n.lang("bbs_physics.forms.body.restitution");

    public static final IKey SCENE_TITLE = L10n.lang("bbs_physics.scene.title");
    public static final IKey SCENE_NONE = L10n.lang("bbs_physics.scene.none");
    public static final IKey SCENE_STATUS = L10n.lang("bbs_physics.scene.status");
    public static final IKey SCENE_CACHE = L10n.lang("bbs_physics.scene.cache");
    public static final IKey SCENE_CACHE_EMPTY = L10n.lang("bbs_physics.scene.cache_empty");
    public static final IKey SCENE_COMPUTE = L10n.lang("bbs_physics.scene.compute");
    public static final IKey SCENE_COMPUTE_TOOLTIP = L10n.lang("bbs_physics.scene.compute_tooltip");
    public static final IKey SCENE_RESET = L10n.lang("bbs_physics.scene.reset");
    public static final IKey SCENE_RESET_TOOLTIP = L10n.lang("bbs_physics.scene.reset_tooltip");
    public static final IKey SCENE_WORLD = L10n.lang("bbs_physics.scene.world");
    public static final IKey SCENE_GRAVITY = L10n.lang("bbs_physics.scene.gravity");
    public static final IKey SCENE_GRAVITY_TOOLTIP = L10n.lang("bbs_physics.scene.gravity_tooltip");
    public static final IKey SCENE_STEPS = L10n.lang("bbs_physics.scene.steps");
    public static final IKey SCENE_STEPS_TOOLTIP = L10n.lang("bbs_physics.scene.steps_tooltip");
    public static final IKey SCENE_REGION = L10n.lang("bbs_physics.scene.region");
    public static final IKey SCENE_REGION_TOOLTIP = L10n.lang("bbs_physics.scene.region_tooltip");
    public static final IKey SCENE_REGION_ROOM = L10n.lang("bbs_physics.scene.region_room");
    public static final IKey SCENE_REGION_OUTDOORS = L10n.lang("bbs_physics.scene.region_outdoors");
    public static final IKey SCENE_REGION_LARGE = L10n.lang("bbs_physics.scene.region_large");
    public static final IKey SCENE_REGION_CUSTOM = L10n.lang("bbs_physics.scene.region_custom");
    public static final IKey SCENE_DEBUG = L10n.lang("bbs_physics.scene.debug");

    public static final IKey HUD_TICK = L10n.lang("bbs_physics.hud.tick");
    public static final IKey HUD_NOT_RECORDED = L10n.lang("bbs_physics.hud.not_recorded");
    public static final IKey HUD_GHOSTS = L10n.lang("bbs_physics.hud.ghosts");
    public static final IKey HUD_OUTSIDE = L10n.lang("bbs_physics.hud.outside");

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

    public static final IKey RAGDOLL_TITLE = L10n.lang("bbs_physics.forms.ragdoll.title");
    public static final IKey RAGDOLL_ENABLED = L10n.lang("bbs_physics.forms.ragdoll.enabled");
    public static final IKey RAGDOLL_ENABLED_TOOLTIP = L10n.lang("bbs_physics.forms.ragdoll.enabled_tooltip");

    public static final IKey RAGDOLL_JOINT = L10n.lang("bbs_physics.forms.ragdoll.joint");
    public static final IKey RAGDOLL_KIND = L10n.lang("bbs_physics.forms.ragdoll.kind");
    public static final IKey RAGDOLL_KIND_TOOLTIP = L10n.lang("bbs_physics.forms.ragdoll.kind_tooltip");
    public static final IKey RAGDOLL_SWING = L10n.lang("bbs_physics.forms.ragdoll.swing");
    public static final IKey RAGDOLL_SWING_TOOLTIP = L10n.lang("bbs_physics.forms.ragdoll.swing_tooltip");
    public static final IKey RAGDOLL_TWIST = L10n.lang("bbs_physics.forms.ragdoll.twist");
    public static final IKey RAGDOLL_HINGE_AXIS = L10n.lang("bbs_physics.forms.ragdoll.hinge_axis");
    public static final IKey RAGDOLL_HINGE_AXIS_TOOLTIP = L10n.lang("bbs_physics.forms.ragdoll.hinge_axis_tooltip");
    public static final IKey RAGDOLL_HINGE = L10n.lang("bbs_physics.forms.ragdoll.hinge");
    public static final IKey RAGDOLL_ATTACH = L10n.lang("bbs_physics.forms.ragdoll.attach");
    public static final IKey RAGDOLL_ATTACH_TOOLTIP = L10n.lang("bbs_physics.forms.ragdoll.attach_tooltip");
    public static final IKey RAGDOLL_ATTACH_AUTO = L10n.lang("bbs_physics.forms.ragdoll.attach_auto");
    public static final IKey RAGDOLL_RESET_BONE = L10n.lang("bbs_physics.forms.ragdoll.reset_bone");

    public static final IKey RAGDOLL_ONLY_MODELS = L10n.lang("bbs_physics.forms.ragdoll.only_models");
    public static final IKey RAGDOLL_SUMMARY_PART = L10n.lang("bbs_physics.forms.ragdoll.summary_part");
    public static final IKey RAGDOLL_SUMMARY_UNMARKED = L10n.lang("bbs_physics.forms.ragdoll.summary_unmarked");

    public static final IKey RAGDOLL_CONTEXT_COPY = L10n.lang("bbs_physics.forms.ragdoll.context.copy");
    public static final IKey RAGDOLL_CONTEXT_PASTE = L10n.lang("bbs_physics.forms.ragdoll.context.paste");
    public static final IKey RAGDOLL_CONTEXT_RESET = L10n.lang("bbs_physics.forms.ragdoll.context.reset");
    public static final IKey RAGDOLL_CONTEXT_SAVE = L10n.lang("bbs_physics.forms.ragdoll.context.save");
    public static final IKey RAGDOLL_CONTEXT_NAME = L10n.lang("bbs_physics.forms.ragdoll.context.name");

    private static final IKey JOINT_CONE = L10n.lang("bbs_physics.forms.ragdoll.kind.cone");
    private static final IKey JOINT_HINGE = L10n.lang("bbs_physics.forms.ragdoll.kind.hinge");
    private static final IKey JOINT_FIXED = L10n.lang("bbs_physics.forms.ragdoll.kind.fixed");
    private static final IKey JOINT_FREE = L10n.lang("bbs_physics.forms.ragdoll.kind.free");

    public static IKey jointKind(RagdollJointKind kind)
    {
        return switch (kind)
        {
            case CONE -> JOINT_CONE;
            case HINGE -> JOINT_HINGE;
            case FIXED -> JOINT_FIXED;
            case FREE -> JOINT_FREE;
        };
    }
}
