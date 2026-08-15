package mchorse.bbs_physics.collision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A form's collision markup: what shape it is, as the form itself describes it.
 *
 * <p>This is a description, not a body. Nothing here decides what is simulated — that is up to
 * whoever reads it, and the readers want different things from the same markup (§5.2): an actor
 * shoving props wants one kinematic body per marked bone, a model falling as one piece wants them
 * all welded into a single shape, a ragdoll wants a few large ones with joints between, and hair
 * will one day want no bodies at all, just something to bump into. Keeping the markup free of that
 * decision is the whole reason it lives on the form instead of inside the physics body form.</p>
 *
 * @param slots keyed by bone name for a model, or by {@link #SELF} for the form's own shape
 */
public record FormCollision(Map<String, CollisionSlot> slots)
{
    /** The key of the form's own slot, as opposed to one of its bones. */
    public static final String SELF = "";

    public static final FormCollision EMPTY = new FormCollision(Collections.emptyMap());

    public FormCollision
    {
        slots = slots == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
    }

    public boolean isEmpty()
    {
        return this.slots.isEmpty();
    }

    public CollisionSlot get(String slot)
    {
        return this.slots.getOrDefault(slot, CollisionSlot.NONE);
    }

    /** The same markup with one slot replaced — or removed, when it has nothing left to say. */
    public FormCollision with(String slot, CollisionSlot value)
    {
        Map<String, CollisionSlot> slots = new LinkedHashMap<>(this.slots);

        if (value == null || value.isEmpty())
        {
            slots.remove(slot);
        }
        else
        {
            slots.put(slot, value);
        }

        return new FormCollision(slots);
    }
}
