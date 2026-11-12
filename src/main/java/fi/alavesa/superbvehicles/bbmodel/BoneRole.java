package fi.alavesa.superbvehicles.bbmodel;

import java.util.Locale;

/**
 * The naming convention that drives behaviour: a bone's role is read from its name (prefix match, case-insensitive),
 * and a vehicle YAML can override any bone's role under {@code bones:}. Bones with a role are always animatable parts
 * of their own in the generated pack; everything else is folded into the nearest animatable ancestor.
 */
public enum BoneRole {
    ROOT("root"), BODY("body"), TURRET("turret"), GUN("gun", "barrel"), WHEEL("wheel_"), TRACK_L("track_l"), TRACK_R("track_r"),
    ROTOR_MAIN("rotor_main"), ROTOR_TAIL("rotor_tail"), PROPELLER("propeller", "prop_"), GEAR("gear_"), FLAP("flap_"),
    AILERON("aileron_"), ELEVATOR("elevator"), RUDDER("rudder"), SEAT("seat_"), MUZZLE("muzzle_"), HITBOX("hitbox_"),
    EXHAUST("exhaust"), DAMAGE1("dmg1_", "damage1_"), DAMAGE2("dmg2_", "damage2_"), WRECK("wreck_"), INTACT("intact_"),
    STEER("steer"), DOOR("door_"), LIGHT("light_"), NONE();

    private final String[] prefixes;
    BoneRole(String... prefixes) { this.prefixes = prefixes; }

    public static BoneRole of(String boneName) {
        if (boneName == null) return NONE;
        String n = boneName.toLowerCase(Locale.ROOT).trim();
        for (BoneRole r : values()) for (String p : r.prefixes) if (n.equals(p) || n.startsWith(p) || (p.endsWith("_") && n.equals(p.substring(0, p.length() - 1)))) return r;
        return NONE;
    }

    public static BoneRole parse(String s) {
        if (s == null) return NONE;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_')); } catch (IllegalArgumentException e) { return of(s); }
    }

    /** Roles that mark a bone as moving on its own (so it needs its own display part). */
    public boolean animatable() { return this != NONE && this != SEAT && this != MUZZLE && this != HITBOX && this != EXHAUST && this != ROOT && this != BODY; }

    /** A wheel bone that steers: wheel_f*, wheel_front*, wheel_steer*. */
    public static boolean steeringWheel(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("wheel_f") || n.startsWith("wheel_steer");
    }

    /** -1 for a left-side wheel/track, +1 right, 0 unknown: wheel_fl, wheel_rr, wheel_l1 ... */
    public static int side(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.indexOf('_');
        if (i < 0) return 0;
        String s = n.substring(i + 1);
        if (s.startsWith("l") || s.endsWith("l") || s.startsWith("fl") || s.startsWith("rl") || s.startsWith("ml") || s.contains("left")) return -1;
        if (s.startsWith("r") && !s.startsWith("rear") || s.endsWith("r") || s.contains("right")) return 1;
        return 0;
    }
}
