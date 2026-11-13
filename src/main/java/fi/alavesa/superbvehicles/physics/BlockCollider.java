package fi.alavesa.superbvehicles.physics;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3d;

import java.util.List;

/**
 * Hull-vs-block collision. The hull is an OBB; collision tests use it shrunk by {@link #SKIN} on every side, so a hull
 * resting exactly on the floor (or touching a wall) is contact, not overlap - before 0.2.0 resting contact counted as a
 * hit, every horizontal move was "blocked" and vehicles only crept forward through the step-up path.
 * A move is resolved one axis at a time (y, then x, then z): the largest free fraction is found by bisection. A
 * horizontal hit may step up to the step height. A hull that starts inside blocks (spawned a little into the ground)
 * is pushed out along the axis of least penetration first.
 */
public final class BlockCollider {
    /** Blocks of tolerance on each side of the hull for collision tests. */
    public static final double SKIN = 0.02;

    public static final class Result {
        public final Vector3d moved = new Vector3d();
        public final Vector3d depenetration = new Vector3d();
        public boolean hitX, hitY, hitZ, stepped;
        public double blockedSpeed;       // blocks of this move lost to collisions (horizontal)
        public double hitX0, hitY0, hitZ0; // world point of the last horizontal hit (NaN when none)
        public boolean hitHorizontal() { return hitX || hitZ; }
        public String describe() {
            if (depenetration.lengthSquared() > 1e-9) return String.format(java.util.Locale.ROOT, "depenetrated %.3f %.3f %.3f", depenetration.x, depenetration.y, depenetration.z);
            if (stepped) return "stepped up";
            if (hitX || hitZ) return "blocked " + (hitX ? "x" : "") + (hitZ ? "z" : "");
            if (hitY) return "resting";
            return "free";
        }
    }

    private final Terrain terrain;
    private final World world;   // only for cosmetic block lookups (particles); null in tests

    public BlockCollider(World world) { this.world = world; this.terrain = new WorldTerrain(world); }
    public BlockCollider(Terrain terrain) { this.world = terrain instanceof WorldTerrain wt ? wt.world() : null; this.terrain = terrain; }

    public Terrain terrain() { return terrain; }
    public List<BoundingBox> blockBoxes(BoundingBox region) { return terrain.boxes(region); }

    static Obb shrunk(Obb hull) {
        return new Obb(hull.center, new Vector3d(Math.max(0.01, hull.half.x - SKIN), Math.max(0.01, hull.half.y - SKIN), Math.max(0.01, hull.half.z - SKIN)), hull.rot);
    }

    private static boolean overlaps(Obb hull, List<BoundingBox> boxes) {
        BoundingBox a = hull.aabb();
        for (BoundingBox b : boxes) if (a.overlaps(b) && hull.intersects(b)) return true;
        return false;
    }

    /** Move the hull (at the START position) by delta, axis by axis. */
    public Result sweep(Obb startHull, Vector3d delta, double stepHeight, boolean canStep) {
        Result r = new Result();
        r.hitX0 = r.hitY0 = r.hitZ0 = Double.NaN;
        Obb hull = shrunk(startHull);
        BoundingBox region = startHull.aabb().union(startHull.moved(delta).aabb()).expand(Math.max(0, stepHeight) + 0.5);
        List<BoundingBox> boxes = terrain.boxes(region);
        Vector3d pos = new Vector3d();
        if (!boxes.isEmpty() && overlaps(hull, boxes)) {
            Vector3d push = depenetration(hull, boxes, Math.max(0.6, stepHeight + 0.1));
            if (push != null) { pos.add(push); r.depenetration.set(push); }
        }
        if (delta.lengthSquared() < 1e-12) { r.moved.set(pos); return r; }
        double dy = axisMove(hull, pos, 1, delta.y, boxes);
        if (Math.abs(dy - delta.y) > 1e-9) r.hitY = true;
        pos.y += dy;
        double dx = axisMove(hull, pos, 0, delta.x, boxes);
        boolean blockedX = Math.abs(dx - delta.x) > 1e-9;
        pos.x += dx;
        double dz = axisMove(hull, pos, 2, delta.z, boxes);
        boolean blockedZ = Math.abs(dz - delta.z) > 1e-9;
        pos.z += dz;
        if ((blockedX || blockedZ) && canStep && stepHeight > 0) {
            Vector3d alt = new Vector3d(pos.x - dx, pos.y, pos.z - dz);
            double lift = axisMove(hull, alt, 1, stepHeight, boxes);
            alt.y += lift;
            double ax = axisMove(hull, alt, 0, delta.x, boxes); alt.x += ax;
            double az = axisMove(hull, alt, 2, delta.z, boxes); alt.z += az;
            if (Math.hypot(ax, az) > Math.hypot(dx, dz) + 1e-6) {
                alt.y += axisMove(hull, alt, 1, -lift, boxes);    // settle back onto the step
                pos.set(alt);
                blockedX = Math.abs(ax - delta.x) > 1e-9; blockedZ = Math.abs(az - delta.z) > 1e-9;
                r.stepped = true;
            }
        }
        r.hitX = blockedX; r.hitZ = blockedZ;
        if (blockedX || blockedZ) { Vector3d c = new Vector3d(hull.center).add(pos); r.hitX0 = c.x; r.hitY0 = c.y; r.hitZ0 = c.z; }
        r.moved.set(pos);
        r.blockedSpeed = Math.hypot(delta.x - (pos.x - r.depenetration.x), delta.z - (pos.z - r.depenetration.z));
        return r;
    }

    /** Smallest push (up to maxUp vertically, 0.6 horizontally) that frees the hull, or null when none is found. */
    static Vector3d depenetration(Obb hull, List<BoundingBox> boxes, double maxUp) {
        BoundingBox a = hull.aabb();
        double up = 0, best = Double.MAX_VALUE;
        Vector3d bestV = null;
        for (BoundingBox b : boxes) if (a.overlaps(b) && hull.intersects(b)) up = Math.max(up, b.getMaxY() - a.getMinY());
        Vector3d[] candidates = { new Vector3d(0, up + 1e-4, 0) };
        for (Vector3d c : candidates) {
            if (c.y > maxUp) continue;
            if (!overlaps(hull.moved(c), boxes) && c.length() < best) { best = c.length(); bestV = c; }
        }
        if (bestV != null) return bestV;
        for (double s = 0.1; s <= 0.6 + 1e-9; s += 0.1) for (Vector3d d : new Vector3d[]{new Vector3d(s, 0, 0), new Vector3d(-s, 0, 0), new Vector3d(0, 0, s), new Vector3d(0, 0, -s)})
            if (!overlaps(hull.moved(d), boxes)) return d;
        return null;
    }

    /** Largest fraction of `amount` along axis that keeps the hull (offset by `offset`) free of the boxes. */
    private static double axisMove(Obb hull, Vector3d offset, int axis, double amount, List<BoundingBox> boxes) {
        if (Math.abs(amount) < 1e-12 || boxes.isEmpty()) return amount;
        Vector3d full = new Vector3d(offset); set(full, axis, get(full, axis) + amount);
        if (!overlaps(hull.moved(full), boxes)) return amount;
        // already overlapping where we are (depenetration failed): do not make it worse, allow moving out
        if (overlaps(hull.moved(offset), boxes)) return 0;
        double lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            double mid = (lo + hi) / 2;
            Vector3d t = new Vector3d(offset); set(t, axis, get(t, axis) + amount * mid);
            if (overlaps(hull.moved(t), boxes)) hi = mid; else lo = mid;
        }
        double got = amount * lo;
        return Math.abs(got) < 1e-5 ? 0 : got;
    }

    private static double get(Vector3d v, int axis) { return axis == 0 ? v.x : axis == 1 ? v.y : v.z; }
    private static void set(Vector3d v, int axis, double val) { if (axis == 0) v.x = val; else if (axis == 1) v.y = val; else v.z = val; }

    public double groundAt(double x, double y, double z, double up, double down) { return terrain.groundAt(x, y, z, up, down); }
    public boolean isWater(double x, double y, double z) { return terrain.isWater(x, y, z); }
    public double waterSurface(double x, double y, double z) { return terrain.waterSurface(x, y, z); }
    public boolean loaded(double x, double z) { return terrain.loaded(x, z); }

    /** Block at a point for cosmetics; null without a world (tests). */
    public Block blockAt(Vector3d p) { return world == null ? null : world.getBlockAt((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)); }

    /** The solid block under a point for dust particles; null without a world. */
    public Block below(double x, double y, double z) {
        if (world == null) return null;
        Block b = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y - 0.1), (int) Math.floor(z));
        return b.isPassable() ? b.getRelative(BlockFace.DOWN) : b;
    }

    public static boolean soft(Material m, List<String> list) {
        String n = m.name();
        for (String s : list) { if (n.equals(s)) return true; if (s.equals("LEAVES") && n.endsWith("_LEAVES")) return true; if (s.endsWith("*") && n.startsWith(s.substring(0, s.length() - 1))) return true; }
        return false;
    }
}
