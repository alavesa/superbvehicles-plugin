package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.render.Pose;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

/**
 * data/vehicles.yml: every vehicle's type, world, pose, health, fuel, ammo, owner, lock and skin. Saved on a timer
 * and on disable, loaded on enable. Our entities are never saved by the server (persistent = false), so a restart
 * can only ever bring back what is in this file: no duplicates.
 */
public final class Persistence {
    private final SuperbVehiclesPlugin plugin;
    private final File file;

    public Persistence(SuperbVehiclesPlugin plugin) {
        this.plugin = plugin;
        file = new File(new File(plugin.getDataFolder(), "data"), "vehicles.yml");
        long interval = Math.max(10, (long) plugin.cfg("persistence.save-interval-seconds", 120)) * 20;
        plugin.sched().repeat(interval, interval, this::save);
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        int n = 0, skipped = 0;
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            VehicleType type = plugin.types().get(s.getString("type"));
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (type == null || w == null) { skipped++; continue; }
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { skipped++; continue; }
            if (plugin.vehicles().byId(id) != null) continue;
            Pose pose = new Pose();
            pose.x = s.getDouble("x"); pose.y = s.getDouble("y"); pose.z = s.getDouble("z"); pose.yaw = s.getDouble("yaw"); pose.modelYaw = type.modelYaw;
            Vehicle v = new Vehicle(plugin, id, type, w, pose);
            v.health = Math.max(1, Math.min(type.health, s.getDouble("health", type.health)));
            v.fuel = Math.max(0, Math.min(type.fuelCapacity, s.getDouble("fuel", type.fuelCapacity)));
            v.skin = s.getString("skin", "default");
            v.locked = s.getBoolean("locked", false);
            String owner = s.getString("owner");
            if (owner != null && !owner.isEmpty()) try { v.owner = UUID.fromString(owner); } catch (IllegalArgumentException ignored) { }
            ConfigurationSection ammo = s.getConfigurationSection("ammo");
            if (ammo != null) for (String wn : ammo.getKeys(false)) { MountedWeapon mw = v.weapons.get(wn); if (mw != null) { mw.magazine = ammo.getInt(wn + ".mag", mw.magazine); mw.reserve = ammo.getInt(wn + ".reserve", mw.reserve); } }
            plugin.vehicles().adopt(v);
            n++;
        }
        plugin.getLogger().info("Restored " + n + " vehicle(s)" + (skipped > 0 ? ", skipped " + skipped + " (unknown type or world)" : "") + ".");
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Vehicle v : plugin.vehicles().all()) {
            if (v.removed || v.wreck) continue;
            String k = v.id.toString();
            y.set(k + ".type", v.type.id);
            y.set(k + ".world", v.world.getName());
            y.set(k + ".x", v.pose.x); y.set(k + ".y", v.pose.y); y.set(k + ".z", v.pose.z); y.set(k + ".yaw", v.pose.yaw);
            y.set(k + ".health", v.health); y.set(k + ".fuel", v.fuel);
            y.set(k + ".skin", v.skin); y.set(k + ".locked", v.locked);
            y.set(k + ".owner", v.owner == null ? "" : v.owner.toString());
            for (MountedWeapon w : v.weapons.values()) { y.set(k + ".ammo." + w.type.name + ".mag", w.magazine); y.set(k + ".ammo." + w.type.name + ".reserve", w.reserve); }
        }
        try { file.getParentFile().mkdirs(); y.save(file); } catch (Exception e) { plugin.getLogger().warning("could not save data/vehicles.yml: " + e); }
    }
}
