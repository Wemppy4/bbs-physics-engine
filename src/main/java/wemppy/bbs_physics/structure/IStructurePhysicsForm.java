package wemppy.bbs_physics.structure;

import wemppy.bbs_physics.forms.ValueData;

public interface IStructurePhysicsForm
{
    ValueData bbs_physics$getDestruction();
    DestructionState bbs_physics$getDestructionState();
    void bbs_physics$setDestructionState(DestructionState state);
}
