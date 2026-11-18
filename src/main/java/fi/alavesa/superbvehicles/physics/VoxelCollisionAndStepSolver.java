package fi.alavesa.superbvehicles.physics;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Block collision for the rigid-body ground model ({@link VehicleRigidBodyPhysics}), answered from the real collision
 * shapes of the blocks ({@code getCollisionShape().getBoundingBoxes()}), never from material names. One instance per
 * vehicle; every query works on a box buffer that is filled once per tick ({@link #gather}) and reused, so the solver
 * itself allocates nothing after warm-up.
 * <ul>
 *   <li>{@link #support}: the height a wheel rests on. Boxes are rolled over with a circular wheel profile, so a slab,
 *       a stair step, a snow layer or a trapdoor lifts the contact point along an arc instead of a jump. Boxes that
 *       rise more than the current climb limit above the wheel are not ground - they are obstacles for the hull.</li>
 *   <li>{@link #blocked}: the hull (a yaw-turned rectangle standing on the tilted chassis plane) against every box that
 *       rises above the climb limit or hangs into the body from above.</li>
 *   <li>{@link #classify} / {@link #canClimbFullBlock} / {@link #probeAhead}: rise &lt;= free step = rolled over;
 *       rise &lt;= full step = climbed only with enough kinetic energy or drive force, and only when the lifted body
 *       fits under what is above it; anything taller is a wall.</li>
 *   <li>{@link #impact}: the impulse of a hit - normal velocity reversed by the restitution, and the angular impulse
 *       r x J through the inertia tensor.</li>
 * </ul>
 * Units: blocks (= metres), seconds, kilograms.
 */
public final class VoxelCollisionAndStepSolver {
    static final double EPS = 1e-4;

    /** What a rise above the chassis plane is to a ground vehicle. */
    public enum Obstacle { NONE, SUB_BLOCK, FULL_BLOCK, WALL, CEILING }

    /** Tyre-relevant properties of the block a wheel stands on. */
    public static final class Surface {
        public static final Surface DEFAULT = new Surface("default", 1f, 1f);
        public final String name;
        /** Friction relative to dry stone / asphalt (1 = full grip, ice ~0.05). */
        public final float grip;
        /** Multiplier on the vehicle's rolling resistance (sand, mud, snow > 1). */
        public final float rolling;
        public Surface(String name, float grip, float rolling) { this.name = name; this.grip = grip; this.rolling = rolling; }
    }

    /** Reusable list of world-space collision boxes (six doubles each) with the surface of the block they belong to. */
    public static final class BoxBuffer {
        private double[] d = new double[6 * 256];
        private Surface[] s = new Surface[256];
        private int n;

        public void clear() { n = 0; }
        public int size() { return n; }
        public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Surface surface) {
            if (n == s.length) { d = java.util.Arrays.copyOf(d, d.length * 2); s = java.util.Arrays.copyOf(s, s.length * 2); }
            int o = n * 6;
            d[o] = minX; d[o + 1] = minY; d[o + 2] = minZ; d[o + 3] = maxX; d[o + 4] = maxY; d[o + 5] = maxZ;
            s[n++] = surface;
        }
        public double minX(int i) { return d[i * 6]; }
        public double minY(int i) { return d[i * 6 + 1]; }
        public double minZ(int i) { return d[i * 6 + 2]; }
        public double maxX(int i) { return d[i * 6 + 3]; }
        public double maxY(int i) { return d[i * 6 + 4]; }
        public double maxZ(int i) { return d[i * 6 + 5]; }
        public Surface surface(int i) { return s[i]; }
    }

    /** The world as the solver sees it. {@link WorldVoxels} answers from a Bukkit world; tests use plain arrays. */
    public interface VoxelSource {
        /** Append the collision boxes of every block in the inclusive block range. False when part of it is not loaded. */
        boolean collect(int x0, int y0, int z0, int x1, int y1, int z1, BoxBuffer out);
        boolean water(int x, int y, int z);
        /** Lava, fire, magma, cactus ... : a block a player must not be put into or onto. */
        default boolean hazard(int x, int y, int z) { return false; }
    }

    private final BoxBuffer boxes = new BoxBuffer();
    private boolean complete = true;

    // result of the last support() call
    private Surface supportSurface = Surface.DEFAULT;
    // result of the last blocked() call
    private double hitX, hitY, hitZ, hitRise;
    private Obstacle hitKind = Obstacle.NONE;
    // result of the last probeAhead() call
    private double aheadRise;
    private boolean aheadCeiling;

    public BoxBuffer boxes() { return boxes; }
    /** False when the last {@link #gather} touched an unloaded chunk: the caller must not move the vehicle. */
    public boolean complete() { return complete; }
    public Surface supportSurface() { return supportSurface; }
    public double hitX() { return hitX; }
    public double hitY() { return hitY; }
    public double hitZ() { return hitZ; }
    public double hitRise() { return hitRise; }
    public Obstacle hitKind() { return hitKind; }
    public double aheadRise() { return aheadRise; }
    public boolean aheadCeiling() { return aheadCeiling; }

    /** Fill the box buffer with everything in the region (world coordinates); once per tick. */
    public void gather(VoxelSource src, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boxes.clear();
        complete = src.collect(floor(minX), floor(minY), floor(minZ), floor(maxX), floor(maxY), floor(maxZ), boxes);
    }

    static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    // ------------------------------------------------------------------ wheel support

    /**
     * The height the bottom of a wheel centred over (x, z) rests at, or NaN when there is no ground in reach. A box
     * supports the wheel when its top is at most {@code climbLimit} above {@code refY} (the wheel's current contact
     * height) and at most {@code reach} below it. A wheel of profile radius {@code r} beside a box edge at horizontal
     * distance d is held up at {@code top - (r - sqrt(r^2 - d^2))}: it rolls up the edge on its own circle.
     */
    public double support(double x, double z, double refY, double climbLimit, double r, double reach) {
        double best = Double.NaN, maxTop = refY + climbLimit + EPS, minTop = refY - reach, r2 = r * r;
        Surface surf = Surface.DEFAULT;
        BoxBuffer b = boxes;
        for (int i = 0, n = b.size(); i < n; i++) {
            double top = b.maxY(i);
            if (top > maxTop || top < minTop) continue;
            double dx = x < b.minX(i) ? b.minX(i) - x : x > b.maxX(i) ? x - b.maxX(i) : 0;
            double dz = z < b.minZ(i) ? b.minZ(i) - z : z > b.maxZ(i) ? z - b.maxZ(i) : 0;
            double d2 = dx * dx + dz * dz;
            if (d2 >= r2) continue;
            double h = d2 == 0 ? top : top - (r - Math.sqrt(r2 - d2));
            if (h < minTop) continue;
            if (Double.isNaN(best) || h > best) { best = h; surf = b.surface(i); }
        }
        supportSurface = surf;
        return best;
    }

    // ------------------------------------------------------------------ hull

    /**
     * Does the hull overlap an obstacle? The hull is a rectangle (half width {@code hw} along the right axis, half
     * length {@code hl} along the forward axis, both unit vectors in the XZ plane) centred on (cx, cz), standing on
     * the chassis plane {@code planeY + slopeF * forward + slopeR * right} and {@code height} tall. A box is an
     * obstacle when it overlaps the footprint, reaches more than {@code climbLimit} above the plane where it meets
     * the hull, and starts below the roof. Boxes at or under the climb limit are ground: the wheels deal with them.
     * On a hit the contact point, the rise above the plane and the kind are kept for {@link #impact}.
     */
    public boolean blocked(double cx, double cz, double fx, double fz, double rx, double rz, double hw, double hl,
                           double planeY, double slopeF, double slopeR, double climbLimit, double height, double freeStep, double fullStep) {
        double ex = Math.abs(rx) * hw + Math.abs(fx) * hl, ez = Math.abs(rz) * hw + Math.abs(fz) * hl;
        BoxBuffer b = boxes;
        for (int i = 0, n = b.size(); i < n; i++) {
            double bx = (b.minX(i) + b.maxX(i)) * 0.5, bz = (b.minZ(i) + b.maxZ(i)) * 0.5;
            double hbx = (b.maxX(i) - b.minX(i)) * 0.5, hbz = (b.maxZ(i) - b.minZ(i)) * 0.5;
            double dx = bx - cx, dz = bz - cz;
            // separating axes: world X, world Z, hull forward, hull right
            if (Math.abs(dx) >= ex + hbx - EPS || Math.abs(dz) >= ez + hbz - EPS) continue;
            double lf = dx * fx + dz * fz, lr = dx * rx + dz * rz;
            if (Math.abs(lf) >= hl + hbx * Math.abs(fx) + hbz * Math.abs(fz) - EPS) continue;
            if (Math.abs(lr) >= hw + hbx * Math.abs(rx) + hbz * Math.abs(rz) - EPS) continue;
            // the plane where the box meets the hull
            double cf = lf < -hl ? -hl : lf > hl ? hl : lf, cr = lr < -hw ? -hw : lr > hw ? hw : lr;
            double plane = planeY + cf * slopeF + cr * slopeR;
            double top = b.maxY(i), bottom = b.minY(i);
            if (top <= plane + climbLimit + EPS || bottom >= plane + height - EPS) continue;
            hitX = cx + fx * cf + rx * cr; hitZ = cz + fz * cf + rz * cr;
            hitY = Math.max(bottom, plane) * 0.5 + Math.min(top, plane + height) * 0.5;
            hitRise = top - plane;
            hitKind = bottom > plane + fullStep ? Obstacle.CEILING : classify(hitRise, freeStep, fullStep);
            return true;
        }
        hitKind = Obstacle.NONE;
        return false;
    }

    /** Sub-block (slab, stair step, snow, trapdoor), full block, or wall. */
    public static Obstacle classify(double rise, double freeStep, double fullStep) {
        if (rise <= EPS) return Obstacle.NONE;
        if (rise <= freeStep + EPS) return Obstacle.SUB_BLOCK;
        if (rise <= fullStep + EPS) return Obstacle.FULL_BLOCK;
        return Obstacle.WALL;
    }

    /**
     * May a full-block ledge be climbed? Yes when the kinetic energy toward it is at least {@code energyFraction} of
     * the potential energy of the climb (m g rise), or when the drive force at the wheels is at least
     * {@code tractionRatio} of the vehicle's weight.
     */
    public static boolean canClimbFullBlock(double mass, double speedToward, double driveForce, double rise, double gravity, double energyFraction, double tractionRatio) {
        double kinetic = 0.5 * mass * speedToward * speedToward, potential = mass * gravity * rise;
        return kinetic >= energyFraction * potential || driveForce >= tractionRatio * mass * gravity;
    }

    /**
     * Look at the hull's footprint moved to (cx, cz), the place it is heading for: {@link #aheadRise} is the highest
     * full-block-class ledge there (0 when none) and {@link #aheadCeiling} tells whether the body, lifted by that
     * ledge, would be pressed into something above it (a box that starts above the full step and below the lifted
     * roof).
     */
    public void probeAhead(double cx, double cz, double fx, double fz, double rx, double rz, double hw, double hl,
                           double planeY, double slopeF, double slopeR, double height, double freeStep, double fullStep) {
        double ex = Math.abs(rx) * hw + Math.abs(fx) * hl, ez = Math.abs(rz) * hw + Math.abs(fz) * hl;
        double rise = 0, lowestRoof = Double.MAX_VALUE;
        BoxBuffer b = boxes;
        for (int i = 0, n = b.size(); i < n; i++) {
            double bx = (b.minX(i) + b.maxX(i)) * 0.5, bz = (b.minZ(i) + b.maxZ(i)) * 0.5;
            double hbx = (b.maxX(i) - b.minX(i)) * 0.5, hbz = (b.maxZ(i) - b.minZ(i)) * 0.5;
            double dx = bx - cx, dz = bz - cz;
            if (Math.abs(dx) >= ex + hbx - EPS || Math.abs(dz) >= ez + hbz - EPS) continue;
            double lf = dx * fx + dz * fz, lr = dx * rx + dz * rz;
            if (Math.abs(lf) >= hl + hbx * Math.abs(fx) + hbz * Math.abs(fz) - EPS) continue;
            if (Math.abs(lr) >= hw + hbx * Math.abs(rx) + hbz * Math.abs(rz) - EPS) continue;
            double cf = lf < -hl ? -hl : lf > hl ? hl : lf, cr = lr < -hw ? -hw : lr > hw ? hw : lr;
            double plane = planeY + cf * slopeF + cr * slopeR;
            double top = b.maxY(i) - plane, bottom = b.minY(i) - plane;
            if (bottom > fullStep + EPS) { if (bottom < lowestRoof) lowestRoof = bottom; continue; }
            if (top > freeStep + EPS && top <= fullStep + EPS && top > rise) rise = top;
        }
        aheadRise = rise;
        aheadCeiling = rise > 0 && lowestRoof < height + rise - EPS;
    }

    // ------------------------------------------------------------------ impulse

    /** Restitution of a hit at this closing speed: a bumper bounce at parking speed, nearly inelastic in a crash. */
    public static float restitution(float closing, float low, float high) {
        if (closing < 0.4f) return 0f;                    // touching: stop, do not rattle
        float t = Math.min(1f, closing / 8f);
        return low + (high - low) * t;
    }

    /**
     * Collision impulse against a fixed obstacle. {@code vel} is changed in place: the component along the normal
     * (nx, ny, nz; pointing from the obstacle to the vehicle) is reversed by the restitution. The angular impulse
     * r x J, taken through the inverse inertia tensor in the body frame, is ADDED to {@code angular} (rad/s, world).
     * Returns the closing speed that was stopped (blocks/s), 0 when the vehicle was not moving into the obstacle.
     */
    public static float impact(Vector3f vel, float nx, float ny, float nz, float restitution, float mass,
                               Vector3f arm, Vector3f inverseInertia, Quaternionf orientation, Vector3f angular, Vector3f scratch) {
        float vn = vel.x * nx + vel.y * ny + vel.z * nz;
        if (vn >= 0) return 0f;
        float j = -(1f + restitution) * mass * vn;                       // impulse magnitude along the normal (N s)
        vel.add(nx * j / mass, ny * j / mass, nz * j / mass);
        // angular impulse L = r x (j n), into the body frame, through I^-1, back to the world
        scratch.set(arm.y * nz - arm.z * ny, arm.z * nx - arm.x * nz, arm.x * ny - arm.y * nx).mul(j);
        orientation.transformInverse(scratch);
        scratch.mul(inverseInertia);
        orientation.transform(scratch);
        angular.add(scratch);
        return -vn;
    }

    // ------------------------------------------------------------------ Bukkit world

    /**
     * {@link VoxelSource} backed by a Bukkit world. Never loads a chunk. Shapes are cached per block state (a slab,
     * a stair or a fence has the same boxes wherever it stands), so in steady state a query costs one type lookup per
     * block and one state lookup per solid block; blocks whose shape depends on where they are or on a block entity
     * (bamboo, dripstone, scaffolding, powder snow, moving pistons, shulker boxes) are always asked directly. The
     * cache is bounded: it is cleared when it reaches {@link #SHAPE_CACHE_MAX} states.
     */
    public static final class WorldVoxels implements VoxelSource {
        public static final int SHAPE_CACHE_MAX = 4096;
        private static final double[] NO_BOXES = new double[0];

        private final World world;
        private final HashMap<BlockData, double[]> shapes = new HashMap<>();
        private final EnumMap<Material, Surface> surfaces = new EnumMap<>(Material.class);
        private final EnumSet<Material> uncached = EnumSet.noneOf(Material.class);
        private final EnumSet<Material> hazards = EnumSet.noneOf(Material.class);
        private final Location scratch;

        public WorldVoxels(World world) {
            this.world = world;
            this.scratch = new Location(world, 0, 0, 0);
            for (String n : new String[]{"BAMBOO", "POINTED_DRIPSTONE", "SCAFFOLDING", "POWDER_SNOW", "MOVING_PISTON"}) { Material m = Material.getMaterial(n); if (m != null) uncached.add(m); }
            try { uncached.addAll(Tag.SHULKER_BOXES.getValues()); } catch (RuntimeException ignored) { }
            hazards(java.util.List.of("LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS", "SWEET_BERRY_BUSH"));
        }

        public World world() { return world; }
        public int cachedShapes() { return shapes.size(); }

        /** Replace the hazard list (config seats.dismount.hazards). */
        public void hazards(Collection<String> names) {
            hazards.clear();
            for (String n : names) { Material m = Material.matchMaterial(n); if (m != null) hazards.add(m); }
        }

        /** Surface overrides from config physics.surfaces: material name -> {grip, rolling}. */
        public void surface(Material m, float grip, float rolling) { surfaces.put(m, new Surface(m.name(), grip, rolling)); }

        @Override public boolean collect(int x0, int y0, int z0, int x1, int y1, int z1, BoxBuffer out) {
            y0 = Math.max(y0, world.getMinHeight()); y1 = Math.min(y1, world.getMaxHeight() - 1);
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 60000) return false;   // absurd region
            boolean all = true;
            int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
            boolean loaded = false;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                int cx = x >> 4, cz = z >> 4;
                if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; loaded = world.isChunkLoaded(cx, cz); }
                if (!loaded) { all = false; continue; }
                for (int y = y0; y <= y1; y++) {
                    Material m = world.getType(x, y, z);
                    if (!m.isCollidable()) continue;
                    double[] sh = shape(m, x, y, z);
                    if (sh.length == 0) continue;
                    Surface surf = surfaceOf(m);
                    for (int i = 0; i < sh.length; i += 6) out.add(x + sh[i], y + sh[i + 1], z + sh[i + 2], x + sh[i + 3], y + sh[i + 4], z + sh[i + 5], surf);
                }
            }
            return all;
        }

        private double[] shape(Material m, int x, int y, int z) {
            if (uncached.contains(m)) return pack(world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes());
            BlockData data = world.getBlockData(x, y, z);
            double[] sh = shapes.get(data);
            if (sh == null) {
                if (shapes.size() >= SHAPE_CACHE_MAX) shapes.clear();
                scratch.set(x, y, z);
                sh = pack(data.getCollisionShape(scratch).getBoundingBoxes());
                shapes.put(data, sh);
            }
            return sh;
        }

        private static double[] pack(Collection<BoundingBox> bbs) {
            if (bbs.isEmpty()) return NO_BOXES;
            double[] out = new double[bbs.size() * 6];
            int i = 0;
            for (BoundingBox b : bbs) { out[i++] = b.getMinX(); out[i++] = b.getMinY(); out[i++] = b.getMinZ(); out[i++] = b.getMaxX(); out[i++] = b.getMaxY(); out[i++] = b.getMaxZ(); }
            return out;
        }

        /**
         * Grip and rolling resistance of a block. Grip follows the block's own slipperiness (0.6 = normal -> 1.0,
         * ice 0.98 -> 0.05); loose and soft ground rolls harder and grips less. Config physics.surfaces overrides.
         */
        private Surface surfaceOf(Material m) {
            Surface s = surfaces.get(m);
            if (s != null) return s;
            float slip = 0.6f;
            try { slip = m.getSlipperiness(); } catch (RuntimeException ignored) { }
            float grip = Math.max(0.05f, Math.min(1f, (1f - slip) / 0.4f)), rolling = 1f;
            String n = m.name();
            if (n.endsWith("SAND") || n.equals("GRAVEL") || n.endsWith("CONCRETE_POWDER")) { grip *= 0.6f; rolling = 4f; }
            else if (n.equals("MUD") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL") || n.equals("CLAY") || n.equals("FARMLAND")) { grip *= 0.45f; rolling = 6f; }
            else if (n.equals("SNOW") || n.equals("SNOW_BLOCK") || n.equals("POWDER_SNOW")) { grip *= 0.35f; rolling = 3f; }
            else if (n.equals("GRASS_BLOCK") || n.endsWith("DIRT") || n.equals("PODZOL") || n.equals("MYCELIUM") || n.equals("MOSS_BLOCK") || n.equals("DIRT_PATH")) { grip *= 0.8f; rolling = 1.8f; }
            s = new Surface(n, grip, rolling);
            surfaces.put(m, s);
            return s;
        }

        private boolean loadedAt(int x, int y, int z) { return y >= world.getMinHeight() && y < world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4); }
        @Override public boolean water(int x, int y, int z) { return loadedAt(x, y, z) && world.getType(x, y, z) == Material.WATER; }
        @Override public boolean hazard(int x, int y, int z) { return loadedAt(x, y, z) && hazards.contains(world.getType(x, y, z)); }
    }
}
