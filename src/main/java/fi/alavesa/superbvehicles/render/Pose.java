package fi.alavesa.superbvehicles.render;

import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * Where a vehicle is and how it is turned. Model frame (Blockbench px): forward = -Z, right = +X, up = +Y, origin at
 * the vehicle position. yaw is Minecraft yaw (0 = south, +Z); pitch > 0 nose up; roll > 0 right side down. The world
 * rotation is R = Ry(-(yaw + modelYaw)) * Rx(pitch) * Rz(-roll); with the default modelYaw 180 a model whose nose
 * points to Blockbench north faces the way the vehicle drives.
 */
public final class Pose {
    public double x, y, z;
    public double yaw, pitch, roll;   // degrees
    public double modelYaw = 180;

    public Pose() { }
    public Pose(Location l, double modelYaw) { x = l.getX(); y = l.getY(); z = l.getZ(); yaw = l.getYaw(); this.modelYaw = modelYaw; }
    public Pose copy() { Pose p = new Pose(); p.x = x; p.y = y; p.z = z; p.yaw = yaw; p.pitch = pitch; p.roll = roll; p.modelYaw = modelYaw; return p; }
    public void set(Pose o) { x = o.x; y = o.y; z = o.z; yaw = o.yaw; pitch = o.pitch; roll = o.roll; modelYaw = o.modelYaw; }

    public Location location(World w) { return new Location(w, x, y, z, (float) yaw, 0f); }
    public Vector3d position() { return new Vector3d(x, y, z); }

    /** Rotation matrix (model -> world), see class doc. */
    public Matrix3d rotation() {
        double a = Math.toRadians(-(yaw + modelYaw)), p = Math.toRadians(pitch), r = Math.toRadians(-roll);
        return new Matrix3d().rotationY(a).rotateX(p).rotateZ(r);
    }

    /** Same rotation as a quaternion (for display transformations). */
    public Quaternionf quaternion() {
        double a = Math.toRadians(-(yaw + modelYaw)), p = Math.toRadians(pitch), r = Math.toRadians(-roll);
        return new Quaternionf().rotationY((float) a).rotateX((float) p).rotateZ((float) r);
    }

    /** Forward unit vector in the world (horizontal when pitch is 0). */
    public Vector3d forward() { return rotation().transform(new Vector3d(0, 0, -1)); }
    public Vector3d right() { return rotation().transform(new Vector3d(1, 0, 0)); }
    public Vector3d up() { return rotation().transform(new Vector3d(0, 1, 0)); }
    /** Flat forward (yaw only). */
    public Vector3d forwardFlat() { double rad = Math.toRadians(yaw); return new Vector3d(-Math.sin(rad), 0, Math.cos(rad)); }
    public Vector3d rightFlat() { double rad = Math.toRadians(yaw); return new Vector3d(-Math.cos(rad), 0, -Math.sin(rad)); }

    /** Model px -> world blocks. */
    public Vector3d toWorld(double px, double py, double pz, double scale) {
        Vector3d v = rotation().transform(new Vector3d(px / 16.0 * scale, py / 16.0 * scale, pz / 16.0 * scale));
        return v.add(x, y, z);
    }
    public Vector3d toWorld(double[] px, double scale) { return toWorld(px[0], px[1], px[2], scale); }
    /** Model px direction -> world direction (unit). */
    public Vector3d dirToWorld(double[] d) { Vector3d v = rotation().transform(new Vector3d(d[0], d[1], d[2])); return v.lengthSquared() < 1e-12 ? forward() : v.normalize(); }
    /** World point -> model-local blocks (unscaled). */
    public Vector3d toLocal(Vector3d world) { return rotation().transpose().transform(new Vector3d(world).sub(x, y, z)); }

    public static double wrap(double deg) { deg %= 360; if (deg > 180) deg -= 360; if (deg < -180) deg += 360; return deg; }
    public static double approach(double cur, double target, double rate) { return cur + (target - cur) * Math.max(0, Math.min(1, rate)); }
    public static double approachAngle(double cur, double target, double maxStep) { double d = wrap(target - cur); if (Math.abs(d) <= maxStep) return wrap(target); return wrap(cur + Math.signum(d) * maxStep); }
    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
