package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.bbmodel.BoneRole;
import fi.alavesa.superbvehicles.weapon.WeaponType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One vehicle definition = one YAML file in plugins/SuperbVehicles/vehicles/ (+ its .bbmodel in models/). All
 * behaviour is data: class, physics numbers, health/armour, fuel, seats, mounted weapons, bone-role overrides,
 * animation state names, sounds, skins and an optional recipe. Class-specific physics keys are read through
 * {@link #physics(String, double)} so each physics model documents its own defaults.
 */
public final class VehicleType {
    public enum SeatRole { DRIVER, GUNNER, COPILOT, PASSENGER;
        public static SeatRole parse(String s) { if (s == null) return PASSENGER; try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return s.toLowerCase(Locale.ROOT).startsWith("pilot") ? DRIVER : PASSENGER; } }
        public boolean controls() { return this == DRIVER; }
    }

    public static final class Seat {
        public final int index; public final String name, locator; public final SeatRole role;
        public final List<String> weapons = new ArrayList<>();
        public final double[] offset;        // px, added to the locator position
        public final double width, height;   // click box size (blocks)
        public final boolean exposed;        // the rider can be hit / shot (open seats)
        public final float yawOffset;        // rider facing offset (deg) relative to the hull
        Seat(int index, ConfigurationSection s) {
            this.index = index;
            name = s.getString("name", index == 0 ? "driver" : "seat" + (index + 1));
            locator = s.getString("locator", "seat_" + name);
            role = SeatRole.parse(s.getString("role", index == 0 ? "driver" : "passenger"));
            for (Object o : s.getList("weapons", List.of())) weapons.add(String.valueOf(o));
            if (weapons.isEmpty() && s.isString("weapon")) weapons.add(s.getString("weapon"));
            List<Double> off = s.getDoubleList("offset");
            offset = off.size() == 3 ? new double[]{off.get(0), off.get(1), off.get(2)} : new double[]{0, 0, 0};
            width = s.getDouble("width", 0.8); height = s.getDouble("height", 1.0);
            exposed = s.getBoolean("exposed", false);
            yawOffset = (float) s.getDouble("yaw-offset", 0);
        }
        Seat(int index, String name, String locator, SeatRole role) {
            this.index = index; this.name = name; this.locator = locator; this.role = role; offset = new double[]{0, 0, 0}; width = 0.8; height = 1.0; exposed = false; yawOffset = 0;
        }
    }

    public final String id, displayName, model;
    public final VehicleClass vehicleClass;
    public final double modelYaw, scale;
    public final ConfigurationSection physicsSection;
    public final double mass, enginePower, maxSpeed, turnRate, stepHeight;
    public final double health;
    public final Map<String, Double> armor = new LinkedHashMap<>();
    public final double fuelCapacity, fuelConsumption;   // units, units per second at full load
    public final double initialFuel;                     // 0..1 of the capacity a newly spawned vehicle holds
    public final String hudSkin;                         // military | civilian | aircraft (texture set of the shader HUD)
    public final double gaugeMaxSpeed, gaugeSweep, gaugeRedline;   // speedometer: full-scale blocks/s, needle sweep degrees, redline 0..1
    public final String fuelGauge;                       // bar | arc
    public final List<Seat> seats = new ArrayList<>();
    public final Map<String, WeaponType> weapons = new LinkedHashMap<>();
    public final Map<String, BoneRole> boneRoles = new LinkedHashMap<>();      // lower-case bone name -> role override
    public final List<String> hideGroups = new ArrayList<>();
    public final Map<String, String> animations = new LinkedHashMap<>();        // state -> animation name
    public final Map<String, String> sounds = new LinkedHashMap<>();
    public final Map<String, Map<String, String>> skins = new LinkedHashMap<>(); // skin -> (texture name -> png file in models/skins/)
    public final double[] hitboxSize, hitboxOffset;                              // blocks; null = from the model
    public final double topAngle;                                                // hits steeper than this (deg) count as top/bottom
    public final ConfigurationSection recipe, itemSection, hud;
    public final boolean lockable, invulnerable;
    public final double wreckSeconds, explosionPower;
    public final String permissionUse, permissionSpawn;
    public final File source;
    private final ConfigurationSection rawSection;

    public VehicleType(String id, ConfigurationSection s, File source) {
        this.id = id.toLowerCase(Locale.ROOT);
        this.source = source;
        this.rawSection = s;
        displayName = s.getString("display-name", WeaponType.prettify(id));
        model = s.getString("model", this.id);
        vehicleClass = VehicleClass.parse(s.getString("class", "wheeled"));
        modelYaw = s.getDouble("model-yaw", 180);
        scale = Math.max(0.05, s.getDouble("scale", 1.0));
        physicsSection = s.getConfigurationSection("physics") != null ? s.getConfigurationSection("physics") : new MemoryConfiguration();
        mass = physics("mass", defaultMass(vehicleClass));
        enginePower = physics("engine-power", defaultPower(vehicleClass));
        maxSpeed = physics("max-speed", defaultSpeed(vehicleClass));
        turnRate = physics("turn-rate", vehicleClass == VehicleClass.TRACKED ? 2.0 : vehicleClass == VehicleClass.BOAT ? 1.5 : 3.0);
        stepHeight = physics("step-height", vehicleClass == VehicleClass.TRACKED ? 1.1 : 0.6);
        health = Math.max(1, s.getDouble("health", defaultHealth(vehicleClass)));
        ConfigurationSection a = s.getConfigurationSection("armor");
        for (String z : new String[]{"front", "side", "rear", "top", "bottom"}) armor.put(z, a == null ? 0.0 : a.getDouble(z, z.equals("bottom") ? a.getDouble("top", 0) : 0));
        ConfigurationSection f = s.getConfigurationSection("fuel");
        fuelCapacity = f == null ? 100 : f.getDouble("capacity", 100);
        fuelConsumption = f == null ? 0.3 : f.getDouble("consumption", 0.3);
        initialFuel = Math.max(0, Math.min(1, (f == null ? 100 : f.getDouble("initial-fuel", 100)) / 100.0));
        hudSkin = s.getString("hud-skin", vehicleClass.aircraft() ? "aircraft" : (s.getConfigurationSection("weapons") != null ? "military" : "civilian")).toLowerCase(Locale.ROOT);
        ConfigurationSection gauge = s.getConfigurationSection("gauge");
        gaugeMaxSpeed = gauge == null ? Math.max(5, Math.ceil(maxSpeed * 1.2 / 5) * 5) : gauge.getDouble("max-speed", Math.max(5, Math.ceil(maxSpeed * 1.2 / 5) * 5));
        gaugeSweep = gauge == null ? 270 : gauge.getDouble("sweep", 270);
        gaugeRedline = gauge == null ? 0.85 : gauge.getDouble("redline", 0.85);
        fuelGauge = (gauge == null ? (vehicleClass.aircraft() ? "arc" : "bar") : gauge.getString("fuel", vehicleClass.aircraft() ? "arc" : "bar")).toLowerCase(Locale.ROOT);
        List<Map<?, ?>> seatList = s.getMapList("seats");
        int i = 0;
        for (Map<?, ?> m : seatList) { MemoryConfiguration mc = new MemoryConfiguration(); for (var e : m.entrySet()) mc.set(String.valueOf(e.getKey()), e.getValue()); seats.add(new Seat(i++, mc)); }
        if (seats.isEmpty()) seats.add(new Seat(0, "driver", "seat_driver", vehicleClass == VehicleClass.STATIC ? SeatRole.GUNNER : SeatRole.DRIVER));
        ConfigurationSection w = s.getConfigurationSection("weapons");
        if (w != null) for (String k : w.getKeys(false)) { ConfigurationSection ws = w.getConfigurationSection(k); if (ws != null) weapons.put(k.toLowerCase(Locale.ROOT), new WeaponType(k.toLowerCase(Locale.ROOT), ws)); }
        ConfigurationSection b = s.getConfigurationSection("bones");
        if (b != null) for (String k : b.getKeys(false)) boneRoles.put(k.toLowerCase(Locale.ROOT), BoneRole.parse(b.getString(k)));
        for (Object o : s.getList("hide-groups", List.of())) hideGroups.add(String.valueOf(o));
        ConfigurationSection an = s.getConfigurationSection("animations");
        if (an != null) for (String k : an.getKeys(false)) animations.put(k.toLowerCase(Locale.ROOT), an.getString(k));
        ConfigurationSection so = s.getConfigurationSection("sounds");
        if (so != null) for (String k : so.getKeys(false)) sounds.put(k.toLowerCase(Locale.ROOT), so.getString(k));
        ConfigurationSection tx = s.getConfigurationSection("textures");
        if (tx != null) for (String skin : tx.getKeys(false)) {
            ConfigurationSection sk = tx.getConfigurationSection(skin);
            Map<String, String> map = new LinkedHashMap<>();
            if (sk != null) for (String t : sk.getKeys(false)) map.put(t.toLowerCase(Locale.ROOT), sk.getString(t));
            else if (tx.isString(skin)) map.put("*", tx.getString(skin));
            skins.put(skin.toLowerCase(Locale.ROOT), map);
        }
        ConfigurationSection hb = s.getConfigurationSection("hitbox");
        List<Double> hs = hb == null ? List.of() : hb.getDoubleList("size"), ho = hb == null ? List.of() : hb.getDoubleList("offset");
        hitboxSize = hs.size() == 3 ? new double[]{hs.get(0), hs.get(1), hs.get(2)} : null;
        hitboxOffset = ho.size() == 3 ? new double[]{ho.get(0), ho.get(1), ho.get(2)} : new double[]{0, 0, 0};
        ConfigurationSection hz = s.getConfigurationSection("hitzones");
        topAngle = hz == null ? 55 : hz.getDouble("top-angle", 55);
        recipe = s.getConfigurationSection("recipe");
        itemSection = s.getConfigurationSection("item");
        hud = s.getConfigurationSection("hud");
        lockable = s.getBoolean("lockable", true);
        invulnerable = s.getBoolean("invulnerable", false);
        wreckSeconds = s.getDouble("wreck-seconds", 20);
        explosionPower = s.getDouble("explosion-power", vehicleClass == VehicleClass.STATIC ? 1.5 : 3.0);
        permissionUse = "superbvehicles.use." + this.id;
        permissionSpawn = "superbvehicles.spawn." + this.id;
    }

    public double physics(String key, double def) { return physicsSection.getDouble(key, def); }
    /** A top-level number of the vehicle YAML. */
    public double raw(String key, double def) { return source == null ? def : rawSection.getDouble(key, def); }
    public boolean physicsFlag(String key, boolean def) { return physicsSection.getBoolean(key, def); }
    public List<Double> physicsList(String key) { return physicsSection.getDoubleList(key); }
    public String sound(String key, String def) { String v = sounds.get(key); return v == null || v.isBlank() ? def : v; }
    public String animation(String state) { return animations.get(state); }
    public double armor(String zone) { return armor.getOrDefault(zone, 0.0); }
    public Seat driverSeat() { for (Seat s : seats) if (s.role == SeatRole.DRIVER) return s; return seats.get(0); }
    public List<String> skinNames() { List<String> out = new ArrayList<>(); out.add("default"); out.addAll(skins.keySet()); return out; }
    public boolean hasWeapons() { return !weapons.isEmpty(); }

    public static double defaultMass(VehicleClass c) { return switch (c) { case WHEELED -> 2500; case TRACKED -> 14000; case BOAT -> 1500; case HELICOPTER -> 2000; case PLANE -> 6000; case STATIC -> 500; }; }
    public static double defaultPower(VehicleClass c) { return switch (c) { case WHEELED -> 180; case TRACKED -> 500; case BOAT -> 160; case HELICOPTER -> 300; case PLANE -> 900; case STATIC -> 0; }; }
    public static double defaultSpeed(VehicleClass c) { return switch (c) { case WHEELED -> 22; case TRACKED -> 12; case BOAT -> 18; case HELICOPTER -> 30; case PLANE -> 60; case STATIC -> 0; }; }
    public static double defaultHealth(VehicleClass c) { return switch (c) { case WHEELED -> 300; case TRACKED -> 1200; case BOAT -> 350; case HELICOPTER -> 500; case PLANE -> 450; case STATIC -> 250; }; }

    /** Load every vehicles/*.yml; a file whose root holds several ids is accepted too. Problems go to {@code errors}. */
    public static List<VehicleType> loadAll(File dir, List<String> errors) {
        List<VehicleType> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml") || n.toLowerCase(Locale.ROOT).endsWith(".yaml"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            YamlConfiguration y = new YamlConfiguration();
            try { y.load(f); } catch (Exception e) { errors.add(f.getName() + ": " + e.getMessage()); continue; }
            String base = f.getName().replaceAll("\\.ya?ml$", "");
            try {
                if (y.contains("class") || y.contains("model") || y.contains("seats")) out.add(new VehicleType(y.getString("id", base), y, f));
                else for (String k : y.getKeys(false)) { ConfigurationSection s = y.getConfigurationSection(k); if (s != null) out.add(new VehicleType(s.getString("id", k), s, f)); }
            } catch (Exception e) { errors.add(f.getName() + ": " + e); }
        }
        return out;
    }
}
