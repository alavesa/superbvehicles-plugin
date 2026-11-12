package fi.alavesa.superbvehicles.weapon;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One mounted weapon as declared under a vehicle's {@code weapons:} map. Everything is data: the kind decides the
 * behaviour family (projectile, guided, dropped, countermeasure, automatic turret), the numbers tune it.
 */
public final class WeaponType {
    public enum Kind {
        AUTOCANNON, MG, CANNON, ROCKET, MISSILE, BOMB, MORTAR, FLARE, CHAFF, SMOKE, CIWS, LASER;
        public boolean projectile() { return this != FLARE && this != CHAFF && this != SMOKE; }
        public boolean guided() { return this == MISSILE; }
        public boolean countermeasure() { return this == FLARE || this == CHAFF || this == SMOKE; }
        public boolean dropped() { return this == BOMB; }
    }
    public enum Guidance { NONE, WIRE, LASER, LOCK, LOCK_LASER }

    public final String name;
    public final Kind kind;
    public final String displayName;
    public final List<String> muzzles = new ArrayList<>();
    public final double damage, splash, splashDamage, penetration, velocity, gravity, drag, spread;
    public final int rpm, cooldownTicks, burst;
    public final int ammo, magazine, reloadTicks;
    public final boolean blockDamage, fire, destroysProjectiles;
    public final double explosionPower;
    public final String sound, hitSound, reloadSound, particle, trail;
    public final double recoil;
    public final Guidance guidance;
    public final double lockTime, lockRange, lockCone, turnRate, range, lifetime;
    public final String ammoType;            // AP | HE | ... label for cannons (two shells = two weapons)
    public final boolean autoTarget;         // CIWS: fires on its own
    public final double traverseSpeed, pitchMin, pitchMax, yawMin, yawMax;   // turret limits (deg, deg/s)
    public final String turretBone, gunBone; // bones this weapon turns (default: turret / gun)
    public final boolean showProjectile;     // an item display flies with the shell/rocket
    public final String projectileModel;
    public final double mortarAngle;
    public final int flareTicks;

    public WeaponType(String name, ConfigurationSection s) {
        this.name = name;
        kind = parseKind(s.getString("type", "mg"));
        displayName = s.getString("display-name", prettify(name));
        for (Object o : s.getList("muzzle", List.of())) muzzles.add(String.valueOf(o));
        if (muzzles.isEmpty() && s.isString("muzzle")) muzzles.add(s.getString("muzzle"));
        damage = s.getDouble("damage", defaultDamage(kind));
        splash = s.getDouble("splash", defaultSplash(kind));
        splashDamage = s.getDouble("splash-damage", damage * 0.7);
        penetration = s.getDouble("penetration", defaultPen(kind));
        velocity = s.getDouble("velocity", defaultVelocity(kind));
        gravity = s.getDouble("gravity", kind == Kind.BOMB ? 0.08 : kind == Kind.MORTAR ? 0.05 : kind == Kind.MISSILE ? 0 : 0.02);
        drag = s.getDouble("drag", 0.005);
        spread = s.getDouble("spread", kind == Kind.MG ? 1.5 : kind == Kind.AUTOCANNON ? 0.8 : 0.3);
        rpm = s.getInt("rpm", defaultRpm(kind));
        cooldownTicks = Math.max(1, s.getInt("cooldown-ticks", (int) Math.round(1200.0 / Math.max(1, rpm))));
        burst = Math.max(1, s.getInt("burst", 1));
        ammo = s.getInt("ammo", defaultAmmo(kind));
        magazine = Math.max(1, s.getInt("magazine", kind == Kind.MG ? 100 : kind == Kind.AUTOCANNON ? 30 : kind == Kind.CANNON ? 1 : kind == Kind.ROCKET ? 8 : kind == Kind.MISSILE ? 2 : kind == Kind.BOMB ? 4 : kind == Kind.MORTAR ? 1 : 1));
        reloadTicks = Math.max(0, (int) Math.round(s.getDouble("reload", kind == Kind.CANNON ? 5 : kind == Kind.MISSILE ? 6 : kind == Kind.MG ? 4 : 3) * 20));
        blockDamage = s.getBoolean("block-damage", kind == Kind.CANNON || kind == Kind.BOMB || kind == Kind.ROCKET || kind == Kind.MISSILE || kind == Kind.MORTAR);
        fire = s.getBoolean("fire", false);
        destroysProjectiles = s.getBoolean("destroys-projectiles", kind == Kind.CIWS);
        explosionPower = s.getDouble("explosion-power", splash > 0 ? Math.min(6, splash * 0.6) : 0);
        sound = s.getString("sound", defaultSound(kind));
        hitSound = s.getString("hit-sound", splash > 0 ? "minecraft:entity.generic.explode" : "minecraft:block.anvil.land");
        reloadSound = s.getString("reload-sound", "minecraft:block.piston.contract");
        particle = s.getString("particle", splash > 0 ? "EXPLOSION" : "CRIT");
        trail = s.getString("trail", kind == Kind.ROCKET || kind == Kind.MISSILE ? "CAMPFIRE_COSY_SMOKE" : kind == Kind.BOMB ? "CLOUD" : kind == Kind.MG || kind == Kind.AUTOCANNON || kind == Kind.CIWS ? "" : "SMOKE");
        recoil = s.getDouble("recoil", kind == Kind.CANNON ? 0.15 : kind == Kind.AUTOCANNON ? 0.02 : 0.0);
        guidance = parseGuidance(s.getString("guidance", kind == Kind.MISSILE ? "lock" : "none"));
        lockTime = s.getDouble("lock-time", 1.5);
        lockRange = s.getDouble("lock-range", 160);
        lockCone = s.getDouble("lock-cone", 12);
        turnRate = s.getDouble("turn-rate", kind == Kind.MISSILE ? 6 : 0);
        range = s.getDouble("range", kind == Kind.MG ? 120 : kind == Kind.CIWS ? 90 : 300);
        lifetime = s.getDouble("lifetime", kind == Kind.MISSILE ? 12 : kind == Kind.ROCKET ? 6 : 8);
        ammoType = s.getString("ammo-type", kind == Kind.CANNON ? "HE" : "").toUpperCase(Locale.ROOT);
        autoTarget = s.getBoolean("auto-target", kind == Kind.CIWS);
        traverseSpeed = s.getDouble("traverse-speed", 90);
        pitchMin = s.getDouble("pitch-min", -10);
        pitchMax = s.getDouble("pitch-max", kind == Kind.CIWS || kind == Kind.MISSILE ? 85 : 25);
        yawMin = s.getDouble("yaw-min", -180);
        yawMax = s.getDouble("yaw-max", 180);
        turretBone = s.getString("turret-bone", "turret");
        gunBone = s.getString("gun-bone", "gun");
        showProjectile = s.getBoolean("show-projectile", kind == Kind.ROCKET || kind == Kind.MISSILE || kind == Kind.BOMB || kind == Kind.CANNON || kind == Kind.MORTAR);
        projectileModel = s.getString("projectile-model", kind == Kind.MISSILE ? "projectile_missile" : kind == Kind.ROCKET ? "projectile_rocket" : kind == Kind.BOMB ? "projectile_bomb" : "projectile_shell");
        mortarAngle = s.getDouble("mortar-angle", 60);
        flareTicks = s.getInt("flare-ticks", 80);
    }

    public static Kind parseKind(String s) {
        String k = s == null ? "mg" : s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (k) {
            case "autocannon", "auto_cannon", "ac" -> Kind.AUTOCANNON;
            case "mg", "machinegun", "machine_gun", "hmg" -> Kind.MG;
            case "cannon", "main_cannon", "gun" -> Kind.CANNON;
            case "rocket", "rockets", "rocket_pod" -> Kind.ROCKET;
            case "missile", "atgm", "tow", "aam", "guided" -> Kind.MISSILE;
            case "bomb", "bombs" -> Kind.BOMB;
            case "mortar", "artillery", "howitzer" -> Kind.MORTAR;
            case "flare", "flares" -> Kind.FLARE;
            case "chaff" -> Kind.CHAFF;
            case "smoke", "smoke_launcher" -> Kind.SMOKE;
            case "ciws", "auto_turret", "autoturret", "aa" -> Kind.CIWS;
            case "laser" -> Kind.LASER;
            default -> Kind.MG;
        };
    }

    public static Guidance parseGuidance(String s) {
        String g = s == null ? "none" : s.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (g) { case "wire", "tow", "sacl" -> Guidance.WIRE; case "laser" -> Guidance.LASER; case "lock", "lock_on", "ir", "radar" -> Guidance.LOCK; case "lock_laser", "both" -> Guidance.LOCK_LASER; default -> Guidance.NONE; };
    }

    private static double defaultDamage(Kind k) { return switch (k) { case MG -> 6; case AUTOCANNON -> 18; case CANNON -> 120; case ROCKET -> 45; case MISSILE -> 160; case BOMB -> 220; case MORTAR -> 90; case CIWS -> 10; case LASER -> 40; default -> 0; }; }
    private static double defaultSplash(Kind k) { return switch (k) { case CANNON -> 3.5; case ROCKET -> 3; case MISSILE -> 4; case BOMB -> 7; case MORTAR -> 5; default -> 0; }; }
    private static double defaultPen(Kind k) { return switch (k) { case MG -> 5; case AUTOCANNON -> 30; case CANNON -> 120; case ROCKET -> 40; case MISSILE -> 200; case BOMB -> 60; case MORTAR -> 30; case CIWS -> 10; case LASER -> 80; default -> 0; }; }
    private static double defaultVelocity(Kind k) { return switch (k) { case MG, AUTOCANNON, CIWS -> 12; case CANNON -> 16; case ROCKET -> 6; case MISSILE -> 4; case BOMB -> 0; case MORTAR -> 3.5; case LASER -> 60; default -> 2; }; }
    private static int defaultRpm(Kind k) { return switch (k) { case MG -> 600; case AUTOCANNON -> 200; case CIWS -> 900; case CANNON -> 10; case ROCKET -> 120; case MISSILE -> 15; case BOMB -> 60; case MORTAR -> 8; case LASER -> 60; default -> 30; }; }
    private static int defaultAmmo(Kind k) { return switch (k) { case MG -> 1000; case AUTOCANNON -> 300; case CIWS -> 600; case CANNON -> 40; case ROCKET -> 38; case MISSILE -> 8; case BOMB -> 8; case MORTAR -> 30; case LASER -> 100; case FLARE, CHAFF -> 12; case SMOKE -> 6; }; }
    private static String defaultSound(Kind k) { return switch (k) { case MG, CIWS -> "minecraft:entity.generic.explode"; case AUTOCANNON -> "minecraft:entity.firework_rocket.blast"; case CANNON, MORTAR -> "minecraft:entity.generic.explode"; case ROCKET, MISSILE -> "minecraft:entity.firework_rocket.launch"; case BOMB -> "minecraft:block.dispenser.dispense"; case LASER -> "minecraft:block.beacon.power_select"; default -> "minecraft:entity.firework_rocket.shoot"; }; }

    public static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.replace('_', ' ').replace('-', ' ').split(" ")) { if (w.isEmpty()) continue; if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return sb.toString();
    }
}
