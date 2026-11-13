package fi.alavesa.superbvehicles.physics;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** {@link Terrain} backed by a Bukkit world. Never loads chunks: an unloaded chunk counts as empty and not loaded. */
public final class WorldTerrain implements Terrain {
    private final World world;
    public WorldTerrain(World world) { this.world = world; }
    public World world() { return world; }

    @Override public List<BoundingBox> boxes(BoundingBox region) {
        List<BoundingBox> out = new ArrayList<>();
        int x0 = (int) Math.floor(region.getMinX()) - 1, x1 = (int) Math.floor(region.getMaxX()) + 1;
        int y0 = Math.max(world.getMinHeight(), (int) Math.floor(region.getMinY()) - 1), y1 = Math.min(world.getMaxHeight() - 1, (int) Math.floor(region.getMaxY()) + 1);
        int z0 = (int) Math.floor(region.getMinZ()) - 1, z1 = (int) Math.floor(region.getMaxZ()) + 1;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 40000) return out;   // absurd region: treat as free
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

    @Override public double groundAt(double x, double y, double z, double up, double down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return Double.NaN;
        for (int by = (int) Math.floor(y + up); by >= (int) Math.floor(y - down); by--) {
            if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;
            Block b = world.getBlockAt(bx, by, bz);
            if (b.isPassable()) continue;
            double top = Double.NaN;
            double fx = x - bx, fz = z - bz;
            for (BoundingBox box : b.getCollisionShape().getBoundingBoxes())
                if (fx >= box.getMinX() - 1e-6 && fx <= box.getMaxX() + 1e-6 && fz >= box.getMinZ() - 1e-6 && fz <= box.getMaxZ() + 1e-6) top = Double.isNaN(top) ? by + box.getMaxY() : Math.max(top, by + box.getMaxY());
            if (Double.isNaN(top)) continue;                       // the shape does not cover this point (a fence post beside it)
            if (top > y + up + 1e-6) continue;                     // a block whose top is above the search start (we are inside it)
            return top;
        }
        return Double.NaN;
    }

    @Override public boolean isWater(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4) || by < world.getMinHeight() || by >= world.getMaxHeight()) return false;
        Block b = world.getBlockAt(bx, by, bz);
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
    }

    @Override public boolean loaded(double x, double z) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        return world.isChunkLoaded(cx, cz) && world.isChunkGenerated(cx, cz);
    }
}
