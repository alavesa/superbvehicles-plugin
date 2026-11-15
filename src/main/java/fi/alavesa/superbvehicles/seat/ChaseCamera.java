package fi.alavesa.superbvehicles.seat;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.Obb;
import fi.alavesa.superbvehicles.render.Pose;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Third-person chase view for pilots (helicopters by default). The rider's seat stand is moved behind and above the
 * vehicle along the rider's own view direction, like the vanilla F5 view: the client still turns the head itself, so
 * looking around stays smooth, and the vehicle stays in the middle of the screen. A block between the vehicle and the
 * camera pulls the camera closer (no looking through walls).
 * <p>Everyone else must not see the pilot floating behind the aircraft, so while the chase view is on the pilot is
 * invisible (a short, self-refreshing invisibility effect, so nothing lingers after a crash), other players get the
 * pilot's armour and hands sent as empty, and a {@link Mannequin} with the pilot's skin, name and equipment sits in
 * the real seat. The tab list is not touched.
 * <p>Zoom: the mouse wheel moves the camera closer / further. Scrolling only changes the hotbar slot, so while the
 * chase view is on the held slot is pinned to camera.scroll-slot (default 7th): one slot up or down from it is a
 * scroll step and is undone at once; the number keys 1-5 still select weapons. The real slot comes back afterwards.
 * <p>Per-player choice: /sbv camera chase|cockpit and the zoom, saved in data/camera-players.yml.
 */
public final class ChaseCamera {
    private static final int INVIS_TICKS = 60;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final class Active {
        final UUID player; ArmorStand seat; Mannequin standIn; int age; int savedSlot = -1;
        Active(UUID player) { this.player = player; }
    }

    private final SuperbVehiclesPlugin plugin;
    private final File prefsFile;
    private final Map<UUID, Boolean> prefs = new HashMap<>();
    private final Map<UUID, Double> zoom = new HashMap<>();
    private final Map<String, Active> active = new HashMap<>();   // vehicle id # seat index

    public ChaseCamera(SuperbVehiclesPlugin plugin) {
        this.plugin = plugin;
        prefsFile = new File(plugin.getDataFolder(), "data/camera-players.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(prefsFile);
        for (String k : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                if (y.isConfigurationSection(k)) {          // 0.7: {mode, zoom}
                    String m = y.getString(k + ".mode");
                    if (m != null) prefs.put(id, "chase".equalsIgnoreCase(m));
                    if (y.contains(k + ".zoom")) zoom.put(id, y.getDouble(k + ".zoom"));
                } else prefs.put(id, "chase".equalsIgnoreCase(y.getString(k)));   // 0.6: mode only
            } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------------ preferences

    /** Does this rider get the chase view in this seat? */
    public boolean wants(Player p, Vehicle v, Vehicle.SeatSlot s) {
        if (p == null || !plugin.flag("camera.enabled", true) || v.wreck) return false;
        List<String> classes = plugin.getConfig().getStringList("camera.vehicles");
        if (classes.isEmpty()) classes = List.of("helicopter");
        if (!classes.contains(v.type.vehicleClass.name().toLowerCase(Locale.ROOT))) return false;
        String seats = plugin.getConfig().getString("camera.seats", "driver");
        if (!"all".equalsIgnoreCase(seats) && s.def.role != VehicleType.SeatRole.DRIVER) return false;
        if (!v.type.physicsFlag("chase-camera", true)) return false;
        Boolean pref = prefs.get(p.getUniqueId());
        return pref != null ? pref : !"cockpit".equalsIgnoreCase(plugin.getConfig().getString("camera.default", "chase"));
    }

    public Boolean preference(Player p) { return prefs.get(p.getUniqueId()); }

    public void set(Player p, boolean chase) { prefs.put(p.getUniqueId(), chase); save(); }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : prefs.entrySet()) y.set(e.getKey() + ".mode", e.getValue() ? "chase" : "cockpit");
        for (var e : zoom.entrySet()) y.set(e.getKey() + ".zoom", Math.round(e.getValue() * 1000) / 1000.0);
        try { prefsFile.getParentFile().mkdirs(); y.save(prefsFile); } catch (Exception ex) { plugin.getLogger().warning("could not save " + prefsFile + ": " + ex); }
    }

    // ------------------------------------------------------------------ per tick

    /**
     * Called by {@link Vehicle#tickSeats()} for every seat. Returns where the seat stand goes this tick: the chase
     * position for a chase rider, else {@code seatLoc} unchanged. Starts / stops the stand-in as riders come and go.
     */
    public Location place(Vehicle v, Vehicle.SeatSlot s, Location seatLoc) {
        String key = v.id + "#" + s.index;
        Player rider = s.rider();
        boolean chase = wants(rider, v, s);
        Active a = active.get(key);
        if (a != null && (!chase || !a.player.equals(rider.getUniqueId()))) { stop(a); active.remove(key); a = null; }
        if (!chase) return seatLoc;
        if (a == null) {
            a = new Active(rider.getUniqueId()); active.put(key, a);
            if (scrollZoom()) { a.savedSlot = rider.getInventory().getHeldItemSlot(); rider.getInventory().setHeldItemSlot(pinnedSlot()); }
        }
        a.age++;
        keepHidden(v, a, rider, seatLoc);
        return chaseLocation(v, rider);
    }

    /** Behind the vehicle along the rider's view, pulled in front of any block in between. */
    Location chaseLocation(Vehicle v, Player rider) {
        Obb h = v.hull();
        double size = Math.max(h.half.x, Math.max(h.half.y, h.half.z));
        double dist = plugin.cfg("camera.distance", 0);
        if (dist <= 0) dist = Pose.clamp(size * 2.2 + 4, 6, 18);
        dist = Pose.clamp(dist * zoom.getOrDefault(rider.getUniqueId(), 1.0), plugin.cfg("camera.min-zoom-distance", 2.5), plugin.cfg("camera.max-zoom-distance", 40));
        Vector3d pivot = new Vector3d(h.center).add(0, h.half.y + plugin.cfg("camera.height", 1.2), 0);
        pivot.y += v.phys.visualYOffset;
        float yaw = rider.getLocation().getYaw();
        float pitch = (float) Pose.clamp(rider.getLocation().getPitch(), plugin.cfg("camera.min-pitch", -25), plugin.cfg("camera.max-pitch", 75));
        Vector look = new Location(v.world, 0, 0, 0, yaw, pitch).getDirection();
        Vector back = look.clone().multiply(-1);
        Location from = new Location(v.world, pivot.x, pivot.y, pivot.z);
        RayTraceResult hit = v.world.rayTraceBlocks(from, back, dist + 0.5, FluidCollisionMode.NEVER, true);
        if (hit != null) dist = Math.max(plugin.cfg("camera.min-distance", 1.5), hit.getHitPosition().distance(from.toVector()) - 0.5);
        Vector eye = from.toVector().add(back.multiply(dist));
        // the stand sits below the eye: player eye = stand + (eye height 1.62 - riding offset 0.6)
        double eyeAbove = plugin.cfg("camera.eye-above-stand", 1.02);
        return new Location(v.world, eye.getX(), eye.getY() - eyeAbove, eye.getZ(), yaw, 0f);
    }

    private void keepHidden(Vehicle v, Active a, Player rider, Location seatLoc) {
        if (!plugin.flag("camera.stand-in", true)) return;
        // invisibility that runs out by itself unless refreshed (never left behind by a crash or a kick)
        PotionEffect cur = rider.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (cur == null || (cur.isAmbient() && !cur.hasParticles() && cur.getDuration() < INVIS_TICKS - 15))
            rider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, INVIS_TICKS, 0, true, false, false));
        // other players: the pilot's equipment is sent as empty (armour and held items render on invisible players)
        if (a.age % 5 == 1) {
            Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot sl : SLOTS) empty.put(sl, ItemStack.empty());
            for (Player o : rider.getWorld().getPlayers()) if (o != rider && o.getLocation().distanceSquared(rider.getLocation()) < 128 * 128) o.sendEquipmentChange(rider, empty);
        }
        // the stand-in in the real seat
        if (a.seat == null || !a.seat.isValid()) {
            a.seat = v.world.spawn(seatLoc, ArmorStand.class, st -> {
                st.setInvisible(true); st.setMarker(true); st.setSmall(true); st.setGravity(false); st.setBasePlate(false); st.setInvulnerable(true); st.setPersistent(false); st.setSilent(true); st.setCollidable(false);
                tag(v, st);
            });
        } else a.seat.teleport(seatLoc, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        if (a.standIn == null || !a.standIn.isValid()) {
            a.standIn = v.world.spawn(seatLoc, Mannequin.class, m -> {
                m.setProfile(ResolvableProfile.resolvableProfile(rider.getPlayerProfile()));
                m.setImmovable(true); m.setInvulnerable(true); m.setPersistent(false); m.setSilent(true); m.setGravity(false); m.setCollidable(false); m.setAI(false);
                m.customName(rider.name()); m.setCustomNameVisible(true);
                try { m.setDescription(null); } catch (RuntimeException ex) { m.setDescription(net.kyori.adventure.text.Component.empty()); }
                tag(v, m);
            });
            a.seat.addPassenger(a.standIn);
        }
        if (a.age % 10 == 1) copyEquipment(rider, a.standIn);
        a.standIn.setRotation(seatLoc.getYaw(), (float) Pose.clamp(rider.getLocation().getPitch(), -40, 40));
    }

    private void tag(Vehicle v, org.bukkit.entity.Entity e) {
        e.addScoreboardTag(Keys.TAG_ENTITY);
        e.getPersistentDataContainer().set(plugin.keys().vehicleId, PersistentDataType.STRING, v.id.toString());
    }

    private static void copyEquipment(Player from, Mannequin to) {
        EntityEquipment src = from.getEquipment(), dst = to.getEquipment();
        for (EquipmentSlot sl : SLOTS) {
            ItemStack it = src.getItem(sl);
            ItemStack now = dst.getItem(sl);
            if (!it.equals(now)) dst.setItem(sl, it.clone());
        }
    }

    private void stop(Active a) {
        if (a.standIn != null) a.standIn.remove();
        if (a.seat != null) a.seat.remove();
        Player p = plugin.getServer().getPlayer(a.player);
        if (p == null) return;
        if (a.savedSlot >= 0 && p.getInventory().getHeldItemSlot() == pinnedSlot()) p.getInventory().setHeldItemSlot(a.savedSlot);
        PotionEffect cur = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (cur != null && cur.isAmbient() && !cur.hasParticles() && cur.getDuration() <= INVIS_TICKS) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot sl : SLOTS) real.put(sl, p.getEquipment().getItem(sl));
        for (Player o : p.getWorld().getPlayers()) if (o != p) o.sendEquipmentChange(p, real);
    }

    private boolean scrollZoom() { return plugin.flag("camera.scroll-zoom", true); }
    /** The hotbar slot (0-8) held while in the chase view; scrolling away from it zooms. */
    public int pinnedSlot() { return (int) Pose.clamp(plugin.cfg("camera.scroll-slot", 7) - 1, 1, 7); }

    /**
     * A hotbar change from a chase-view rider. Returns true when it was a zoom step (the event must be cancelled so
     * the slot snaps back); weapon keys pass through. Slots right next to the pinned one are scroll steps; two above
     * (fast scrolling) counts twice.
     */
    public boolean scroll(Player p, int prev, int next) {
        if (!scrollZoom() || !active(p)) return false;
        int pin = pinnedSlot();
        if (prev != pin) return false;
        int steps = next - pin;
        if (steps == 0 || Math.abs(steps) > 2 || next < pin - 1) return false;   // a number key further away = weapon choice
        double step = plugin.cfg("camera.zoom-step", 1.15);
        double z = zoom.getOrDefault(p.getUniqueId(), 1.0) * Math.pow(step, steps);   // scroll down (next slot) = further out
        z = Pose.clamp(z, plugin.cfg("camera.min-zoom", 0.3), plugin.cfg("camera.max-zoom", 3.0));
        zoom.put(p.getUniqueId(), z);
        plugin.messages().actionBar(p, "camera-zoom", fi.alavesa.superbvehicles.core.Messages.num("percent", Math.round(z * 100)));
        if (++zoomSaves % 8 == 0) save();
        return true;
    }
    private int zoomSaves;

    /** /sbv camera zoom <percent>. */
    public double setZoom(Player p, double factor) {
        double z = Pose.clamp(factor, plugin.cfg("camera.min-zoom", 0.3), plugin.cfg("camera.max-zoom", 3.0));
        zoom.put(p.getUniqueId(), z); save();
        return z;
    }

    /** Is this player in the chase view right now? */
    public boolean active(Player p) {
        for (Active a : active.values()) if (a.player.equals(p.getUniqueId())) return true;
        return false;
    }

    /** Player leaves (quit / exit): restore them now, while the Player object is still live. */
    public void release(Player p) {
        for (Iterator<Active> it = active.values().iterator(); it.hasNext(); ) { Active a = it.next(); if (a.player.equals(p.getUniqueId())) { stop(a); it.remove(); } }
    }

    /** A vehicle despawns or is removed. */
    public void release(Vehicle v) {
        String prefix = v.id + "#";
        for (Iterator<Map.Entry<String, Active>> it = active.entrySet().iterator(); it.hasNext(); ) { var e = it.next(); if (e.getKey().startsWith(prefix)) { stop(e.getValue()); it.remove(); } }
    }

    public void shutdown() { for (Active a : active.values()) stop(a); active.clear(); save(); }
}
