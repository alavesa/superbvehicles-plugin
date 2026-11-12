package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Public API: {@code SuperbVehiclesAPI.get()} once the plugin is enabled. */
public final class SuperbVehiclesAPI {
    private static SuperbVehiclesAPI instance;
    private final SuperbVehiclesPlugin plugin;
    private SuperbVehiclesAPI(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }
    public static void init(SuperbVehiclesPlugin p) { instance = new SuperbVehiclesAPI(p); }
    public static SuperbVehiclesAPI get() { if (instance == null) throw new IllegalStateException("SuperbVehicles is not enabled"); return instance; }

    /** Register a type from a configuration section (same schema as vehicles/*.yml). */
    public VehicleType registerType(String id, ConfigurationSection yaml, boolean rebuildPack) {
        VehicleType t = new VehicleType(id, yaml, null);
        plugin.types().register(t);
        if (rebuildPack) plugin.rebuildPack(null);
        return t;
    }
    public boolean unregisterType(String id) { return plugin.types().unregister(id); }
    public VehicleType type(String id) { return plugin.types().get(id); }
    public Collection<VehicleType> types() { return plugin.types().all(); }

    public Vehicle spawn(VehicleType type, Location at, UUID owner) { return plugin.vehicles().spawn(type, at, owner); }
    public Vehicle spawn(String typeId, Location at, UUID owner) { VehicleType t = type(typeId); return t == null ? null : spawn(t, at, owner); }
    public void remove(Vehicle v) { plugin.vehicles().remove(v, false); }
    public Collection<Vehicle> vehicles() { return plugin.vehicles().all(); }
    public Vehicle byId(UUID id) { return plugin.vehicles().byId(id); }
    public Vehicle byEntity(Entity e) { return plugin.vehicles().byEntity(e); }
    public Vehicle byPlayer(Player p) { return plugin.vehicles().byPlayer(p); }
    public List<Vehicle> nearby(Location l, double radius) { return plugin.vehicles().nearby(l, radius); }

    /** Lower a vehicle's health from another plugin. */
    public void damage(Vehicle v, double amount, DamageType type, Entity source, Vector3d hitPoint, Vector3d hitDirection, double penetration) {
        plugin.damage().apply(v, amount, type, source, hitPoint, hitDirection, penetration);
    }
    /** The first vehicle hull a ray crosses, or null. */
    public RayHit rayTrace(Location from, Vector3d dir, double range) { return plugin.vehicles().rayTrace(from, dir, range, null); }
    public record RayHit(Vehicle vehicle, Vector3d point, double distance) { }

    public ItemStack spawnItem(VehicleType type, int amount) { return plugin.items().spawnItem(type, amount); }
    public void rebuildPack() { plugin.rebuildPack(null); }
}
