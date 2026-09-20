package wemppy.bbs_physics.structure;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import wemppy.bbs_physics.forms.PhysicsForms;
import wemppy.bbs_physics.forms.PhysicsType;

/** Stored setup only. Runtime poses never travel into a saved/copied form. */
public final class StructureDestruction
{
    public static final String KEY = "bbs_physics:destruction";
    public static final float DEFAULT_STRENGTH = 3F;
    public static final int MAX_BLOCKS = 1024;

    private StructureDestruction() {}

    public static boolean isEnabled(Form form)
    {
        return PhysicsForms.getType(form) == PhysicsType.DESTRUCTION;
    }

    public static float strength(Form form)
    {
        if (form instanceof IStructurePhysicsForm physics
            && physics.bbs_physics$getDestruction().get() instanceof MapType map && map.has("strength"))
        {
            float value = map.getFloat("strength");
            return Float.isFinite(value) ? Math.max(0F, Math.min(1000F, value)) : DEFAULT_STRENGTH;
        }
        return DEFAULT_STRENGTH;
    }

    public static void setStrength(Form form, float value)
    {
        if (form instanceof IStructurePhysicsForm physics)
        {
            var stored = physics.bbs_physics$getDestruction();
            MapType map = stored.get() instanceof MapType data ? (MapType) data.copy() : new MapType();
            map.putFloat("strength", Float.isFinite(value) ? Math.max(0F, Math.min(1000F, value)) : DEFAULT_STRENGTH);
            stored.set(map);
        }
    }

    public static DestructionState state(Form form)
    {
        return form instanceof IStructurePhysicsForm physics ? physics.bbs_physics$getDestructionState() : null;
    }
}
