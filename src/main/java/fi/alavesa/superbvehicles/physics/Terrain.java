package fi.alavesa.superbvehicles.physics;

import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * What the physics needs to know about the world: solid collision boxes in a region, the ground height under a
 * point, water, and whether a chunk is loaded. {@link WorldTerrain} answers from a Bukkit world; tests use a flat
 * floor. Coordinates are world blocks.
 */
public interface Terrain {
    /** Collision boxes (world coordinates) of solid blocks intersecting the region. */
    List<BoundingBox> boxes(BoundingBox region);

    /** Highest solid surface under (x, z) searching from y+up down to y-down; NaN when there is none. */
    double groundAt(double x, double y, double z, double up, double down);

    boolean isWater(double x, double y, double z);

    /** Is the chunk holding (x, z) loaded and fully generated? */
    boolean loaded(double x, double z);

    /** Water surface height at (x, z) near y, or NaN when not over water. */
    default double waterSurface(double x, double y, double z) {
        if (!isWater(x, y, z) && !isWater(x, y - 0.5, z) && !isWater(x, y + 0.5, z)) return Double.NaN;
        double top = Math.floor(y);
        while (isWater(x, top + 1, z) && top < y + 8) top += 1;
        return top + (isWater(x, top, z) ? 0.875 : 0);
    }

    /** A flat, endless floor whose top is at {@code height} (tests and the startup self-check). */
    static Terrain flat(double height) {
        return new Terrain() {
            @Override public List<BoundingBox> boxes(BoundingBox r) {
                java.util.ArrayList<BoundingBox> out = new java.util.ArrayList<>();
                if (r.getMinY() > height) return out;
                int x0 = (int) Math.floor(r.getMinX()), x1 = (int) Math.floor(r.getMaxX()), z0 = (int) Math.floor(r.getMinZ()), z1 = (int) Math.floor(r.getMaxZ());
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) out.add(new BoundingBox(x, height - 1, z, x + 1, height, z + 1));
                return out;
            }
            @Override public double groundAt(double x, double y, double z, double up, double down) { return y + up >= height && y - down <= height ? height : Double.NaN; }
            @Override public boolean isWater(double x, double y, double z) { return false; }
            @Override public boolean loaded(double x, double z) { return true; }
        };
    }
}
