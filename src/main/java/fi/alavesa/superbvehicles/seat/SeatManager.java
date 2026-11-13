package fi.alavesa.superbvehicles.seat;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.api.VehicleEnterEvent;
import fi.alavesa.superbvehicles.api.VehicleExitEvent;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.Controls;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who sits where. Right-clicking a vehicle's click box takes the nearest free seat (the driver's first when
 * allowed); right-clicking another seat's box while seated switches. Sneak leaves - unless the vehicle is an
 * aircraft in the air (land, or double-tap sneak to bail out with slow falling) or a ground vehicle above the
 * dismount speed. Also keeps each player's last PlayerInputEvent state and the one-shot key edges for the physics.
 */
public final class SeatManager implements Listener {
    private static final class Keys { Input input; boolean swap, drop, attack, use; int hotbar = -1; int lastSneakTick = Integer.MIN_VALUE / 2; int bailUntil = -1; int hintAt = -100; }

    private final SuperbVehiclesPlugin plugin;
    private final Map<UUID, Keys> keys = new HashMap<>();
    private final InputCache inputs = new InputCache();
    private boolean forced;
    private int tick;

    public SeatManager(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public void tick(int serverTick) { tick = serverTick; }

    // ------------------------------------------------------------------ input bookkeeping

    private Keys keys(Player p) { return keys.computeIfAbsent(p.getUniqueId(), k -> new Keys()); }
    public Input inputOf(Player p) { return inputs.get(p.getUniqueId()); }

    public void input(Player p, Input in) {
        Keys k = keys(p);
        boolean wasSneak = k.input != null && k.input.isSneak();
        k.input = in;
        inputs.put(p.getUniqueId(), in);
        if (in.isSneak() && !wasSneak) {
            int window = (int) plugin.cfg("seats.bail-out-ticks", 8);
            if (tick - k.lastSneakTick <= window) k.bailUntil = tick + 3;
            k.lastSneakTick = tick;
        }
    }
    public void edge(Player p, String what) { Keys k = keys(p); switch (what) { case "swap" -> k.swap = true; case "drop" -> k.drop = true; case "attack" -> k.attack = true; case "use" -> k.use = true; default -> { } } }
    public void hotbar(Player p, int slot) { keys(p).hotbar = slot; }

    /** Move this tick's one-shot edges into the vehicle controls and clear them. */
    public void drainEdges(Player p, Controls c) {
        Keys k = keys.get(p.getUniqueId());
        if (k == null) return;
        c.swapHands = k.swap; c.drop = k.drop; c.attack = k.attack; c.use = k.use; c.hotbar = k.hotbar;
        k.swap = k.drop = k.attack = k.use = false; k.hotbar = -1;
    }

    /** Gunner seats: feed look + trigger to the selected weapon, hotbar selects, F = countermeasure. Called by HUD tick for every rider. */
    public void gunnerTick(Vehicle v, Vehicle.SeatSlot s, Player p) {
        Keys k = keys.get(p.getUniqueId());
        if (s.def.weapons.isEmpty()) { if (k != null) { k.swap = k.drop = k.attack = k.use = false; k.hotbar = -1; } return; }
        boolean driver = s.def.role == VehicleType.SeatRole.DRIVER;
        String cls = driver ? v.type.vehicleClass.name().toLowerCase(java.util.Locale.ROOT) : "gunner";
        String fireKey = plugin.getConfig().getString("controls." + cls + ".fire", driver ? "sprint" : "jump");
        String cmKey = plugin.getConfig().getString("controls." + cls + ".countermeasure", "swap_hands");
        if (k != null && k.hotbar >= 0 && k.hotbar < s.def.weapons.size() && k.hotbar != s.weaponIndex) {
            s.weaponIndex = k.hotbar;
            MountedWeapon w = v.weapons.get(s.def.weapons.get(s.weaponIndex));
            if (w != null) plugin.messages().actionBar(p, "weapon-selected", Messages.str("weapon", w.type.displayName));
        }
        if (s.weaponIndex >= s.def.weapons.size()) s.weaponIndex = 0;
        MountedWeapon w = v.weapons.get(s.def.weapons.get(s.weaponIndex));
        if (w == null) return;
        Input in = k == null ? null : k.input;
        boolean trigger = false, cm = false;
        if (in != null) {
            trigger = held(in, fireKey) || (k.attack && !driver);
            cm = pressed(k, cmKey);
        }
        if (driver) { /* the driver's edges are drained by the vehicle tick; only the held trigger is read here */ }
        else if (k != null) { k.attack = false; k.use = false; k.swap = false; k.drop = false; k.hotbar = -1; }
        w.control(p, trigger, cm);
        // a countermeasure weapon elsewhere in this seat's list also listens to its key
        for (String name : s.def.weapons) { MountedWeapon o = v.weapons.get(name); if (o != null && o != w && o.type.kind.countermeasure() && cm) o.control(p, false, true); }
    }

    private static boolean held(Input in, String key) {
        return switch (key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)) { case "jump", "space" -> in.isJump(); case "sprint", "ctrl" -> in.isSprint(); case "sneak" -> in.isSneak(); case "forward" -> in.isForward(); case "backward" -> in.isBackward(); default -> false; };
    }
    private static boolean pressed(Keys k, String key) {
        return switch (key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)) { case "swap_hands", "f" -> k.swap; case "drop", "q" -> k.drop; case "attack", "left_click" -> k.attack; case "use", "right_click" -> k.use; default -> false; };
    }

    public void forced(Runnable r) { boolean old = forced; forced = true; try { r.run(); } finally { forced = old; } }

    // ------------------------------------------------------------------ enter / exit

    public boolean enter(Player p, Vehicle v, Vehicle.SeatSlot seat) {
        if (v == null || v.wreck || v.removed || !v.spawned()) return false;
        if (!p.hasPermission(v.type.permissionUse) && !p.hasPermission("superbvehicles.use.*") && !p.hasPermission("superbvehicles.admin")) { plugin.messages().send(p, "no-permission"); return false; }
        if (v.locked && v.owner != null && !v.owner.equals(p.getUniqueId()) && !p.hasPermission("superbvehicles.admin")) { plugin.messages().send(p, "locked", Messages.str("name", v.displayName())); return false; }
        Vehicle.SeatSlot current = v.seatOf(p);
        if (seat == null) {
            // nearest free seat, driver first
            Vehicle.SeatSlot best = null; double bestD = Double.MAX_VALUE;
            Vector3d pp = new Vector3d(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ());
            for (Vehicle.SeatSlot s : v.seats) {
                if (!s.free() || s == current) continue;
                double d = v.seatWorld(s).distance(pp) - (s.def.role == VehicleType.SeatRole.DRIVER ? 1.5 : 0);
                if (d < bestD) { bestD = d; best = s; }
            }
            seat = best;
        }
        if (seat == null) { plugin.messages().send(p, "no-free-seat"); return false; }
        if (!seat.free()) { plugin.messages().send(p, "seat-taken"); return false; }
        VehicleEnterEvent ev = new VehicleEnterEvent(v, p, seat.index);
        plugin.getServer().getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        if (current != null) forced(() -> current.stand.removePassenger(p));
        else if (p.getVehicle() != null) p.leaveVehicle();
        v.tickSeats();
        if (!seat.stand.addPassenger(p)) return false;
        seat.enteredTick = tick;
        seat.weaponIndex = 0;
        // the engine starts when a driver takes the seat (physics.auto-start-engine, default true)
        if (seat.def.role == VehicleType.SeatRole.DRIVER && v.type.physicsFlag("auto-start-engine", true) && v.type.vehicleClass != VehicleClass.STATIC && !v.phys.engineOn && v.engineAvailable()) {
            v.phys.engineOn = true; v.phys.idleTicks = 0;
            v.playSound(v.type.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f);
        }
        keys(p).bailUntil = -1;
        plugin.messages().send(p, current != null ? "seat-switched" : "entered", Messages.str("seat", seat.def.name), Messages.str("name", v.displayName()));
        return true;
    }

    /** Take a player out of their seat (voluntary when !force). */
    public void exit(Player p, boolean force) {
        Vehicle v = plugin.vehicles().byPlayer(p);
        if (v == null) return;
        Vehicle.SeatSlot s = v.seatOf(p);
        forced(() -> { if (s != null && s.stand != null) s.stand.removePassenger(p); else p.leaveVehicle(); });
        if (s != null) plugin.getServer().getPluginManager().callEvent(new VehicleExitEvent(v, p, s.index, force));
        plugin.hud().forgetPlayer(p);
    }

    public void ejectAll(Vehicle v, boolean launch) {
        for (Vehicle.SeatSlot s : v.seats) {
            Player p = s.rider();
            if (p == null) continue;
            forced(() -> s.stand.removePassenger(p));
            plugin.getServer().getPluginManager().callEvent(new VehicleExitEvent(v, p, s.index, true));
            plugin.hud().forgetPlayer(p);
            if (launch) { p.setVelocity(new Vector(0, plugin.cfg("seats.eject-velocity", 1.2) * 0.5, 0)); }
        }
    }

    /** May this rider leave right now by sneaking? */
    public boolean mayLeave(Player p, Vehicle v) {
        if (forced || v.wreck || v.removed) return true;
        Keys k = keys.get(p.getUniqueId());
        boolean bail = k != null && tick <= k.bailUntil;
        if (v.type.vehicleClass.aircraft()) {
            if (v.phys.onGround && v.phys.speedBps() < 2) return true;
            if (bail) { bailOut(p, v); return true; }
            hint(p, k, "cannot-leave-air");
            return false;
        }
        double limit = plugin.cfg("seats.dismount-speed", 6.0);
        if (v.type.vehicleClass == VehicleClass.STATIC || v.phys.speedBps() <= limit) return true;
        if (bail) return true;
        hint(p, k, "cannot-leave-speed", Messages.num("speed", limit));
        return false;
    }

    private void hint(Player p, Keys k, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        if (k == null) k = keys(p);
        if (tick - k.hintAt > 40) { k.hintAt = tick; plugin.messages().actionBar(p, key, r); }
    }

    private void bailOut(Player p, Vehicle v) {
        plugin.messages().send(p, v.type.vehicleClass == VehicleClass.PLANE ? "ejected" : "bailed-out");
        plugin.sched().later(1, () -> {
            if (!p.isOnline()) return;
            p.setVelocity(new Vector(0, plugin.cfg("seats.eject-velocity", 1.2), 0).add(new Vector(v.phys.vel.x, 0, v.phys.vel.z).multiply(0.5 / fi.alavesa.superbvehicles.physics.Units.TPS)));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 25, 0, false, false, true));
        });
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = e.getRightClicked();
        Vehicle v = plugin.vehicles().byEntity(clicked);
        if (v == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (plugin.items().useOn(p, v)) return;              // fuel can / ammo crate / repair kit
        Vehicle mine = plugin.vehicles().byPlayer(p);
        if (mine != null && mine != v) return;               // sitting in another vehicle
        if (p.isSneaking() && !p.isInsideVehicle()) { if (plugin.items().pickUp(p, v)) return; }
        Vehicle.SeatSlot slot = v.seatOfEntity(clicked);
        if (mine == v && slot == null) return;
        if (mine == null && p.getLocation().distance(v.location()) > plugin.cfg("seats.enter-range", 4.5) + v.hull().half.length()) return;
        enter(p, v, slot != null && slot.free() ? slot : null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Vehicle v = plugin.vehicles().byEntity(e.getDismounted());
        if (v == null) return;
        if (forced) return;
        if (!mayLeave(p, v)) { e.setCancelled(true); return; }
        Vehicle.SeatSlot s = v.seatOfEntity(e.getDismounted());
        plugin.getServer().getPluginManager().callEvent(new VehicleExitEvent(v, p, s == null ? -1 : s.index, false));
        plugin.hud().forgetPlayer(p);
        // put the player beside the vehicle, not inside its hull
        plugin.sched().later(1, () -> {
            if (!p.isOnline() || p.isInsideVehicle()) return;
            Vector3d side = v.pose.rightFlat().mul(-(v.hull().half.x + 0.8));
            Location out = new Location(v.world, v.pose.x + side.x, v.pose.y + 0.5, v.pose.z + side.z, p.getLocation().getYaw(), p.getLocation().getPitch());
            if (out.getBlock().isPassable() && out.clone().add(0, 1, 0).getBlock().isPassable()) p.teleport(out);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Vehicle v = plugin.vehicles().byPlayer(p);
        if (v != null) exit(p, true);
        keys.remove(p.getUniqueId());
        inputs.forget(p.getUniqueId());
        plugin.hud().quit(p);
    }
}
