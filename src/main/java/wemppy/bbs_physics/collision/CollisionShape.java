package wemppy.bbs_physics.collision;

import org.joml.Vector3f;

/**
 * One authored primitive, in the frame of the slot it belongs to.
 *
 * <p><b>Units are blocks</b> throughout — the unit the simulation runs in (§8) — and {@code size}
 * is the full extent, not the half one, because that is the number an author measures: a head is
 * half a block across, not a quarter. The editor scrolls it a sixteenth at a time, so a model
 * authored in pixels still lands on whole pixels.</p>
 *
 * <p><b>The frame.</b> For a plain form, that is the form's own frame — where it is drawn. For a
 * bone of a cubic model it is <em>model space measured from the bone's pivot</em>, which is what
 * the model editor shows: the half turn that separates a bone's own matrix from model space
 * (§10.1) is applied when the shape is handed to the engine, not here, so that what an author
 * types matches what they see. For a BOBJ bone the two frames only differ by that same flip
 * applied from the outside, so its bone-local directions pass through unchanged.</p>
 *
 * @param rotation euler angles in degrees, applied Z·Y·X — the order BBS rotates a cube in
 */
public record CollisionShape(CollisionKind kind, Vector3f offset, Vector3f rotation, Vector3f size)
{
    public CollisionShape
    {
        kind = kind == null ? CollisionKind.BOX : kind;
        offset = offset == null ? new Vector3f() : offset;
        rotation = rotation == null ? new Vector3f() : rotation;
        size = size == null ? new Vector3f(0.5F) : size;
    }

    /** A fresh primitive of the given kind, sized for whatever it is being added to. */
    public static CollisionShape of(CollisionKind kind, float size)
    {
        return new CollisionShape(kind, new Vector3f(), new Vector3f(), new Vector3f(size));
    }

    public CollisionShape copy()
    {
        return new CollisionShape(this.kind, new Vector3f(this.offset), new Vector3f(this.rotation), new Vector3f(this.size));
    }
}
