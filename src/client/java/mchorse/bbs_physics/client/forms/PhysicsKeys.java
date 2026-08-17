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
    public static final IKey PHYSICS_ADD = L10n.lang("bbs_physics.forms.physics.add");
    public static final IKey PHYSICS_ADD_BODY = L10n.lang("bbs_physics.forms.physics.add_body");
    public static final IKey PHYSICS_ADD_RAGDOLL = L10n.lang("bbs_physics.forms.physics.add_ragdoll");
    public static final IKey PHYSICS_ADD_CHAIN = L10n.lang("bbs_physics.forms.physics.add_chain");
    public static final IKey PHYSICS_ADD_OBSTACLE = L10n.lang("bbs_physics.forms.physics.add_obstacle");
    public static final IKey PHYSICS_REMOVE = L10n.lang("bbs_physics.forms.physics.remove");
    public static final IKey PHYSICS_UNMARKED = L10n.lang("bbs_physics.forms.physics.unmarked");

    public static final IKey BODY_MASS_TOOLTIP = L10n.lang("bbs_physics.forms.body.mass_tooltip");
    public static final IKey BODY_TYPE = L10n.lang("bbs_physics.forms.body.type");
    public static final IKey BODY_TYPE_ACTIVE = L10n.lang("bbs_physics.forms.body.type_active");
    public static final IKey BODY_TYPE_PASSIVE = L10n.lang("bbs_physics.forms.body.type_passive");
    public static final IKey BODY_TYPE_TOOLTIP = L10n.lang("bbs_physics.forms.body.type_tooltip");

    public static final IKey MATERIAL_TOOLTIP = L10n.lang("bbs_physics.forms.body.material_tooltip");
    public static final IKey MATERIAL_CORK = L10n.lang("bbs_physics.forms.body.material.cork");
    public static final IKey MATERIAL_WOOD = L10n.lang("bbs_physics.forms.body.material.wood");
    public static final IKey MATERIAL_WATER = L10n.lang("bbs_physics.forms.body.material.water");
    public static final IKey MATERIAL_RUBBER = L10n.lang("bbs_physics.forms.body.material.rubber");
    public static final IKey MATERIAL_CONCRETE = L10n.lang("bbs_physics.forms.body.material.concrete");
    public static final IKey MATERIAL_STONE = L10n.lang("bbs_physics.forms.body.material.stone");
    public static final IKey MATERIAL_IRON = L10n.lang("bbs_physics.forms.body.material.iron");
    public static final IKey MATERIAL_GOLD = L10n.lang("bbs_physics.forms.body.material.gold");

    public static final IKey AUTHORITY = L10n.lang("bbs_physics.forms.body.authority");
    public static final IKey AUTHORITY_TOOLTIP = L10n.lang("bbs_physics.forms.body.authority_tooltip");
    public static final IKey MASS = L10n.lang("bbs_physics.forms.body.mass");
    public static final IKey FRICTION = L10n.lang("bbs_physics.forms.body.friction");
    public static final IKey RESTITUTION = L10n.lang("bbs_physics.forms.body.restitution");

    public static final IKey CLOTH_TITLE = L10n.lang("bbs_physics.forms.cloth.title");
    public static final IKey CLOTH_SHEET = L10n.lang("bbs_physics.forms.cloth.sheet");
    public static final IKey CLOTH_FABRIC = L10n.lang("bbs_physics.forms.cloth.fabric");
    public static final IKey CLOTH_WIDTH = L10n.lang("bbs_physics.forms.cloth.width");
    public static final IKey CLOTH_HEIGHT = L10n.lang("bbs_physics.forms.cloth.height");
    public static final IKey CLOTH_SEGMENTS_X = L10n.lang("bbs_physics.forms.cloth.segments_x");
    public static final IKey CLOTH_SEGMENTS_Y = L10n.lang("bbs_physics.forms.cloth.segments_y");
    public static final IKey CLOTH_EDGE_TOOLTIP = L10n.lang("bbs_physics.forms.cloth.edge_tooltip");
    public static final IKey CLOTH_EDGE_TOP = L10n.lang("bbs_physics.forms.cloth.edge_top");
    public static final IKey CLOTH_EDGE_LEFT = L10n.lang("bbs_physics.forms.cloth.edge_left");
    public static final IKey CLOTH_EDGE_TOP_CORNERS = L10n.lang("bbs_physics.forms.cloth.edge_top_corners");
    public static final IKey CLOTH_EDGE_NONE = L10n.lang("bbs_physics.forms.cloth.edge_none");
    public static final IKey CLOTH_SELF_COLLISION = L10n.lang("bbs_physics.forms.cloth.self_collision");
    public static final IKey CLOTH_SELF_COLLISION_TOOLTIP = L10n.lang("bbs_physics.forms.cloth.self_collision_tooltip");
    public static final IKey CLOTH_MASS = L10n.lang("bbs_physics.forms.cloth.mass");
    public static final IKey CLOTH_STIFFNESS = L10n.lang("bbs_physics.forms.cloth.stiffness");
    public static final IKey CLOTH_DAMPING = L10n.lang("bbs_physics.forms.cloth.damping");

    public static final IKey BALLOON_TITLE = L10n.lang("bbs_physics.forms.balloon.title");
    public static final IKey BALLOON_BALL = L10n.lang("bbs_physics.forms.balloon.ball");
    public static final IKey BALLOON_SKIN = L10n.lang("bbs_physics.forms.balloon.skin");
    public static final IKey BALLOON_RADIUS = L10n.lang("bbs_physics.forms.balloon.radius");
    public static final IKey BALLOON_SEGMENTS = L10n.lang("bbs_physics.forms.balloon.segments");
    public static final IKey BALLOON_RINGS = L10n.lang("bbs_physics.forms.balloon.rings");
    public static final IKey BALLOON_INFLATION = L10n.lang("bbs_physics.forms.balloon.inflation");
    public static final IKey BALLOON_STIFFNESS = L10n.lang("bbs_physics.forms.balloon.stiffness");
    public static final IKey BALLOON_MASS = L10n.lang("bbs_physics.forms.balloon.mass");
    public static final IKey BALLOON_GRAVITY = L10n.lang("bbs_physics.forms.balloon.gravity");
    public static final IKey BALLOON_DAMPING = L10n.lang("bbs_physics.forms.balloon.damping");

    public static final IKey CHAIN_TITLE = L10n.lang("bbs_physics.forms.chain.title");
    public static final IKey CHAIN_LINK_LABEL = L10n.lang("bbs_physics.forms.chain.link_label");
    public static final IKey CHAIN_LINK = L10n.lang("bbs_physics.forms.chain.link");
    public static final IKey CHAIN_STRAND = L10n.lang("bbs_physics.forms.chain.strand");
    public static final IKey CHAIN_LENGTH = L10n.lang("bbs_physics.forms.chain.length");
    public static final IKey CHAIN_SEGMENTS = L10n.lang("bbs_physics.forms.chain.segments");
    public static final IKey CHAIN_RADIUS = L10n.lang("bbs_physics.forms.chain.radius");
    public static final IKey CHAIN_FEEL = L10n.lang("bbs_physics.forms.chain.feel");
    public static final IKey CHAIN_MASS = L10n.lang("bbs_physics.forms.chain.mass");
    public static final IKey CHAIN_STIFFNESS = L10n.lang("bbs_physics.forms.chain.stiffness");
    public static final IKey CHAIN_DAMPING = L10n.lang("bbs_physics.forms.chain.damping");
    public static final IKey CHAIN_ENDS = L10n.lang("bbs_physics.forms.chain.ends");
    public static final IKey CHAIN_HELD_START = L10n.lang("bbs_physics.forms.chain.held_start");
    public static final IKey CHAIN_HELD_START_TOOLTIP = L10n.lang("bbs_physics.forms.chain.held_start_tooltip");
    public static final IKey CHAIN_ATTACH_HINT = L10n.lang("bbs_physics.forms.chain.attach_hint");

    public static final IKey CHAIN_MODIFIER_TITLE = L10n.lang("bbs_physics.forms.chain.modifier_title");
    public static final IKey CHAIN_TAKE_FROM_MODEL = L10n.lang("bbs_physics.forms.chain.take_from_model");
    public static final IKey CHAIN_TAKE_FROM_MODEL_TOOLTIP = L10n.lang("bbs_physics.forms.chain.take_from_model_tooltip");
    public static final IKey CHAIN_CLEAR = L10n.lang("bbs_physics.forms.chain.clear");
    public static final IKey CHAIN_BONE_RADIUS = L10n.lang("bbs_physics.forms.chain.bone_radius");
    public static final IKey CHAIN_SELF_COLLISION = L10n.lang("bbs_physics.forms.chain.self_collision");
    public static final IKey CHAIN_SELF_COLLISION_TOOLTIP = L10n.lang("bbs_physics.forms.chain.self_collision_tooltip");

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
    public static final IKey HUD_LOST = L10n.lang("bbs_physics.hud.lost");

    public static final IKey COLLISION_TITLE = L10n.lang("bbs_physics.forms.collision.title");
    public static final IKey COLLISION_PREVIEW = L10n.lang("bbs_physics.forms.collision.preview");

    public static final IKey COLLISION_MODE = L10n.lang("bbs_physics.forms.collision.mode");
    public static final IKey COLLISION_MODE_TOOLTIP = L10n.lang("bbs_physics.forms.collision.mode_tooltip");
    public static final IKey COLLISION_MODE_NONE = L10n.lang("bbs_physics.forms.collision.mode_none");
    public static final IKey COLLISION_MODE_AUTO = L10n.lang("bbs_physics.forms.collision.mode_auto");
    public static final IKey COLLISION_MODE_SHAPES = L10n.lang("bbs_physics.forms.collision.mode_shapes");

    public static final IKey COLLISION_SHAPES = L10n.lang("bbs_physics.forms.collision.shapes");
    public static final IKey COLLISION_SHAPE_ADD = L10n.lang("bbs_physics.forms.collision.shape_add");
    public static final IKey COLLISION_SHAPE_REMOVE = L10n.lang("bbs_physics.forms.collision.shape_remove");
    public static final IKey COLLISION_SHAPE_KIND = L10n.lang("bbs_physics.forms.collision.shape_kind");

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

    public static final IKey CLIP_POINT = L10n.lang("bbs_physics.clips.point");
    public static final IKey CLIP_POINT_FROM_LOOK = L10n.lang("bbs_physics.clips.point_from_look");
    public static final IKey CLIP_RADIAL = L10n.lang("bbs_physics.clips.radial");
    public static final IKey CLIP_RADIAL_TOOLTIP = L10n.lang("bbs_physics.clips.radial_tooltip");
    public static final IKey CLIP_STRENGTH = L10n.lang("bbs_physics.clips.strength");
    public static final IKey CLIP_STRENGTH_TOOLTIP = L10n.lang("bbs_physics.clips.strength_tooltip");
    public static final IKey CLIP_RADIUS = L10n.lang("bbs_physics.clips.radius");
    public static final IKey CLIP_RADIUS_TOOLTIP = L10n.lang("bbs_physics.clips.radius_tooltip");
    public static final IKey CLIP_DIRECTION = L10n.lang("bbs_physics.clips.direction");
    public static final IKey CLIP_BONE = L10n.lang("bbs_physics.clips.bone");
    public static final IKey CLIP_BONE_TOOLTIP = L10n.lang("bbs_physics.clips.bone_tooltip");
    public static final IKey CLIP_KICK = L10n.lang("bbs_physics.clips.kick");
    public static final IKey CLIP_KICK_TOOLTIP = L10n.lang("bbs_physics.clips.kick_tooltip");

    public static final IKey RAGDOLL_TITLE = L10n.lang("bbs_physics.forms.ragdoll.title");
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
