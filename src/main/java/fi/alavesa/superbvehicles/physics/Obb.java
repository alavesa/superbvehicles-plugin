package fi.alavesa.superbvehicles.physics;

import org.bukkit.util.BoundingBox;
import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * An oriented box: centre, half extents and a rotation (columns = the box axes in world space). Separating-axis tests
 * against block AABBs and other OBBs, point containment, a ray test that also reports which face was hit (for hit
 * zones), and the world AABB that bounds it.
 */
public final class Obb {
    public final Vector3d center;
    public final Vector3d half;
    public final Matrix3d rot;

    public Obb(Vector3d center, Vector3d half, Matrix3d rot) { this.center = center; this.half = half; this.rot = rot; }

    public Obb moved(Vector3d d) { return new Obb(new Vector3d(center).add(d), half, rot); }

    public Vector3d axis(int i) { return rot.getColumn(i, new Vector3d()); }

    public BoundingBox aabb() {
        double ex = Math.abs(rot.m00) * half.x + Math.abs(rot.m10) * half.y + Math.abs(rot.m20) * half.z;
        double ey = Math.abs(rot.m01) * half.x + Math.abs(rot.m11) * half.y + Math.abs(rot.m21) * half.z;
        double ez = Math.abs(rot.m02) * half.x + Math.abs(rot.m12) * half.y + Math.abs(rot.m22) * half.z;
        return new BoundingBox(center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez);
    }

    /** The eight corners. */
    public Vector3d[] corners() {
        Vector3d[] out = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            Vector3d l = new Vector3d((i & 1) == 0 ? -half.x : half.x, (i & 2) == 0 ? -half.y : half.y, (i & 4) == 0 ? -half.z : half.z);
            out[i] = rot.transform(l).add(center);
        }
        return out;
    }

    /** Lowest corner height. */
    public double minY() { double m = Double.MAX_VALUE; for (Vector3d c : corners()) m = Math.min(m, c.y); return m; }

    public boolean contains(Vector3d p) {
        Vector3d l = rot.transformTranspose(new Vector3d(p).sub(center));
        return Math.abs(l.x) <= half.x && Math.abs(l.y) <= half.y && Math.abs(l.z) <= half.z;
    }

    /** SAT against an axis-aligned box. */
    public boolean intersects(BoundingBox b) {
        Vector3d bc = new Vector3d((b.getMinX() + b.getMaxX()) / 2, (b.getMinY() + b.getMaxY()) / 2, (b.getMinZ() + b.getMaxZ()) / 2);
        Vector3d bh = new Vector3d(b.getWidthX() / 2, b.getHeight() / 2, b.getWidthZ() / 2);
        Vector3d d = new Vector3d(bc).sub(center);
        Vector3d[] A = {axis(0), axis(1), axis(2)};
        Vector3d[] B = {new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)};
        double[] ha = {half.x, half.y, half.z}, hb = {bh.x, bh.y, bh.z};
        for (int i = 0; i < 3; i++) if (!overlap(A[i], d, A, ha, B, hb)) return false;
        for (int i = 0; i < 3; i++) if (!overlap(B[i], d, A, ha, B, hb)) return false;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            Vector3d c = new Vector3d(A[i]).cross(B[j]);
            if (c.lengthSquared() < 1e-9) continue;
            if (!overlap(c, d, A, ha, B, hb)) return false;
        }
        return true;
    }

    /** SAT against another OBB. */
    public boolean intersects(Obb o) {
        Vector3d d = new Vector3d(o.center).sub(center);
        Vector3d[] A = {axis(0), axis(1), axis(2)}, B = {o.axis(0), o.axis(1), o.axis(2)};
        double[] ha = {half.x, half.y, half.z}, hb = {o.half.x, o.half.y, o.half.z};
        for (int i = 0; i < 3; i++) if (!overlap(A[i], d, A, ha, B, hb)) return false;
        for (int i = 0; i < 3; i++) if (!overlap(B[i], d, A, ha, B, hb)) return false;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            Vector3d c = new Vector3d(A[i]).cross(B[j]);
            if (c.lengthSquared() < 1e-9) continue;
            if (!overlap(c, d, A, ha, B, hb)) return false;
        }
        return true;
    }

    private static boolean overlap(Vector3d L, Vector3d d, Vector3d[] A, double[] ha, Vector3d[] B, double[] hb) {
        double ra = 0, rb = 0;
        for (int i = 0; i < 3; i++) { ra += ha[i] * Math.abs(A[i].dot(L)); rb += hb[i] * Math.abs(B[i].dot(L)); }
        return Math.abs(d.dot(L)) <= ra + rb + 1e-9;
    }

    /** Ray hit: {distance, faceAxis (0 x, 1 y, 2 z), faceSign (+1/-1 in box space)} or null. */
    public double[] rayHit(Vector3d from, Vector3d dir, double maxDist) {
        Vector3d o = rot.transformTranspose(new Vector3d(from).sub(center));
        Vector3d d = rot.transformTranspose(new Vector3d(dir));
        double tmin = 0, tmax = maxDist;
        int hitAxis = -1; double hitSign = 0;
        double[] oo = {o.x, o.y, o.z}, dd = {d.x, d.y, d.z}, hh = {half.x, half.y, half.z};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1e-12) { if (Math.abs(oo[i]) > hh[i]) return null; continue; }
            double t1 = (-hh[i] - oo[i]) / dd[i], t2 = (hh[i] - oo[i]) / dd[i];
            double sign = -1;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; sign = 1; }
            if (t1 > tmin) { tmin = t1; hitAxis = i; hitSign = sign; }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        if (hitAxis < 0) { hitAxis = 1; hitSign = 1; }   // started inside
        return new double[]{tmin, hitAxis, hitSign};
    }
}
