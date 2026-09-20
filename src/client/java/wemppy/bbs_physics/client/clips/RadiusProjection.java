package wemppy.bbs_physics.client.clips;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.ui.utils.Area;
import org.joml.*;

/** Camera snapshot for radius picking and ray/plane dragging on CML. */
final class RadiusProjection
{
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3d camera = new Vector3d();
    private final Vector3d center = new Vector3d();
    private final Area area = new Area();

    void setup(Camera camera, Area area, Vector3d center)
    {
        this.setup(camera, area, center.x, center.y, center.z);
    }

    void setup(Camera camera, Area area, double x, double y, double z)
    {
        this.viewProjection.set(camera.projection).mul(camera.view);
        this.inverse.set(this.viewProjection).invert();
        this.camera.set(camera.position);
        this.center.set(x, y, z);
        this.area.set(area.x, area.y, area.w, area.h);
    }

    boolean projectToScreen(Vector3d world, Vector2f out)
    {
        Vector4f clip = new Vector4f((float) (world.x - this.camera.x),
            (float) (world.y - this.camera.y), (float) (world.z - this.camera.z), 1F);
        this.viewProjection.transform(clip);
        if (clip.w <= 0.0001F) return false;
        out.set(this.area.x + (clip.x / clip.w + 1F) * this.area.w / 2F,
            this.area.y + (1F - clip.y / clip.w) * this.area.h / 2F);
        return out.isFinite();
    }

    private Vector3f unproject(int x, int y, float z)
    {
        Vector4f point = new Vector4f(2F * (x - this.area.x) / this.area.w - 1F,
            1F - 2F * (y - this.area.y) / this.area.h, z, 1F);
        this.inverse.transform(point);
        return new Vector3f(point.x, point.y, point.z).div(point.w);
    }

    Vector3f rayDirection(int x, int y, Vector3f out)
    {
        return out.set(this.unproject(x, y, 0.5F)).sub(this.unproject(x, y, -1F)).normalize();
    }

    boolean intersectPlane(int x, int y, Vector3f normal, Vector3d out)
    {
        Vector3f near = this.unproject(x, y, -1F);
        Vector3f direction = this.rayDirection(x, y, new Vector3f());
        double denominator = direction.dot(normal);
        if (java.lang.Math.abs(denominator) < 0.0001D) return false;
        Vector3d origin = new Vector3d(this.camera).add(near);
        double t = new Vector3d(this.center).sub(origin).dot(new Vector3d(normal)) / denominator;
        if (t <= 0D || !Double.isFinite(t)) return false;
        out.set(origin).add(direction.x * t, direction.y * t, direction.z * t);
        return out.isFinite();
    }
}
