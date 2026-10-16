package wemppy.bbs_physics.client.scene;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import wemppy.bbs_physics.chain.ChainKnob;
import wemppy.bbs_physics.chain.FormChains;
import wemppy.bbs_physics.forms.BodyKnob;
import wemppy.bbs_physics.forms.IModelPhysicsForm;
import wemppy.bbs_physics.forms.IPhysicsForm;
import wemppy.bbs_physics.forms.PhysicsBodyState;
import wemppy.bbs_physics.forms.PhysicsKnobValue;
import wemppy.bbs_physics.forms.ValueData;
import wemppy.bbs_physics.ragdoll.RagdollKnob;
import wemppy.bbs_physics.ragdoll.RagdollState;

import java.util.List;

/**
 * The kept poses (stage 3) without a game: a walk packed and handed back bit for bit, the poses an
 * edit takes back, the limit they are kept under, the physics a replay's signature leaves out, and
 * the question every rig asks before sharing a walk — whether its own would take in anything
 * simulated.
 */
public final class PoseCacheCheck
{
    public static void main(String[] args)
    {
        packing();
        keeping();
        signature();
        takingIn();

        System.out.println("PoseCacheCheck passed: packing, keeping and taking back, limit, physics left out of the signature, what a walk takes in.");
    }

    /** A walk packed into a pose comes back the same to the bit, as fresh objects. */
    private static void packing()
    {
        MatrixCache walk = new MatrixCache();

        walk.put("", new Matrix4f().translate(0.1F, 0.2F, 0.3F), new Matrix4f());
        walk.put("0", new Matrix4f().rotateY(0.5F).scale(2F).translate(1F / 3F, 0F, 7F), null);
        walk.put("0/head", new Matrix4f().rotateXYZ(0.1F, -1.2F, 2.9F).translate(-4F, 5F, 1E-7F), null);

        Matrix4f actorWorld = new Matrix4f().translate(10F, 20F, 30F).rotateX(0.3F);
        Matrix4f frame = new Matrix4f().translate(-1F, 0F, 4F).rotateZ(1F);
        ScenePoses.Pose pose = ScenePoses.Pose.of(walk, actorWorld, new String[] {"0"}, new Matrix4f[] {frame}, null);
        MatrixCache back = pose.matrices();

        require(back.keySet().equals(walk.keySet()), "Every path a walk left comes back");

        for (String path : walk.keySet())
        {
            require(back.get(path).matrix().equals(walk.get(path).matrix()), "A matrix comes back bit for bit: " + path);
            require(back.get(path).matrix() != walk.get(path).matrix(), "A matrix comes back as a fresh object");
        }

        require(back.get("0").matrix() != pose.matrices().get("0").matrix(), "Every hand-out is fresh");
        require(back.get("missing").matrix() == null, "A path the walk did not leave reads as nothing, as from a walk");

        Matrix4f into = new Matrix4f();

        pose.actorWorld(into);
        require(into.equals(actorWorld), "Where the actor stood comes back");

        pose.parentFrame(0, into);
        require(into.equals(frame), "The parent frame left on a body comes back");

        ScenePoses.Pose next = ScenePoses.Pose.of(walk, actorWorld, new String[] {"0"}, new Matrix4f[] {frame}, pose.paths);

        require(next.paths == pose.paths, "Consecutive walks over the same paths share one array of them");
    }

    /** Poses by tick: kept, taken back from a tick, forgotten, and never past the limit. */
    private static void keeping()
    {
        ScenePoses poses = new ScenePoses();
        ScenePoses.Actor actor = poses.entry("actor");
        MatrixCache walk = new MatrixCache();

        walk.put("", new Matrix4f(), null);
        walk.put("0", new Matrix4f().translate(1F, 0F, 0F), null);

        for (int tick = 0; tick < 10; tick++)
        {
            actor.put(tick, walk, new Matrix4f().translate(tick, 0F, 0F), new String[0], new Matrix4f[0]);
        }

        long ten = poses.bytes();

        require(ten > 0 && actor.get(9) != null && actor.get(10) == null, "Ten ticks kept");

        Matrix4f into = new Matrix4f();

        actor.get(7).actorWorld(into);
        require(into.m30() == 7F, "Each tick keeps its own walk");

        actor.put(7, walk, new Matrix4f(), new String[0], new Matrix4f[0]);
        actor.get(7).actorWorld(into);
        require(into.m30() == 7F && poses.bytes() == ten, "A tick already kept is not walked over");

        actor.truncate(4);
        require(actor.get(3) != null && actor.get(4) == null && actor.get(9) == null, "Taken back from a tick on");
        require(poses.bytes() < ten && poses.bytes() > 0, "What was taken back is counted off");

        poses.edited(new SceneChanges.Change(0, null, SceneChanges.Poses.STAND));
        require(actor.get(3) != null, "A physics edit takes nothing back");

        poses.edited(new SceneChanges.Change(0, null, SceneChanges.Poses.ALL));
        require(actor.get(0) == null && poses.bytes() == 0, "An edit to the film as a whole takes everything back");

        ScenePoses small = new ScenePoses(2000);
        ScenePoses.Actor tight = small.entry("actor");

        for (int tick = 0; tick < 100; tick++)
        {
            tight.put(tick, walk, new Matrix4f(), new String[0], new Matrix4f[0]);
        }

        require(small.bytes() <= 2000 && tight.get(0) != null && tight.get(99) == null, "Never kept past the limit; the later ticks walk as before");
    }

    /** A replay's signature leaves physics out — but for the strand bones — at every depth. */
    private static void signature()
    {
        MapType form = new MapType();
        MapType chain = new MapType();
        MapType part = new MapType();
        MapType inner = new MapType();
        ListType parts = new ListType();

        chain.putBool("enabled", true);
        inner.putFloat("bbs_physics:authority", 0.5F);
        inner.putString("texture", "assets:skin.png");
        part.put("form", inner);
        parts.add(part);

        form.putString("id", "bbs:model");
        form.putFloat(BodyKnob.MASS.id, 5F);
        form.put("bbs_physics:ragdoll", new MapType());
        form.put(FormChains.KEY, chain);
        form.put("parts", parts);

        MapType other = (MapType) form.copy();

        other.putFloat(BodyKnob.MASS.id, 50F);
        other.getList("parts").get(0).asMap().get("form").asMap().putFloat("bbs_physics:authority", 1F);

        ScenePoses.withoutPhysics(form);
        ScenePoses.withoutPhysics(other);

        require(!form.has(BodyKnob.MASS.id) && !form.has("bbs_physics:ragdoll"), "The physics of a form is left out");
        require(form.has(FormChains.KEY), "The strand bones stay: they decide what BBS's own chains leave alone");
        require(!inner.has("bbs_physics:authority") && inner.has("texture"), "Nested forms lose their physics and nothing else");
        require(form.equals(other), "Two forms that differ only in their physics sign the same");

        other.getList("parts").get(0).asMap().get("form").asMap().putString("texture", "assets:other.png");
        require(!form.equals(other), "A different look signs differently");

        require(ScenePoses.isPhysics(RagdollKnob.MUSCLES.id) && !ScenePoses.isPhysics(FormChains.KEY) && !ScenePoses.isPhysics("pose"),
            "Which values are physics to the signature");
    }

    /** What a rig's walk takes in: the substitutions' own rules, asked before the walk. */
    private static void takingIn()
    {
        TestForm form = new TestForm();
        TestForm other = new TestForm();
        RagdollState ragdoll = new RagdollState();

        ragdoll.setRecorded(true);
        ragdoll.setAuthority(1F, true);
        form.ragdoll = ragdoll;

        PoseEvaluation strands = new PoseEvaluation(form, PoseEvaluation.Kind.CHAIN);
        PoseEvaluation skeleton = new PoseEvaluation(form, PoseEvaluation.Kind.RAGDOLL);
        PoseEvaluation body = new PoseEvaluation(form, PoseEvaluation.Kind.BODY);
        PoseEvaluation elsewhere = new PoseEvaluation(other, PoseEvaluation.Kind.BODY);
        List<Form> forms = List.of(form);

        require(!strands.takesIn(forms), "A ragdoll standing on its keyframes is taken in by nobody");

        ragdoll.setAuthority(0.4F, true);
        require(strands.takesIn(forms), "Strands ride their own model's falling ragdoll");
        require(!skeleton.takesIn(forms), "A ragdoll never reads its own simulated pose");
        require(!body.takesIn(forms), "A body reads nothing simulated on its own form");
        require(elsewhere.takesIn(List.of(form, other)), "Anyone else takes in a falling ragdoll");

        ragdoll.setRecorded(false);
        require(!strands.takesIn(forms), "Nothing recorded, nothing taken in");
        ragdoll.setRecorded(true);
        ragdoll.setAuthority(1F, true);

        RagdollState hair = new RagdollState();

        hair.setRecorded(true);
        hair.setAuthority(0F, true);
        form.chain = hair;
        require(!strands.takesIn(forms) && !skeleton.takesIn(forms), "A model's own loose strands are taken in by none of its rigs");
        require(elsewhere.takesIn(List.of(form)), "But by a form hanging elsewhere");
        form.chain = null;

        PhysicsBodyState state = new PhysicsBodyState();

        state.set(new Vector3f(), new Quaternionf(), 0F, true);
        form.body = state;
        require(skeleton.takesIn(forms) && strands.takesIn(forms), "A ragdoll and strands take in the body of their own form");
        require(!body.takesIn(forms), "The body itself does not");

        state.set(new Vector3f(), new Quaternionf(), 1F, true);
        require(!skeleton.takesIn(forms), "A body held by its keyframes is taken in by nobody");

        form.body = null;

        try (PoseEvaluation ignored = strands.enter())
        {
            require(!strands.tookIn(), "A scope starts with nothing taken in");
            PoseEvaluation.noteTakenIn();
            require(strands.tookIn(), "A substitution says it drew into the walk");
        }

        try (PoseEvaluation ignored = strands.enter())
        {
            require(!strands.tookIn(), "Entering again starts over");
        }
    }

    private static void require(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    /** A form carrying the runtime slots the mixins give real forms in the game. */
    private static final class TestForm extends StructureForm implements IPhysicsForm, IModelPhysicsForm
    {
        private PhysicsBodyState body;
        private RagdollState ragdoll;
        private RagdollState chain;

        @Override public ValueData bbs_physics$getCollision() { return null; }
        @Override public ValueData bbs_physics$getBody() { return null; }
        @Override public ValueFloat bbs_physics$getAuthority() { return null; }
        @Override public PhysicsKnobValue bbs_physics$getBodyKnob(BodyKnob knob) { return null; }
        @Override public PhysicsBodyState bbs_physics$getBodyState() { return this.body; }
        @Override public void bbs_physics$setBodyState(PhysicsBodyState state) { this.body = state; }
        @Override public ValueData bbs_physics$getRagdoll() { return null; }
        @Override public RagdollState bbs_physics$getRagdollState() { return this.ragdoll; }
        @Override public void bbs_physics$setRagdollState(RagdollState state) { this.ragdoll = state; }
        @Override public ValueData bbs_physics$getChain() { return null; }
        @Override public RagdollState bbs_physics$getChainState() { return this.chain; }
        @Override public void bbs_physics$setChainState(RagdollState state) { this.chain = state; }
        @Override public PhysicsKnobValue bbs_physics$getChainKnob(ChainKnob knob) { return null; }
        @Override public PhysicsKnobValue bbs_physics$getRagdollKnob(RagdollKnob knob) { return null; }
    }
}
