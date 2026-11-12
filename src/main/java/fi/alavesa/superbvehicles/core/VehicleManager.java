package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.api.SuperbVehiclesAPI;
import fi.alavesa.superbvehicles.api.VehicleSpawnEvent;
import fi.alavesa.superbvehicles.physics.BlockCollider;
import fi.alavesa.superbvehicles.physics.Obb;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every live vehicle: spawning, the 20 TPS tick loop (physics, weapons, rendering, projectiles, HUD), lookups by
 * entity / player / id / ray, chunk load + unload (dormant records keep their state), orphan sweeps (entities with
 * our tag that belong to no vehicle, e.g. after a crash), and performance accounting for /sbv debug perf.
 */
public final class VehicleManager implements Listener {
    private final SuperbVehiclesPlugin plugin;
    private final Map<UUID, Vehicle> vehicles = new LinkedHashMap<>();
    private final Map<UUID, Vehicle> byEntity = new HashMap<>();
    private final Map<World, BlockCollider> colliders = new HashMap<>();
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask task;
    private int tick;
    public long lastTickNanos, maxTickNanos;

    public VehicleManager(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public void start() {
        task = plugin.sched().repeat(1, 1, this::tickAll);
        plugin.sched().repeat(100, 100, this::sweepOrphans);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Vehicle v : new ArrayList<>(vehicles.values())) { plugin.seats().ejectAll(v, true); v.despawnEntities(); }
        vehicles.clear(); byEntity.clear();
    }

    public BlockCollider collider(World w) { return colliders.computeIfAbsent(w, BlockCollider::new); }

    // ------------------------------------------------------------------ spawn / remove

    public Vehicle spawn(VehicleType type, Location at, UUID owner) { return spawn(type, at, owner, UUID.randomUUID(), false); }

    public Vehicle spawn(VehicleType type, Location at, UUID owner, UUID id, boolean restored) {
        Pose pose = new Pose(at, type.modelYaw);
        Vehicle v = new Vehicle(plugin, id, type, at.getWorld(), pose);
        v.owner = owner;
        vehicles.put(v.id, v);
        if (at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) { v.spawnEntities(); index(v); } else v.dormant = true;
        Bukkit.getPluginManager().callEvent(new VehicleSpawnEvent(v, restored));
        return v;
    }

    /** Register a record restored from disk (entities appear when its chunk loads). */
    public void adopt(Vehicle v) {
        vehicles.put(v.id, v);
        Location at = v.location();
        if (at.getWorld() != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) { v.spawnEntities(); index(v); v.dormant = false; } else v.dormant = true;
        Bukkit.getPluginManager().callEvent(new VehicleSpawnEvent(v, true));
    }

    private void index(Vehicle v) { for (Entity e : v.entities()) byEntity.put(e.getUniqueId(), v); }
    private void unindex(Vehicle v) { byEntity.values().removeIf(x -> x == v); }

    /** Remove a vehicle for good (riders out, entities gone, record dropped). */
    public void remove(Vehicle v, boolean effects) {
        if (v == null || v.removed) return;
        v.removed = true;
        plugin.seats().ejectAll(v, true);
        if (effects && v.spawned()) { Vector3d c = v.hull().center; v.world.spawnParticle(Particle.CLOUD, c.x, c.y, c.z, 20, 0.6, 0.4, 0.6, 0.02); }
        v.despawnEntities();
        unindex(v);
        vehicles.remove(v.id);
        plugin.hud().forget(v);
    }

    public int removeAll(VehicleType type) {
        int n = 0;
        for (Vehicle v : new ArrayList<>(vehicles.values())) if (type == null || v.type == type) { remove(v, true); n++; }
        return n;
    }

    // ------------------------------------------------------------------ lookups

    public Collection<Vehicle> all() { return vehicles.values(); }
    public Vehicle byId(UUID id) { return vehicles.get(id); }

    public Vehicle byEntity(Entity e) {
        if (e == null) return null;
        Vehicle v = byEntity.get(e.getUniqueId());
        if (v != null) return v;
        String s = e.getPersistentDataContainer().get(plugin.keys().vehicleId, PersistentDataType.STRING);
        if (s == null) return null;
        try { return vehicles.get(UUID.fromString(s)); } catch (IllegalArgumentException ex) { return null; }
    }

    public Vehicle byPlayer(Player p) {
        if (p == null) return null;
        Entity ride = p.getVehicle();
        return ride == null ? null : byEntity(ride);
    }

    public List<Vehicle> nearby(Location l, double radius) {
        List<Vehicle> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Vehicle v : vehicles.values()) if (!v.dormant && v.world == l.getWorld() && v.pose.position().distanceSquared(l.getX(), l.getY(), l.getZ()) <= r2) out.add(v);
        return out;
    }

    /** The vehicle a ray (from a player's eyes, say) crosses first. */
    public SuperbVehiclesAPI.RayHit rayTrace(Location from, Vector3d dir, double range, Vehicle except) {
        Vector3d o = new Vector3d(from.getX(), from.getY(), from.getZ()), d = new Vector3d(dir).normalize();
        Vehicle best = null; double bestD = range;
        for (Vehicle v : vehicles.values()) {
            if (v == except || v.dormant || v.world != from.getWorld()) continue;
            Obb h = v.hull();
            if (h.center.distance(o) > range + 20) continue;
            double[] r = h.rayHit(o, d, bestD);
            if (r != null && r[0] < bestD) { bestD = r[0]; best = v; }
        }
        return best == null ? null : new SuperbVehiclesAPI.RayHit(best, new Vector3d(o).add(d.mul(bestD)), bestD);
    }

    /** The vehicle a player means: the one they sit in, else the one they look at within 12 blocks. */
    public Vehicle target(Player p) {
        Vehicle v = byPlayer(p);
        if (v != null) return v;
        var d = p.getEyeLocation().getDirection();
        SuperbVehiclesAPI.RayHit hit = rayTrace(p.getEyeLocation(), new Vector3d(d.getX(), d.getY(), d.getZ()), 12, null);
        return hit == null ? null : hit.vehicle();
    }

    // ------------------------------------------------------------------ tick

    private void tickAll() {
        long t0 = System.nanoTime();
        tick++;
        for (Vehicle v : new ArrayList<>(vehicles.values())) {
            if (v.removed || v.dormant) continue;
            try {
                if (!v.spawned()) { v.despawnEntities(); v.spawnEntities(); index(v); }
                v.tick(collider(v.world), tick);
            } catch (Exception e) {
                plugin.getLogger().warning("Vehicle tick failed for " + v + ": " + e);
                if (plugin.flag("debug.log-physics", false)) e.printStackTrace();
            }
        }
        try { plugin.projectiles().tick(); } catch (Exception e) { plugin.getLogger().warning("projectiles: " + e); }
        try { plugin.seats().tick(tick); } catch (Exception e) { plugin.getLogger().warning("seats: " + e); }
        try { plugin.hud().tick(tick); } catch (Exception e) { plugin.getLogger().warning("hud: " + e); }
        lastTickNanos = System.nanoTime() - t0;
        if (lastTickNanos > maxTickNanos) maxTickNanos = lastTickNanos;
        if (tick % 200 == 0) maxTickNanos = lastTickNanos;
    }

    /** The pack was rebuilt: re-read hitboxes and rebuild every body. */
    public void rigsChanged() {
        for (Vehicle v : vehicles.values()) if (v.spawned()) { v.bindRig(); v.renderer.rebuild(); unindex(v); index(v); }
    }

    /** Entities with our tag whose vehicle does not exist (left behind by a crash or a reload): remove them. */
    public void sweepOrphans() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) {
            if (!e.getScoreboardTags().contains(Keys.TAG_ENTITY)) continue;
            if (e.getScoreboardTags().contains(Keys.TAG_PROJECTILE)) continue;
            Vehicle v = byEntity(e);
            if (v == null || v.removed || !v.owns(e) || !v.entities().contains(e)) { for (Entity p : new ArrayList<>(e.getPassengers())) if (p instanceof Player) plugin.seats().forced(() -> e.removePassenger(p)); e.remove(); }
        }
    }

    // ------------------------------------------------------------------ chunks

    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) {
        if (!plugin.flag("persistence.despawn-unloaded", true)) return;
        Chunk c = e.getChunk();
        for (Vehicle v : vehicles.values()) {
            if (v.dormant || v.world != c.getWorld()) continue;
            if (((int) Math.floor(v.pose.x)) >> 4 != c.getX() || ((int) Math.floor(v.pose.z)) >> 4 != c.getZ()) continue;
            if (v.occupied()) continue;
            v.despawnEntities(); unindex(v); v.dormant = true;
        }
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        for (Vehicle v : vehicles.values()) {
            if (!v.dormant || v.world != c.getWorld()) continue;
            if (((int) Math.floor(v.pose.x)) >> 4 != c.getX() || ((int) Math.floor(v.pose.z)) >> 4 != c.getZ()) continue;
            plugin.sched().later(1, () -> { if (v.dormant && !v.removed) { v.spawnEntities(); index(v); v.dormant = false; } });
        }
    }

    @EventHandler public void onWorldUnload(WorldUnloadEvent e) {
        for (Vehicle v : new ArrayList<>(vehicles.values())) if (v.world == e.getWorld()) { v.despawnEntities(); unindex(v); v.dormant = true; }
        colliders.remove(e.getWorld());
    }

    public int tickCount() { return tick; }
}
