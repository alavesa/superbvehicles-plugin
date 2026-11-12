package fi.alavesa.superbvehicles.weapon;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Owns every in-flight round: global and per-vehicle caps, one tick per round, cleanup on disable. */
public final class ProjectileManager {
    private final SuperbVehiclesPlugin plugin;
    private final List<Projectile> live = new ArrayList<>();

    public ProjectileManager(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public List<Projectile> all() { return live; }
    public int countFor(Vehicle v) { int n = 0; for (Projectile p : live) if (p.shooter == v) n++; return n; }

    public Projectile launch(Vehicle shooter, MountedWeapon weapon, Vector3d from, Vector3d vel, Entity lockTarget, Player gunner) {
        if (live.size() >= plugin.cfg("weapons.max-projectiles", 400)) return null;
        Projectile p = new Projectile(plugin, shooter, weapon, from, vel, lockTarget, gunner);
        live.add(p);
        return p;
    }

    public void tick() {
        Iterator<Projectile> it = live.iterator();
        while (it.hasNext()) {
            Projectile p = it.next();
            boolean alive;
            try { alive = p.tick(); } catch (Exception e) { plugin.getLogger().warning("projectile tick failed: " + e); alive = false; }
            if (!alive) { p.dispose(); it.remove(); }
        }
    }

    /** Remove rounds in a radius (a CIWS intercept); returns how many. */
    public int destroyNear(Vector3d at, double radius, Vehicle except) {
        int n = 0;
        Iterator<Projectile> it = live.iterator();
        while (it.hasNext()) { Projectile p = it.next(); if (p.shooter != except && p.pos.distance(at) <= radius) { p.pop(); p.dispose(); it.remove(); n++; } }
        return n;
    }

    public void clear() { for (Projectile p : live) p.dispose(); live.clear(); }
}
