package fi.alavesa.superbvehicles.physics;

import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test terrain: a floor at {@code base} plus columns of any height in half-block steps (0.5 = a bottom slab, 1.0 = a
 * full block, 1.5 = block + slab ...), optional water up to {@code waterTop} in chosen columns.
 */
final class StepTerrain implements Terrain {
    final double base;
    private final Map<Long, Double> heights = new HashMap<>();   // column -> top above base
    private final Map<Long, Boolean> water = new HashMap<>();
    double waterTop = Double.NaN;                                 // absolute water surface height (y + 0.875 of the top water block)

    StepTerrain(double base) { this.base = base; }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    StepTerrain column(int x, int z, double top) { heights.put(key(x, z), top); return this; }
    /** Fill x0..x1 (inclusive) x z0..z1 with a column height. */
    StepTerrain area(int x0, int z0, int x1, int z1, double top) { for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) column(x, z, top); return this; }
    /** Water columns over the floor (the floor is the sea bed); the surface is at waterTop. */
    StepTerrain sea(int x0, int z0, int x1, int z1, double surface) { waterTop = surface; for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) water.put(key(x, z), true); return this; }

    double top(int x, int z) { return base + heights.getOrDefault(key(x, z), 0.0); }

    @Override public List<BoundingBox> boxes(BoundingBox r) {
        List<BoundingBox> out = new ArrayList<>();
        int x0 = (int) Math.floor(r.getMinX()), x1 = (int) Math.floor(r.getMaxX()), z0 = (int) Math.floor(r.getMinZ()), z1 = (int) Math.floor(r.getMaxZ());
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double t = top(x, z);
            BoundingBox b = new BoundingBox(x, base - 4, z, x + 1, t, z + 1);
            if (b.overlaps(r)) out.add(b);
        }
        return out;
    }

    @Override public double groundAt(double x, double y, double z, double up, double down) {
        double t = top((int) Math.floor(x), (int) Math.floor(z));
        return t <= y + up + 1e-6 && t >= y - down - 1e-6 ? t : Double.NaN;
    }

    @Override public boolean isWater(double x, double y, double z) {
        if (Double.isNaN(waterTop)) return false;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!water.getOrDefault(key(bx, bz), false)) return false;
        return y >= top(bx, bz) && y < Math.ceil(waterTop);
    }

    @Override public double waterSurface(double x, double y, double z) {
        if (Double.isNaN(waterTop) || !water.getOrDefault(key((int) Math.floor(x), (int) Math.floor(z)), false)) return Double.NaN;
        return y > waterTop + 2 ? Double.NaN : waterTop;
    }

    @Override public boolean loaded(double x, double z) { return true; }
}
