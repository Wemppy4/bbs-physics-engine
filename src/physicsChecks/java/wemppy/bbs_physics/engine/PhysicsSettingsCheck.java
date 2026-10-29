package wemppy.bbs_physics.engine;

import wemppy.bbs_physics.chain.ChainIO;
import wemppy.bbs_physics.chain.FormChain;
import wemppy.bbs_physics.forms.BodyIO;
import wemppy.bbs_physics.forms.FormBody;
import wemppy.bbs_physics.ragdoll.FormRagdoll;
import wemppy.bbs_physics.ragdoll.RagdollIO;
import wemppy.bbs_physics.client.forms.PhysicsTracks;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.forms.PhysicsAuthorityValue;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import java.util.ArrayList;
import java.util.List;

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
        checkTracks();
        System.out.println("PhysicsSettingsCheck passed: disabled settings survive serialization; unused physics tracks hide without losing keys.");
    }

    private static void checkTracks()
    {
        boolean[] enabled = {false};
        PhysicsKnobValue knob = new PhysicsKnobValue("bbs_physics:body_mass", 1F, 0F, 100F, form -> false)
        {
            @Override
            public boolean isVisible() { return enabled[0]; }
        };
        PhysicsAuthorityValue authority = new PhysicsAuthorityValue("bbs_physics:authority")
        {
            @Override
            public boolean isVisible() { return enabled[0]; }
        };
        KeyframeChannel<Float> channel = new KeyframeChannel<>(knob.getId(), KeyframeFactories.FLOAT);
        TrackDescriptor body = track("part/child", knob, channel);
        TrackDescriptor handle = track("", authority, null);
        TrackDescriptor ordinary = track("", new ValueFloat("ordinary", 0F), null);
        List<TrackDescriptor> tracks = new ArrayList<>(List.of(body, handle, ordinary));
        PhysicsTracks.filter(tracks);
        require(tracks.equals(List.of(ordinary)), "Unused body/authority tracks must hide; ordinary tracks must survive");

        channel.insert(12F, 7F);
        tracks = new ArrayList<>(List.of(body, handle, ordinary));
        PhysicsTracks.filter(tracks);
        require(tracks.equals(List.of(body, ordinary)), "Disabled nested track with keys must remain accessible");
        require(channel.getKeyframes().size() == 1 && channel.interpolate(12F) == 7F,
            "Listing tracks must not mutate saved animation");
        require(knob.isAnimatable(), "Disabled physics must retain animation capability");

        enabled[0] = true;
        tracks = new ArrayList<>(List.of(body, handle, ordinary));
        PhysicsTracks.filter(tracks);
        require(tracks.size() == 3, "Re-enabling physics must restore available tracks");
    }

    private static TrackDescriptor track(String path, ValueFloat value, KeyframeChannel<Float> channel)
    {
        return new TrackDescriptor(TrackId.property(path, value.getId()), channel, null,
            IKey.constant(value.getId()), null, 0, value);
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
