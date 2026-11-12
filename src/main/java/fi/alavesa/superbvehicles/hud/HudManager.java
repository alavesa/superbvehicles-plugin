package fi.alavesa.superbvehicles.hud;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.PhysicsState;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What riders see: an action-bar line (speed / altitude / fuel / health / weapon + ammo / lock / gear / flaps),
 * an optional boss bar (health or fuel), bar glyphs from the pack font when enabled, and the title flash for an
 * incoming missile lock. Also drives the gunner seats (look + trigger) each HUD tick.
 */
public final class HudManager {
    private final SuperbVehiclesPlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> lockWarnUntil = new HashMap<>();

    public HudManager(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public void tick(int tick) {
        int every = Math.max(1, (int) plugin.cfg("hud.update-ticks", 2));
        for (Vehicle v : plugin.vehicles().all()) {
            if (v.dormant || v.removed) continue;
            for (Vehicle.SeatSlot s : v.seats) {
                Player p = s.rider();
                if (p == null) continue;
                plugin.seats().gunnerTick(v, s, p);
                if (tick % every == 0 && plugin.flag("hud.enabled", true)) show(p, v, s, tick);
            }
        }
    }

    private void show(Player p, Vehicle v, Vehicle.SeatSlot s, int tick) {
        PhysicsState st = v.phys;
        VehicleType t = v.type;
        boolean glyphs = plugin.flag("hud.glyphs", true);
        Component line = Component.empty();
        if (v.wreck) line = Component.text("WRECK", NamedTextColor.DARK_RED);
        else if (t.vehicleClass != VehicleClass.STATIC) {
            double speed = t.vehicleClass.aircraft() ? st.airspeed : Math.abs(st.speed);
            float hue = (float) (0.33 - 0.33 * Math.min(1.0, speed / Math.max(1.0, t.maxSpeed)));
            line = line.append(Component.text(String.format(Locale.ROOT, "%.0f b/s", speed), TextColor.color(java.awt.Color.HSBtoRGB(hue, 0.8f, 1f))));
            if (!st.engineOn) line = line.append(Component.text("  ENGINE OFF", NamedTextColor.GRAY));
            else if (t.vehicleClass.aircraft() && st.spool < 0.95) line = line.append(Component.text(String.format(Locale.ROOT, "  spool %d%%", (int) (st.spool * 100)), NamedTextColor.YELLOW));
            else if (t.vehicleClass.ground()) line = line.append(Component.text("  " + (st.gear < 0 ? "R" : st.gear == 0 ? "N" : String.valueOf(st.gear)) + String.format(Locale.ROOT, " %d rpm", (int) st.rpm), NamedTextColor.GRAY));
            if (t.vehicleClass.aircraft()) {
                line = line.append(Component.text(String.format(Locale.ROOT, "  alt %d %s%.0f", (int) Math.floor(st.altitude), st.verticalSpeed > 0.5 ? "▲" : st.verticalSpeed < -0.5 ? "▼" : "•", Math.abs(st.verticalSpeed)), NamedTextColor.AQUA));
                if (t.vehicleClass == VehicleClass.PLANE) { line = line.append(Component.text(st.gearDown ? "  GEAR" : "  gear", st.gearDown ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)); if (st.flapsDown) line = line.append(Component.text(" FLAPS", NamedTextColor.GREEN)); if (st.stalled) line = line.append(Component.text(" STALL", NamedTextColor.RED)); }
            }
            if (plugin.flag("fuel.enabled", true) && t.fuelCapacity > 0) line = line.append(Component.text("  ⛽", v.fuelFraction() > 0.25 ? NamedTextColor.GRAY : NamedTextColor.RED)).append(bar(v.fuelFraction(), 1, glyphs, NamedTextColor.GOLD));
        }
        line = line.append(Component.text("  ❤", NamedTextColor.GRAY)).append(bar(v.health / v.maxHealth(), 0, glyphs, NamedTextColor.GREEN));
        if (!s.def.weapons.isEmpty() && s.weaponIndex < s.def.weapons.size()) {
            MountedWeapon w = v.weapons.get(s.def.weapons.get(s.weaponIndex));
            if (w != null) {
                line = line.append(Component.text("  " + w.type.displayName + " ", NamedTextColor.GOLD)).append(Component.text(w.ammoText(), w.totalAmmo() > 0 ? NamedTextColor.WHITE : NamedTextColor.RED));
                String state = w.stateText();
                if (!state.isEmpty()) line = line.append(Component.text(" " + state, state.startsWith("LOCKED") ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                if (s.def.weapons.size() > 1) line = line.append(Component.text(" [" + (s.weaponIndex + 1) + "/" + s.def.weapons.size() + "]", NamedTextColor.DARK_GRAY));
            }
        }
        Integer warn = lockWarnUntil.get(v.id);
        if (warn != null && warn > tick && (tick / 5) % 2 == 0) line = Component.text("⚠ LOCK ", NamedTextColor.RED).append(line);
        if (plugin.flag("hud.action-bar", true)) p.sendActionBar(line);
        String bossMode = plugin.getConfig().getString("hud.boss-bar", "health").toLowerCase(Locale.ROOT);
        if (bossMode.equals("none")) { hideBar(p); return; }
        boolean fuel = bossMode.equals("fuel");
        float frac = (float) Math.max(0, Math.min(1, fuel ? v.fuelFraction() : v.health / v.maxHealth()));
        BossBar bar = bars.get(p.getUniqueId());
        Component name = plugin.messages().parse(v.type.displayName).append(Component.text(fuel ? String.format(Locale.ROOT, "  fuel %.0f/%.0f", v.fuel, v.type.fuelCapacity) : String.format(Locale.ROOT, "  %.0f/%.0f", Math.max(0, v.health), v.maxHealth()), NamedTextColor.GRAY));
        BossBar.Color color = frac > 0.5 ? (fuel ? BossBar.Color.YELLOW : BossBar.Color.GREEN) : frac > 0.25 ? BossBar.Color.YELLOW : BossBar.Color.RED;
        if (bar == null) { bar = BossBar.bossBar(name, frac, color, BossBar.Overlay.NOTCHED_10); bars.put(p.getUniqueId(), bar); p.showBossBar(bar); }
        else { bar.name(name); bar.progress(frac); bar.color(color); }
    }

    /** A 10-segment bar: pack glyphs (U+E000 + set*16 + filled) or ASCII blocks. */
    private Component bar(double frac, int set, boolean glyphs, NamedTextColor color) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, frac)) * 10);
        if (glyphs && plugin.lastPack() != null) return Component.text(String.valueOf((char) (0xE000 + set * 16 + filled))).font(Key.key("superbvehicles", "hud"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < filled ? '|' : '.');
        return Component.text(sb.toString(), frac > 0.25 ? color : NamedTextColor.RED);
    }

    public void lockWarning(Vehicle v) {
        lockWarnUntil.put(v.id, plugin.vehicles().tickCount() + 60);
        for (Player p : v.occupants()) { p.playSound(p.getLocation(), "minecraft:block.note_block.bit", 1f, 0.6f); plugin.messages().actionBar(p, "lock-warning"); }
    }

    private void hideBar(Player p) { BossBar b = bars.remove(p.getUniqueId()); if (b != null) p.hideBossBar(b); }
    public void forgetPlayer(Player p) { hideBar(p); }
    public void forget(Vehicle v) { lockWarnUntil.remove(v.id); }
}
