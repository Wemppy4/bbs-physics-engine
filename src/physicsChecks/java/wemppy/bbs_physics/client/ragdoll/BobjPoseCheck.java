package wemppy.bbs_physics.client.ragdoll;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.RigBone;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.client.scene.PhysicsBake;
import wemppy.bbs_physics.ragdoll.RagdollState;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * The simulated pose handed back into a BOBJ armature lands every bone where the recording has it:
 * a pose that agrees with the animation changes nothing, a fallen body and a detached arm are drawn
 * where they fell, a bone with no body of its own rides along with the one above it, and the keys
 * the bake writes for that pose compose back to the very same skeleton.
 */
public final class BobjPoseCheck
{
    private static final float EPS = 1.0e-4F;

    public static void main(String[] args) throws Exception
    {
        agreeing();
        Map<String, Matrix4f> drawn = fallen();
        baked(drawn);

        System.out.println("BobjPoseCheck passed: agreeing pose untouched, fallen and detached bones placed, unsimulated child carried, bake composes back.");
    }

    /** A recording that matches the animation exactly leaves the skeleton where it was. */
    private static void agreeing()
    {
        BOBJArmature armature = armature();

        armature.setupMatrices();

        Map<String, Matrix4f> before = frames(armature);
        RagdollState state = new RagdollState();

        record(state, "body", before.get("body"));
        record(state, "arm", before.get("arm"));
        release(state);

        new RagdollPoseApplier.BobjWalker(state, 1F).walk(armature);
        armature.setupMatrices();

        for (BOBJBone bone : armature.orderedBones)
        {
            same("agreeing " + bone.name, before.get(bone.name), skin(bone));
        }
    }

    /** A body moved and turned, an arm torn off it: both drawn where recorded, the hand on the arm. */
    private static Map<String, Matrix4f> fallen()
    {
        BOBJArmature armature = armature();

        armature.setupMatrices();

        Map<String, Matrix4f> rest = frames(armature);
        Matrix4f body = new Matrix4f().translation(0.5F, 0.2F, -0.3F).rotateXYZ(0.4F, -0.9F, 0.7F);
        Matrix4f arm = new Matrix4f().translation(0.05F, -0.1F, 0.02F)
            .mul(body)
            .mul(new Matrix4f(rest.get("body")).invert().mul(rest.get("arm")))
            .rotateZ(0.5F);
        RagdollState state = new RagdollState();

        record(state, "body", body);
        record(state, "arm", arm);
        release(state);

        new RagdollPoseApplier.BobjWalker(state, 1F).walk(armature);
        armature.setupMatrices();

        same("fallen body", body, skin(armature.bones.get("body")));
        same("detached arm", arm, skin(armature.bones.get("arm")));

        Matrix4f hand = new Matrix4f(arm).mul(new Matrix4f(rest.get("arm")).invert().mul(rest.get("hand")));

        same("carried hand", hand, skin(armature.bones.get("hand")));

        Map<String, Matrix4f> drawn = new HashMap<>();

        for (BOBJBone bone : armature.orderedBones)
        {
            drawn.put(bone.name, skin(bone));
        }

        /* The bake reads the walked armature itself, so it is kept for the next step. */
        BAKED_FROM = armature;

        return drawn;
    }

    private static BOBJArmature BAKED_FROM;

    /**
     * The keys the bake works out for the fallen pose, put on a fresh armature the way BBS applies
     * a pose (the translate added, the rotation composed onto the channels), draw the same skeleton
     * with no substitution at all.
     */
    private static void baked(Map<String, Matrix4f> drawn) throws Exception
    {
        Method shift = PhysicsBake.class.getDeclaredMethod("bobjShift", BOBJBone.class, Vector3f.class, Map.class);

        shift.setAccessible(true);

        BOBJArmature walked = BAKED_FROM;
        BOBJArmature fresh = armature();
        Map<RigBone, Vector3f> before = new HashMap<>();

        for (BOBJBone bone : walked.orderedBones)
        {
            before.put(bone, new Vector3f());
        }

        for (String name : new String[] {"body", "arm"})
        {
            BOBJBone bone = walked.bones.get(name);
            BOBJBone target = fresh.bones.get(name);
            Vector3f translate = (Vector3f) shift.invoke(null, bone, new Vector3f(), before);

            target.transform.translate.add(translate);
            target.orient = new Quaternionf(bone.orient);
        }

        fresh.setupMatrices();

        for (BOBJBone bone : fresh.orderedBones)
        {
            same("baked " + bone.name, drawn.get(bone.name), skin(bone));
        }
    }

    /**
     * A small posed rig: a body turned about Y, an arm off it at an angle, a hand at its end, every
     * bone with channels of its own so nothing lines up by accident.
     */
    private static BOBJArmature armature()
    {
        BOBJArmature armature = new BOBJArmature("rig");

        armature.addBone(new BOBJBone(0, "body", "", new Matrix4f().translation(0.1F, 0.9F, -0.05F).rotateY(0.3F)));
        armature.addBone(new BOBJBone(1, "arm", "body", new Matrix4f().translation(0.35F, 1.4F, 0F).rotateZ(-1.2F).rotateX(0.2F)));
        armature.addBone(new BOBJBone(2, "hand", "arm", new Matrix4f().translation(0.8F, 1.25F, 0.05F).rotateZ(-1.3F)));
        armature.initArmature();

        armature.bones.get("body").transform.rotate.set(0.1F, 0.4F, -0.2F);
        armature.bones.get("body").transform.translate.set(0.02F, 0F, 0.01F);
        armature.bones.get("arm").transform.rotate.set(0.5F, -0.3F, 0.2F);
        armature.bones.get("hand").transform.rotate.set(0.2F, 0F, 0.6F);

        return armature;
    }

    private static Map<String, Matrix4f> frames(BOBJArmature armature)
    {
        Map<String, Matrix4f> frames = new HashMap<>();

        for (BOBJBone bone : armature.orderedBones)
        {
            frames.put(bone.name, new Matrix4f(bone.mat));
        }

        return frames;
    }

    /**
     * Records an armature-space frame the way the rigs do: behind the model's half turn, since a
     * BOBJ bone's cache frame keeps none on its right and the rig's base carries one.
     */
    private static void record(RagdollState state, String bone, Matrix4f frame)
    {
        Matrix4f flipped = new Matrix4f().rotateY((float) Math.PI).mul(frame);

        state.set(bone, flipped.getTranslation(new Vector3f()), flipped.getUnnormalizedRotation(new Quaternionf()), 0F, true);
    }

    private static void release(RagdollState state)
    {
        state.setRecorded(true);
        state.setAuthority(0F, true);
    }

    /** Where the bone's skin is drawn: its skeleton frame, moved by its shift (BOBJBone.compute). */
    private static Matrix4f skin(BOBJBone bone)
    {
        Matrix4f frame = new Matrix4f(bone.mat);

        if (bone.offset != null)
        {
            frame.translateLocal(bone.offset);
        }

        return frame;
    }

    private static void same(String what, Matrix4f expected, Matrix4f actual)
    {
        if (!expected.equals(actual, EPS))
        {
            throw new AssertionError(what + ": expected\n" + expected + "got\n" + actual);
        }
    }
}
