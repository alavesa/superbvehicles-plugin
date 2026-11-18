package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.BoxBuffer;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.Surface;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.VoxelSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Test world for the rigid-body model: an endless floor at {@code base} plus any boxes (slabs, blocks, walls,
 * ceilings), each with a surface; water and hazard cells; an "unloaded" switch. Allocation-free once built.
 */
final class TestVoxels implements VoxelSource {
    final double base;
    Surface floor = Surface.DEFAULT;
    boolean loaded = true;
    private double[] b = new double[6 * 64];
    private Surface[] s = new Surface[64];
    private int n;
    private final Set<Long> water = new HashSet<>(), hazards = new HashSet<>();
    /** Columns without a floor (x range): a pit or the void. */
    int holeX0 = Integer.MAX_VALUE, holeX1 = Integer.MIN_VALUE;

    TestVoxels(double base) { this.base = base; }

    TestVoxels box(double x0, double y0, double z0, double x1, double y1, double z1) { return box(x0, y0, z0, x1, y1, z1, Surface.DEFAULT); }
    TestVoxels box(double x0, double y0, double z0, double x1, double y1, double z1, Surface surface) {
        if (n == s.length) { b = java.util.Arrays.copyOf(b, b.length * 2); s = java.util.Arrays.copyOf(s, s.length * 2); }
        int o = n * 6;
        b[o] = x0; b[o + 1] = y0; b[o + 2] = z0; b[o + 3] = x1; b[o + 4] = y1; b[o + 5] = z1;
        s[n++] = surface;
        return this;
    }
    /** A step across the whole road: everything from x0 to x1 is {@code top} above the floor. */
    TestVoxels step(double x0, double x1, double top) { return box(x0, base, -50, x1, base + top, 50); }
    TestVoxels hole(int x0, int x1) { holeX0 = x0; holeX1 = x1; return this; }
    private static long key(int x, int y, int z) { return ((long) (x & 0x1fffff) << 42) | ((long) (y & 0x1fffff) << 21) | (z & 0x1fffff); }
    TestVoxels addWater(int x, int y, int z) { water.add(key(x, y, z)); return this; }
    TestVoxels addHazard(int x, int y, int z) { hazards.add(key(x, y, z)); return this; }

    @Override public boolean collect(int x0, int y0, int z0, int x1, int y1, int z1, BoxBuffer out) {
        if (!loaded) return false;
        if (y0 < base && y1 + 1 > base - 1) {
            for (int x = x0; x <= x1; x++) {
                if (x >= holeX0 && x <= holeX1) continue;
                out.add(x, base - 1, z0, x + 1, base, z1 + 1, floor);
            }
        }
        for (int i = 0; i < n; i++) {
            int o = i * 6;
            if (b[o + 3] < x0 || b[o] > x1 + 1 || b[o + 4] < y0 || b[o + 1] > y1 + 1 || b[o + 5] < z0 || b[o + 2] > z1 + 1) continue;
            out.add(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], s[i]);
        }
        return true;
    }

    @Override public boolean water(int x, int y, int z) { return !water.isEmpty() && water.contains(key(x, y, z)); }
    @Override public boolean hazard(int x, int y, int z) { return !hazards.isEmpty() && hazards.contains(key(x, y, z)); }
}
