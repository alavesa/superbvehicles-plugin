package fi.alavesa.superbvehicles.physics;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Our own hull-vs-block collision: the hull is an OBB; a move is resolved one axis at a time (y, then x, then z) by
 * testing the box at the end position against every block collision shape in the swept region and, when it
 * overlaps, bisecting for the largest free fraction. Horizontal blocks can be stepped over up to a step height.
 * Also the ground probe used by suspension and the water sampling used by boats.
 */
public final class BlockCollider {
    public static final class Result {
        public final Vector3d moved = new Vector3d();
        public boolean hitX, hitY, hitZ, stepped;
        public Block lastHit;
        public double blockedSpeed;   // length of the movement lost to collisions this tick
        public boolean hitHorizontal() { return hitX || hitZ; }
    }

    private final World world;
    public BlockCollider(World world) { this.world = world; }

    /** Collision boxes of blocks intersecting the region. */
    public List<BoundingBox> blockBoxes(BoundingBox region) {
        List<BoundingBox> out = new ArrayList<>();
        int x0 = (int) Math.floor(region.getMinX()) - 1, x1 = (int) Math.floor(region.getMaxX()) + 1;
        int y0 = Math.max(world.getMinHeight(), (int) Math.floor(region.getMinY()) - 1), y1 = Math.min(world.getMaxHeight() - 1, (int) Math.floor(region.getMaxY()) + 1);
        int z0 = (int) Math.floor(region.getMinZ()) - 1, z1 = (int) Math.floor(region.getMaxZ()) + 1;
        if ((long) (x1 - x0) * (y1 - y0) * (z1 - z0) > 20000) return out;   // absurd region: treat as free
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = y0; y <= y1; y++) {
                Block b = world.getBlockAt(x, y, z);
                if (b.isPassable()) continue;
                for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                    BoundingBox w = bb.clone().shift(x, y, z);
                    if (w.overlaps(region)) out.add(w);
                }
            }
        }
        return out;
    }

    private static boolean overlaps(Obb hull, List<BoundingBox> boxes) {
        BoundingBox a = hull.aabb();
        for (BoundingBox b : boxes) if (a.overlaps(b) && hull.intersects(b)) return true;
        return false;
    }

    /** Move the hull by delta, axis by axis; returns what happened. hull is the OBB at the START position. */
    public Result sweep(Obb hull, Vector3d delta, double stepHeight, boolean canStep) {
        Result r = new Result();
        if (delta.lengthSquared() < 1e-10) return r;
        BoundingBox region = hull.aabb().union(hull.moved(delta).aabb()).expand(stepHeight + 0.1);
        List<BoundingBox> boxes = blockBoxes(region);
        Vector3d pos = new Vector3d();
        // y first (gravity/landing), then x and z
        double dy = axisMove(hull, pos, 1, delta.y, boxes, r);
        pos.y += dy; if (Math.abs(dy - delta.y) > 1e-9) r.hitY = true;
        double dx = axisMove(hull, pos, 0, delta.x, boxes, r);
        boolean blockedX = Math.abs(dx - delta.x) > 1e-9;
        pos.x += dx;
        double dz = axisMove(hull, pos, 2, delta.z, boxes, r);
        boolean blockedZ = Math.abs(dz - delta.z) > 1e-9;
        pos.z += dz;
        if ((blockedX || blockedZ) && canStep && stepHeight > 0) {
            // try the horizontal move again from up to stepHeight higher
            Vector3d alt = new Vector3d(0, pos.y, 0);
            double lift = axisMove(hull, alt, 1, stepHeight, boxes, r);
            alt.y += lift;
            double ax = axisMove(hull, alt, 0, delta.x, boxes, r); alt.x += ax;
            double az = axisMove(hull, alt, 2, delta.z, boxes, r); alt.z += az;
            double gotBefore = Math.hypot(pos.x, pos.z), gotAfter = Math.hypot(alt.x, alt.z);
            if (gotAfter > gotBefore + 1e-6) {
                // settle back down onto the step
                double down = axisMove(hull, alt, 1, -lift, boxes, r);
                alt.y += down;
                pos.set(alt);
                blockedX = Math.abs(ax - delta.x) > 1e-9; blockedZ = Math.abs(az - delta.z) > 1e-9;
                r.stepped = true;
            }
        }
        r.hitX = blockedX; r.hitZ = blockedZ;
        r.moved.set(pos);
        r.blockedSpeed = new Vector3d(delta).sub(pos).length();
        return r;
    }

    /** Largest fraction of `amount` along axis that keeps the hull (already offset by `offset`) free of the boxes. */
    private double axisMove(Obb hull, Vector3d offset, int axis, double amount, List<BoundingBox> boxes, Result r) {
        if (Math.abs(amount) < 1e-9 || boxes.isEmpty()) return amount;
        Vector3d full = new Vector3d(offset); set(full, axis, get(full, axis) + amount);
        if (!overlaps(hull.moved(full), boxes)) return amount;
        double lo = 0, hi = 1;
        for (int i = 0; i < 8; i++) {
            double mid = (lo + hi) / 2;
            Vector3d t = new Vector3d(offset); set(t, axis, get(t, axis) + amount * mid);
            if (overlaps(hull.moved(t), boxes)) hi = mid; else lo = mid;
        }
        double got = amount * lo;
        r.lastHit = nearestBlock(hull.moved(full));
        return Math.abs(got) < 1e-4 ? 0 : got * 0.98;
    }

    private Block nearestBlock(Obb hull) {
        BoundingBox a = hull.aabb();
        return world.getBlockAt((int) Math.floor(a.getCenterX()), (int) Math.floor(a.getCenterY()), (int) Math.floor(a.getCenterZ()));
    }

    private static double get(Vector3d v, int axis) { return axis == 0 ? v.x : axis == 1 ? v.y : v.z; }
    private static void set(Vector3d v, int axis, double val) { if (axis == 0) v.x = val; else if (axis == 1) v.y = val; else v.z = val; }

    /** Highest solid surface under (x, z) searching from y+up down to y-down; NaN when none. */
    public double groundAt(double x, double y, double z, double up, double down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return Double.NaN;
        for (int by = (int) Math.floor(y + up); by >= (int) Math.floor(y - down); by--) {
            if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;
            Block b = world.getBlockAt(bx, by, bz);
            if (b.isPassable()) continue;
            double top = by;
            for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
                double fx = x - bx, fz = z - bz;
                if (fx >= box.getMinX() - 1e-6 && fx <= box.getMaxX() + 1e-6 && fz >= box.getMinZ() - 1e-6 && fz <= box.getMaxZ() + 1e-6) top = Math.max(top, by + box.getMaxY());
            }
            if (top > y + up + 1e-6) continue;   // a block whose top is above the search start (we are inside it)
            return top;
        }
        return Double.NaN;
    }

    public boolean isWater(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        Block b = world.getBlockAt(bx, (int) Math.floor(y), bz);
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
    }

    /** Water surface height at (x,z) near y, or NaN when not over water. */
    public double waterSurface(double x, double y, double z) {
        if (!isWater(x, y, z) && !isWater(x, y - 0.5, z) && !isWater(x, y + 0.5, z)) return Double.NaN;
        double top = Math.floor(y);
        while (isWater(x, top + 1, z) && top < y + 8) top += 1;
        return top + (isWater(x, top, z) ? 0.875 : 0);
    }

    public Block blockAt(Vector3d p) { return world.getBlockAt((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)); }

    public static boolean soft(Material m, List<String> list) {
        String n = m.name();
        for (String s : list) { if (n.equals(s)) return true; if (s.equals("LEAVES") && n.endsWith("_LEAVES")) return true; if (s.endsWith("*") && n.startsWith(s.substring(0, s.length() - 1))) return true; }
        return false;
    }

    public Block below(double x, double y, double z) { Block b = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y - 0.1), (int) Math.floor(z)); return b.isPassable() ? b.getRelative(BlockFace.DOWN) : b; }
}
