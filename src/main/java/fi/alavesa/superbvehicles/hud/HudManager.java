package fi.alavesa.superbvehicles.hud;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.PhysicsState;
import fi.alavesa.superbvehicles.physics.Units;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What riders see on the action bar.
 * <ul>
 *   <li><b>shader</b> mode (driver / pilot seats, when the HUD pack reported SUCCESSFULLY_LOADED for that player):
 *       the textured gauges of {@link ShaderHud}, sent every hud.update-ticks only when a value changed (plus a
 *       keep-alive), with server-side smoothing so the needle glides;</li>
 *   <li><b>text</b> mode (and automatic fallback): one clean line - speed in km/h or mph, gear/RPM, altitude, fuel,
 *       health, weapon and ammo;</li>
 *   <li><b>off</b>.</li>
 * </ul>
 * Gunners and passengers always get the text line (weapons). Plugin messages to a rider are appended to the HUD for a
 * moment instead of replacing it. The HUD is cleared on dismount, death, quit and destruction.
 * Per-player choice: /sbv hud shader|text|off and /sbv hud units kmh|mph, saved in data/hud-players.yml.
 */
public final class HudManager {
    public enum Mode { SHADER, TEXT, OFF;
        static Mode parse(String s) { try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return SHADER; } } }

    private static final class Viewer {
        double speed, fuel, vsi, roll, pitch;           // smoothed values
        Component lastSent; int lastSentTick = -1000;
        Component flash; int flashUntil;
        boolean shown;
    }

    private final SuperbVehiclesPlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> lockWarnUntil = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<UUID, Mode> modes = new HashMap<>();
    private final Map<UUID, Boolean> mph = new HashMap<>();
    private final Map<UUID, Boolean> shaderLoaded = new HashMap<>();
    private Map<String, Integer> advances = new HashMap<>();
    private final File prefsFile;
    private int tick;

    public HudManager(SuperbVehiclesPlugin plugin) {
        this.plugin = plugin;
        prefsFile = new File(new File(plugin.getDataFolder(), "data"), "hud-players.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(prefsFile);
        for (String k : y.getKeys(false)) {
            try { UUID id = UUID.fromString(k); modes.put(id, Mode.parse(y.getString(k + ".mode", "shader"))); mph.put(id, y.getBoolean(k + ".mph", false)); } catch (IllegalArgumentException ignored) { }
        }
        // plugin messages to a rider go into the HUD line instead of replacing it
        fi.alavesa.superbvehicles.core.Messages.setActionBarHook((audience, component) -> audience instanceof Player p && flash(p, component));
    }

    // ------------------------------------------------------------------ preferences and pack state

    public Mode defaultMode() { return Mode.parse(plugin.getConfig().getString("hud.mode", "shader")); }
    public Mode modeOf(Player p) { return modes.getOrDefault(p.getUniqueId(), defaultMode()); }
    public boolean mphOf(Player p) { return mph.getOrDefault(p.getUniqueId(), plugin.getConfig().getString("hud.units", "kmh").equalsIgnoreCase("mph")); }

    public void setMode(Player p, Mode m) { modes.put(p.getUniqueId(), m); savePrefs(); clear(p); if (m == Mode.SHADER) plugin.host().send(p); }
    public void setMph(Player p, boolean on) { mph.put(p.getUniqueId(), on); savePrefs(); }

    private void savePrefs() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : modes.entrySet()) y.set(e.getKey() + ".mode", e.getValue().name().toLowerCase(Locale.ROOT));
        for (var e : mph.entrySet()) y.set(e.getKey() + ".mph", e.getValue());
        try { prefsFile.getParentFile().mkdirs(); y.save(prefsFile); } catch (Exception e) { plugin.getLogger().warning("could not save " + prefsFile + ": " + e); }
    }

    /** Is the shader HUD supported by this server at all? (shaders are written for Minecraft 26.3) */
    public boolean shaderSupported() {
        return plugin.flag("hud.shader.enabled", true) && Bukkit.getMinecraftVersion().startsWith(plugin.getConfig().getString("hud.shader.minecraft-version", "26.3"));
    }

    /** Should the HUD pack be sent to this player? */
    public boolean wantsShaderPack(Player p) { return shaderSupported() && modeOf(p) == Mode.SHADER && !Boolean.FALSE.equals(shaderLoaded.get(p.getUniqueId())); }

    /** From PackHost: the client loaded (or failed / declined) the HUD pack. */
    public void shaderPackStatus(Player p, boolean ok, String status) {
        shaderLoaded.put(p.getUniqueId(), ok);
        if (!ok) plugin.getLogger().info("Shader HUD not available for " + p.getName() + " (" + status + ") - using the text HUD for this session.");
    }

    public void setAdvances(Map<String, Integer> adv) { advances = adv == null ? new HashMap<>() : new HashMap<>(adv); }

    private boolean shaderActive(Player p) { return shaderSupported() && modeOf(p) == Mode.SHADER && Boolean.TRUE.equals(shaderLoaded.get(p.getUniqueId())); }

    // ------------------------------------------------------------------ tick

    public void tick(int serverTick) {
        tick = serverTick;
        int every = Math.max(1, (int) plugin.cfg("hud.update-ticks", 2));
        for (Vehicle v : plugin.vehicles().all()) {
            if (v.dormant || v.removed) continue;
            for (Vehicle.SeatSlot s : v.seats) {
                Player p = s.rider();
                if (p == null) continue;
                plugin.seats().gunnerTick(v, s, p);
                if (!plugin.flag("hud.enabled", true) || modeOf(p) == Mode.OFF) continue;
                Viewer vw = viewers.computeIfAbsent(p.getUniqueId(), k -> new Viewer());
                smooth(vw, v);
                if (tick % every == 0) show(p, v, s, vw);
            }
        }
        if (tick % 100 == 0) viewers.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void smooth(Viewer vw, Vehicle v) {
        PhysicsState st = v.phys;
        double k = plugin.cfg("hud.smoothing", 0.35);
        double speed = v.type.vehicleClass.aircraft() ? st.airspeed : Math.abs(st.speed);
        vw.speed += (speed - vw.speed) * k;
        vw.fuel += (v.fuelFraction() - vw.fuel) * k;
        vw.vsi += (st.verticalSpeed - vw.vsi) * k;
        vw.roll += (v.pose.roll - vw.roll) * k;
        vw.pitch += (v.pose.pitch - vw.pitch) * k;
    }

    private void show(Player p, Vehicle v, Vehicle.SeatSlot s, Viewer vw) {
        Component tail = vw.flash != null && tick <= vw.flashUntil ? vw.flash : null;
        if (tail == null) vw.flash = null;
        Component line;
        boolean gauges = s.def.role == VehicleType.SeatRole.DRIVER && v.type.vehicleClass != VehicleClass.STATIC && !v.wreck;
        if (gauges && shaderActive(p)) {
            Component extra = weaponText(v, s);
            Component lock = lockText(v);
            Component t = Component.empty();
            if (lock != null) t = t.append(lock);
            if (extra != null) t = t.append(extra);
            if (tail != null) t = t.append(Component.text("  ")).append(tail);
            line = ShaderHud.component(ShaderHud.glyphs(values(p, v, vw)), skinOf(v), advances, t);
        } else {
            line = textLine(p, v, s, vw);
            if (tail != null) line = line.append(Component.text("  ")).append(tail);
        }
        boolean changed = !Objects.equals(line, vw.lastSent);
        if (changed || tick - vw.lastSentTick >= 30) {
            if (plugin.flag("hud.action-bar", true)) p.sendActionBar(line);
            vw.lastSent = line; vw.lastSentTick = tick; vw.shown = true;
        }
        bossBar(p, v);
    }

    private String skinOf(Vehicle v) {
        String s = v.type.hudSkin;
        for (String k : fi.alavesa.superbvehicles.pack.HudPackBuilder.SKINS) if (k.equals(s)) return s;
        return "military";
    }

    ShaderHud.Values values(Player p, Vehicle v, Viewer vw) {
        VehicleType t = v.type;
        ShaderHud.Values val = new ShaderHud.Values();
        val.aircraft = t.vehicleClass.aircraft();
        val.speedFraction = vw.speed / Math.max(1, t.gaugeMaxSpeed);
        val.sweep = t.gaugeSweep;
        val.redline = t.gaugeRedline;
        val.mph = mphOf(p);
        val.speedReadout = (int) Math.round(val.mph ? Units.mph(vw.speed) : Units.kmh(vw.speed));
        val.hasFuel = plugin.flag("fuel.enabled", true) && t.fuelCapacity > 0;
        val.fuelFraction = v.outOfFuel() ? 0 : vw.fuel;
        val.fuelArc = t.fuelGauge.equals("arc");
        val.engineOff = !v.phys.engineOn;
        double vmax = Math.max(1, t.physics("climb-rate", t.physics("max-climb-speed", 10)));
        val.verticalFraction = vw.vsi / (vmax * 1.25);
        val.rollDegrees = vw.roll; val.pitchDegrees = vw.pitch;
        val.altitude = (int) Math.floor(v.pose.y);
        return val;
    }

    private Component weaponText(Vehicle v, Vehicle.SeatSlot s) {
        if (s.def.weapons.isEmpty() || s.weaponIndex >= s.def.weapons.size()) return null;
        MountedWeapon w = v.weapons.get(s.def.weapons.get(s.weaponIndex));
        if (w == null) return null;
        Component c = Component.text(w.type.displayName + " ", NamedTextColor.GOLD).append(Component.text(w.ammoText(), w.totalAmmo() > 0 ? NamedTextColor.WHITE : NamedTextColor.RED));
        String state = w.stateText();
        if (!state.isEmpty()) c = c.append(Component.text(" " + state, state.startsWith("LOCKED") ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        if (s.def.weapons.size() > 1) c = c.append(Component.text(" [" + (s.weaponIndex + 1) + "/" + s.def.weapons.size() + "]", NamedTextColor.DARK_GRAY));
        return c;
    }

    private Component lockText(Vehicle v) {
        Integer warn = lockWarnUntil.get(v.id);
        return warn != null && warn > tick && (tick / 5) % 2 == 0 ? Component.text("⚠ LOCK ", NamedTextColor.RED) : null;
    }

    /** The text HUD (fallback and gunner seats). */
    private Component textLine(Player p, Vehicle v, Vehicle.SeatSlot s, Viewer vw) {
        PhysicsState st = v.phys;
        VehicleType t = v.type;
        boolean glyphs = plugin.flag("hud.glyphs", true);
        Component line = Component.empty();
        Component lock = lockText(v);
        if (lock != null) line = line.append(lock);
        if (v.wreck) line = line.append(Component.text("WRECK", NamedTextColor.DARK_RED));
        else if (t.vehicleClass != VehicleClass.STATIC) {
            boolean useMph = mphOf(p);
            double shown = useMph ? Units.mph(vw.speed) : Units.kmh(vw.speed);
            float hue = (float) (0.33 - 0.33 * Math.min(1.0, vw.speed / Math.max(1.0, t.maxSpeed)));
            line = line.append(Component.text(String.format(Locale.ROOT, "%.0f %s", shown, useMph ? "mph" : "km/h"), TextColor.color(java.awt.Color.HSBtoRGB(hue, 0.8f, 1f))));
            if (!st.engineOn) line = line.append(Component.text(v.outOfFuel() ? "  OUT OF FUEL" : "  ENGINE OFF", v.outOfFuel() ? NamedTextColor.RED : NamedTextColor.GRAY));
            else if (t.vehicleClass.aircraft() && st.spool < 0.95) line = line.append(Component.text(String.format(Locale.ROOT, "  spool %d%%", (int) (st.spool * 100)), NamedTextColor.YELLOW));
            else if (t.vehicleClass.ground()) line = line.append(Component.text("  " + (st.gear < 0 ? "R" : st.gear == 0 ? "N" : String.valueOf(st.gear)) + String.format(Locale.ROOT, " %d rpm", (int) st.rpm), NamedTextColor.GRAY));
            if (t.vehicleClass.aircraft()) {
                line = line.append(Component.text(String.format(Locale.ROOT, "  alt %d %s%.0f", (int) Math.floor(st.altitude), st.verticalSpeed > 0.5 ? "▲" : st.verticalSpeed < -0.5 ? "▼" : "•", Math.abs(st.verticalSpeed)), NamedTextColor.AQUA));
                if (t.vehicleClass == VehicleClass.PLANE) { line = line.append(Component.text(st.gearDown ? "  GEAR" : "  gear", st.gearDown ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)); if (st.flapsDown) line = line.append(Component.text(" FLAPS", NamedTextColor.GREEN)); if (st.stalled) line = line.append(Component.text(" STALL", NamedTextColor.RED)); }
            }
            if (plugin.flag("fuel.enabled", true) && t.fuelCapacity > 0) line = line.append(Component.text(String.format(Locale.ROOT, "  fuel %d%% ", (int) Math.round(v.fuelFraction() * 100)), v.fuelFraction() > 0.15 ? NamedTextColor.GRAY : NamedTextColor.RED)).append(bar(v.fuelFraction(), 1, glyphs, NamedTextColor.GOLD));
        }
        line = line.append(Component.text("  ❤", NamedTextColor.GRAY)).append(bar(v.health / v.maxHealth(), 0, glyphs, NamedTextColor.GREEN));
        Component w = weaponText(v, s);
        if (w != null) line = line.append(Component.text("  ")).append(w);
        return line;
    }

    private void bossBar(Player p, Vehicle v) {
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

    /** A 10-segment bar: pack glyphs (U+E000 + set*16 + filled) or ASCII. */
    private Component bar(double frac, int set, boolean glyphs, NamedTextColor color) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, frac)) * 10);
        if (glyphs && plugin.lastPack() != null) return Component.text(String.valueOf((char) (0xE000 + set * 16 + filled))).font(Key.key("superbvehicles", "hud"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < filled ? '|' : '.');
        return Component.text(sb.toString(), frac > 0.25 ? color : NamedTextColor.RED);
    }

    // ------------------------------------------------------------------ messages, warnings, clearing

    /** A message for a rider's action bar: shown inside the HUD for hud.flash-ticks. Returns false for non-riders. */
    public boolean flash(Player p, Component message) {
        if (plugin.vehicles() == null || plugin.vehicles().byPlayer(p) == null || modeOf(p) == Mode.OFF) return false;
        Viewer vw = viewers.computeIfAbsent(p.getUniqueId(), k -> new Viewer());
        vw.flash = message; vw.flashUntil = tick + (int) plugin.cfg("hud.flash-ticks", 50);
        vw.lastSent = null;
        return true;
    }

    public void lockWarning(Vehicle v) {
        lockWarnUntil.put(v.id, plugin.vehicles().tickCount() + 60);
        for (Player p : v.occupants()) { p.playSound(p.getLocation(), "minecraft:block.note_block.bit", 1f, 0.6f); plugin.messages().actionBar(p, "lock-warning"); }
    }

    /** Remove the HUD from the player's screen now. */
    public void clear(Player p) {
        Viewer vw = viewers.remove(p.getUniqueId());
        if (vw != null && vw.shown && p.isOnline()) p.sendActionBar(Component.empty());
        hideBar(p);
    }

    private void hideBar(Player p) { BossBar b = bars.remove(p.getUniqueId()); if (b != null) p.hideBossBar(b); }
    public void forgetPlayer(Player p) { clear(p); }
    public void quit(Player p) { viewers.remove(p.getUniqueId()); bars.remove(p.getUniqueId()); shaderLoaded.remove(p.getUniqueId()); }
    public void forget(Vehicle v) { lockWarnUntil.remove(v.id); }
}
