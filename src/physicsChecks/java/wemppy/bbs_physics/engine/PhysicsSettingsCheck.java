package wemppy.bbs_physics.engine;

import wemppy.bbs_physics.chain.ChainIO;
import wemppy.bbs_physics.chain.FormChain;
import wemppy.bbs_physics.forms.BodyIO;
import wemppy.bbs_physics.forms.FormBody;
import wemppy.bbs_physics.ragdoll.FormRagdoll;
import wemppy.bbs_physics.ragdoll.RagdollIO;

/** Switching physics off must not make its settings disappear on save or copy. */
public final class PhysicsSettingsCheck
{
    public static void main(String[] args)
    {
        FormBody body = FormBody.added().withPassive(true).withMass(27F)
            .withAsleep(true).withLockMove(FormBody.AXIS_Y).with(false);
        require(body.equals(BodyIO.fromData(BodyIO.toData(body))), "Disabled body settings lost");
        FormBody bodyFlags = BodyIO.fromData(BodyIO.toData(body, false));
        require(!bodyFlags.enabled() && bodyFlags.passive() && bodyFlags.asleep()
            && bodyFlags.lockMove() == FormBody.AXIS_Y, "Disabled body flags lost");

        FormRagdoll ragdoll = FormRagdoll.EMPTY.withMass(32F).withSelfCollide(false).withPart("head", false);
        require(ragdoll.equals(RagdollIO.fromData(RagdollIO.toData(ragdoll))), "Disabled ragdoll settings lost");
        FormRagdoll ragdollFlags = RagdollIO.fromData(RagdollIO.toData(ragdoll, false));
        require(!ragdollFlags.enabled() && !ragdollFlags.selfCollide()
            && !ragdollFlags.isPart("head"), "Disabled ragdoll bone selection lost");

        /* No bones selected yet: this used to be treated as empty and discarded. */
        FormChain chain = FormChain.EMPTY.withStiffness(0.37F).withSelfCollision(true);
        require(chain.equals(ChainIO.fromData(ChainIO.toData(chain))), "Disabled empty chain settings lost");
        FormChain chainFlags = ChainIO.fromData(ChainIO.toData(chain, false));
        require(!chainFlags.enabled() && chainFlags.selfCollision(), "Disabled chain flags lost");
        chain = chain.withBone("tail", true);
        require(chain.equals(ChainIO.fromData(ChainIO.toData(chain))), "Disabled chain bone selection lost");

        require(BodyIO.toData(FormBody.EMPTY).isEmpty() && RagdollIO.toData(FormRagdoll.EMPTY).isEmpty()
            && ChainIO.toData(FormChain.EMPTY).isEmpty(), "Unused forms must not gain settings");
        System.out.println("PhysicsSettingsCheck passed: disabled settings, flags, bones and defaults survive serialization.");
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
