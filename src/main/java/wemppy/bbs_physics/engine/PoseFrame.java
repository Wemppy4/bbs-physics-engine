package wemppy.bbs_physics.engine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Interpolate physical poses in world space, then express them in the current render frame. */
public final class PoseFrame
{
    private final Matrix4f previous = new Matrix4f();
    private final Matrix4f current = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3f a = new Vector3f();
    private final Vector3f b = new Vector3f();
    private final Quaternionf qa = new Quaternionf();
    private final Quaternionf qb = new Quaternionf();
    private final Quaternionf qi = new Quaternionf();

    public void set(Matrix4f frame, boolean teleport)
    {
        this.previous.set(teleport ? frame : this.current);
        this.current.set(frame);
        this.render(frame);
    }

    public void render(Matrix4f frame)
    {
        this.inverse.set(frame).invert();
    }

    public Vector3f position(Vector3f previous, Vector3f current, float t, Vector3f out)
    {
        this.previous.transformPosition(previous, this.a);
        this.current.transformPosition(current, this.b);
        return this.inverse.transformPosition(this.a.lerp(this.b, t), out);
    }

    public Quaternionf rotation(Quaternionf previous, Quaternionf current, float t, Quaternionf out)
    {
        this.previous.getUnnormalizedRotation(this.qa).mul(previous).normalize();
        this.current.getUnnormalizedRotation(this.qb).mul(current).normalize();
        this.inverse.getUnnormalizedRotation(this.qi);
        return this.qi.mul(this.qa.slerp(this.qb, t), out).normalize();
    }
}
