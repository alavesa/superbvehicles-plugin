package fi.alavesa.superbvehicles.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Original, procedurally generated placeholder .bbmodel files (Blockbench 4.x format) - one per vehicle class, sized
 * from the vehicle's YAML (seats, weapons). They follow the bone naming convention exactly (wheel_*, turret, gun,
 * rotor_main, gear_*, flap_*, track_l/r, seat_* and muzzle_* locators, hitbox_*), so they double as a template for
 * modellers: open one in Blockbench, replace the boxes, keep the names. Nothing here is derived from any mod.
 */
public final class PlaceholderModels {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Tiles of the generated 64x64 texture (16 px each, 4 columns): colour roles. */
    private enum Tile { BODY(0), DARK(1), METAL(2), GLASS(3), TIRE(4), LIGHT(5), ACCENT(6), ROTOR(7);
        final int i; Tile(int i) { this.i = i; }
        double[] uv() { int cx = i % 4, cy = i / 4; return new double[]{cx * 16 + 0.5, cy * 16 + 0.5, cx * 16 + 15.5, cy * 16 + 15.5}; } }

    private final JsonArray elements = new JsonArray();
    private final JsonArray outliner = new JsonArray();
    private final JsonArray animations = new JsonArray();
    private final List<String> groupUuids = new ArrayList<>();
    private final long seed;
    private int counter;

    private PlaceholderModels(long seed) { this.seed = seed; }

    /** The placeholder for a vehicle type, as .bbmodel JSON text. */
    public static String forType(VehicleType t) {
        PlaceholderModels p = new PlaceholderModels(t.id.hashCode());
        int seats = t.seats.size();
        boolean armed = t.hasWeapons();
        switch (t.vehicleClass) {
            case WHEELED -> p.wheeled(t, seats, armed);
            case TRACKED -> p.tracked(t, armed);
            case BOAT -> p.boat(t, seats);
            case HELICOPTER -> p.helicopter(t, seats, armed);
            case PLANE -> p.plane(t, armed);
            case STATIC -> p.emplacement(t);
        }
        return p.finish(t);
    }

    /** Small projectile models (shell, rocket, missile, bomb): a cube along -Z. */
    public static String projectile(String kind) {
        PlaceholderModels p = new PlaceholderModels(kind.hashCode());
        String g = p.group("body", 0, 0, 0, 0, 0, 0, null);
        switch (kind) {
            case "projectile_missile" -> { p.cube(g, "tube", -1, -1, -8, 1, 1, 8, Tile.LIGHT); p.cube(g, "fin_a", -3, -0.3, 4, 3, 0.3, 8, Tile.DARK); p.cube(g, "fin_b", -0.3, -3, 4, 0.3, 3, 8, Tile.DARK); p.cube(g, "nose", -0.6, -0.6, -10, 0.6, 0.6, -8, Tile.DARK); }
            case "projectile_rocket" -> { p.cube(g, "tube", -0.8, -0.8, -5, 0.8, 0.8, 5, Tile.METAL); p.cube(g, "fin_a", -2.2, -0.2, 3, 2.2, 0.2, 5, Tile.DARK); p.cube(g, "fin_b", -0.2, -2.2, 3, 0.2, 2.2, 5, Tile.DARK); }
            case "projectile_bomb" -> { p.cube(g, "body", -2, -2, -6, 2, 2, 6, Tile.DARK); p.cube(g, "fin_a", -3.5, -0.3, 4, 3.5, 0.3, 7, Tile.METAL); p.cube(g, "fin_b", -0.3, -3.5, 4, 0.3, 3.5, 7, Tile.METAL); }
            default -> { p.cube(g, "shell", -0.7, -0.7, -3, 0.7, 0.7, 3, Tile.ACCENT); }
        }
        return p.finishRaw(kind, 0x808080);
    }

    // ------------------------------------------------------------------ templates

    private void wheeled(VehicleType t, int seats, boolean armed) {
        boolean big = seats > 3 || t.mass > 6000;
        double L = big ? 110 : 80, W = big ? 44 : 36, H = big ? 26 : 18;    // px
        double wheelR = big ? 9 : 7, groundY = wheelR;                       // wheel axle height
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String body = group("body", 0, groundY, 0, 0, 0, 0, root);
        cube(body, "chassis", -W / 2, groundY - 2, -L / 2, W / 2, groundY + H, L / 2, Tile.BODY);
        double cabZ = -L / 2 + 10;
        cube(body, "cab", -W / 2 + 2, groundY + H, cabZ, W / 2 - 2, groundY + H + 16, cabZ + 28, Tile.BODY);
        cube(body, "windscreen", -W / 2 + 3, groundY + H + 4, cabZ - 0.5, W / 2 - 3, groundY + H + 14, cabZ + 0.5, Tile.GLASS);
        cube(body, "bumper", -W / 2, groundY - 1, -L / 2 - 3, W / 2, groundY + 5, -L / 2, Tile.METAL);
        cube(body, "headlight_l", -W / 2 + 3, groundY + 8, -L / 2 - 3.5, -W / 2 + 9, groundY + 12, -L / 2 - 3, Tile.LIGHT);
        cube(body, "headlight_r", W / 2 - 9, groundY + 8, -L / 2 - 3.5, W / 2 - 3, groundY + 12, -L / 2 - 3, Tile.LIGHT);
        if (big) cube(body, "bed", -W / 2 + 2, groundY + H, cabZ + 30, W / 2 - 2, groundY + H + 10, L / 2 - 2, Tile.DARK);
        locator(body, "exhaust", W / 2 - 4, groundY + 2, L / 2 + 1);
        int axles = big ? 3 : 2;
        for (int a = 0; a < axles; a++) {
            double z = axles == 2 ? (a == 0 ? -L / 2 + 16 : L / 2 - 16) : (-L / 2 + 16 + a * (L - 32) / 2.0);
            String pre = a == 0 ? "f" : a == axles - 1 ? "r" : "m";
            wheel("wheel_" + pre + "l", -W / 2 - 3, groundY, z, wheelR, 6, root);
            wheel("wheel_" + pre + "r", W / 2 + 3, groundY, z, wheelR, 6, root);
        }
        // seats: driver left-front, then right-front, then rows behind
        double seatY = groundY + H + 2;
        double[][] spots = {{-W / 4, seatY, cabZ + 12}, {W / 4, seatY, cabZ + 12}, {-W / 4, seatY, cabZ + 40}, {W / 4, seatY, cabZ + 40}, {-W / 4, seatY, cabZ + 60}, {W / 4, seatY, cabZ + 60}};
        for (int i = 0; i < t.seats.size(); i++) { double[] s = spots[Math.min(i, spots.length - 1)]; locator(body, t.seats.get(i).locator, s[0], s[1], s[2] + (i >= spots.length ? (i - spots.length + 1) * 12 : 0)); }
        if (armed) turretAndGun(root, 0, groundY + H + 16, cabZ + 44, big ? 16 : 10, big ? 36 : 24, t);
        hitbox(root, -W / 2 - 3, 0, -L / 2 - 3, W / 2 + 3, groundY + H + (armed ? 30 : 16), L / 2);
        idleAnimation(body);
    }

    private void tracked(VehicleType t, boolean armed) {
        double L = t.mass > 20000 ? 130 : 110, W = 56, H = 22, trackH = 14, trackW = 12;
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String hull = group("body", 0, trackH, 0, 0, 0, 0, root);
        cube(hull, "hull", -W / 2 + trackW, 4, -L / 2, W / 2 - trackW, trackH + H, L / 2, Tile.BODY);
        cube(hull, "hull_front_slope", -W / 2 + trackW, trackH + H - 6, -L / 2 - 10, W / 2 - trackW, trackH + H, -L / 2, Tile.BODY);
        cube(hull, "deck_rear", -W / 2 + trackW + 2, trackH + H, L / 2 - 30, W / 2 - trackW - 2, trackH + H + 4, L / 2 - 4, Tile.DARK);
        for (String side : new String[]{"l", "r"}) {
            double sx = side.equals("l") ? -W / 2 : W / 2 - trackW;
            String tr = group("track_" + side, sx + trackW / 2, 0, 0, 0, 0, 0, root);
            cube(tr, "track_" + side + "_belt", sx, 0, -L / 2 + 2, sx + trackW, trackH + 2, L / 2 - 2, Tile.TIRE);
            for (int i = 0; i < 6; i++) cube(tr, "roadwheel_" + side + i, sx - 0.5, 2, -L / 2 + 8 + i * (L - 16) / 5.0 - 4, sx + trackW + 0.5, 12, -L / 2 + 8 + i * (L - 16) / 5.0 + 4, Tile.METAL);
            cube(tr, "fender_" + side, sx - 1, trackH + 2, -L / 2, sx + trackW + 1, trackH + 4, L / 2, Tile.DARK);
        }
        locator(hull, "exhaust", W / 2 - trackW - 4, trackH + H + 1, L / 2 - 8);
        double turretY = trackH + H;
        if (armed) turretAndGun(root, 0, turretY, -8, 20, 60, t);
        for (int i = 0; i < t.seats.size(); i++) {
            VehicleType.Seat s = t.seats.get(i);
            if (s.role == VehicleType.SeatRole.DRIVER) locator(hull, s.locator, -W / 4, turretY - 6, -L / 2 + 14);
            else if (s.role == VehicleType.SeatRole.GUNNER) locator(hull, s.locator, 0, turretY + 2, 0);
            else locator(hull, s.locator, (i % 2 == 0 ? -1 : 1) * W / 5, turretY - 4, L / 2 - 20 - (i / 2) * 10);
        }
        hitbox(root, -W / 2, 0, -L / 2 - 10, W / 2, turretY + (armed ? 26 : 6), L / 2);
    }

    private void boat(VehicleType t, int seats) {
        double L = seats > 2 ? 120 : 90, W = 34, H = 14;
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String hull = group("body", 0, 0, 0, 0, 0, 0, root);
        cube(hull, "hull", -W / 2, 0, -L / 2 + 14, W / 2, H, L / 2, Tile.LIGHT);
        cube(hull, "bow", -W / 2 + 6, 2, -L / 2, W / 2 - 6, H + 2, -L / 2 + 14, Tile.LIGHT);
        cube(hull, "deck", -W / 2 + 2, H, -L / 2 + 16, W / 2 - 2, H + 1, L / 2 - 2, Tile.DARK);
        cube(hull, "console", -W / 2 + 6, H + 1, -L / 2 + 30, W / 2 - 6, H + 12, -L / 2 + 40, Tile.BODY);
        cube(hull, "windshield", -W / 2 + 6, H + 12, -L / 2 + 30, W / 2 - 6, H + 20, -L / 2 + 31, Tile.GLASS);
        cube(hull, "engine", -8, H + 1, L / 2 - 14, 8, H + 10, L / 2 + 4, Tile.DARK);
        String prop = group("propeller", 0, 3, L / 2 + 6, 0, 0, 0, root);
        cube(prop, "blade_a", -5, 2.5, L / 2 + 5, 5, 3.5, L / 2 + 7, Tile.METAL);
        cube(prop, "blade_b", -0.5, -2, L / 2 + 5, 0.5, 8, L / 2 + 7, Tile.METAL);
        String rudder = group("rudder", 0, 2, L / 2 + 2, 0, 0, 0, root);
        cube(rudder, "rudder_fin", -0.5, -2, L / 2 + 1, 0.5, 6, L / 2 + 4, Tile.METAL);
        for (int i = 0; i < t.seats.size(); i++) locator(hull, t.seats.get(i).locator, (i % 2 == 0 ? -1 : 1) * W / 4, H + 2, -L / 2 + 44 + (i / 2) * 16);
        for (VehicleType.Seat s : t.seats) for (String w : s.weapons) { var wt = t.weapons.get(w); if (wt != null) for (String mz : wt.muzzles) if (!hasLocator(mz)) locator(hull, mz, 0, H + 14, -L / 2 + 22); }
        hitbox(root, -W / 2, 0, -L / 2, W / 2, H + 20, L / 2 + 4);
    }

    private void helicopter(VehicleType t, int seats, boolean armed) {
        boolean heavy = seats > 2 || t.mass > 4000;
        double L = heavy ? 150 : 90, W = heavy ? 30 : 22, H = heavy ? 28 : 20, skidY = 8;
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String fus = group("body", 0, skidY, 0, 0, 0, 0, root);
        cube(fus, "cabin", -W / 2, skidY, -L / 2 + 20, W / 2, skidY + H, -L / 2 + 20 + (heavy ? 60 : 40), Tile.BODY);
        cube(fus, "nose", -W / 2 + 4, skidY + 4, -L / 2 + 10, W / 2 - 4, skidY + H - 4, -L / 2 + 20, Tile.GLASS);
        cube(fus, "canopy", -W / 2 + 2, skidY + H - 2, -L / 2 + 22, W / 2 - 2, skidY + H + 6, -L / 2 + 20 + (heavy ? 40 : 26), Tile.GLASS);
        cube(fus, "tail_boom", -4, skidY + H - 10, -L / 2 + 20 + (heavy ? 60 : 40), 4, skidY + H - 2, L / 2 - 8, Tile.BODY);
        cube(fus, "tail_fin", -1, skidY + H - 2, L / 2 - 16, 1, skidY + H + 16, L / 2, Tile.DARK);
        cube(fus, "mast", -2, skidY + H + 6, -L / 2 + 20 + (heavy ? 30 : 20) - 2, 2, skidY + H + 12, -L / 2 + 20 + (heavy ? 30 : 20) + 2, Tile.METAL);
        double hubZ = -L / 2 + 20 + (heavy ? 30 : 20), hubY = skidY + H + 12;
        String rotor = group("rotor_main", 0, hubY, hubZ, 0, 0, 0, root);
        cube(rotor, "hub", -3, hubY, hubZ - 3, 3, hubY + 3, hubZ + 3, Tile.METAL);
        double bl = heavy ? 70 : 44;
        cube(rotor, "blade_1", -bl, hubY + 1, hubZ - 2, bl, hubY + 2, hubZ + 2, Tile.ROTOR);
        cube(rotor, "blade_2", -2, hubY + 1, hubZ - bl, 2, hubY + 2, hubZ + bl, Tile.ROTOR);
        String tail = group("rotor_tail", 2, skidY + H + 8, L / 2 - 6, 0, 0, 0, root);
        cube(tail, "tail_blade_1", 1.5, skidY + H - 2, L / 2 - 7, 2.5, skidY + H + 18, L / 2 - 5, Tile.ROTOR);
        cube(tail, "tail_blade_2", 1.5, skidY + H + 7, L / 2 - 16, 2.5, skidY + H + 9, L / 2 + 4, Tile.ROTOR);
        for (String side : new String[]{"l", "r"}) {
            double sx = side.equals("l") ? -W / 2 - 4 : W / 2 + 2;
            String gear = group("gear_" + side, sx + 1, 0, 0, 0, 0, 0, root);
            cube(gear, "skid_" + side, sx, 0, -L / 2 + 16, sx + 2, 2, -L / 2 + 20 + (heavy ? 64 : 44), Tile.METAL);
            cube(gear, "strut_" + side + "1", sx, 2, -L / 2 + 26, sx + 2, skidY, -L / 2 + 28, Tile.METAL);
            cube(gear, "strut_" + side + "2", sx, 2, -L / 2 + 20 + (heavy ? 50 : 34), sx + 2, skidY, -L / 2 + 22 + (heavy ? 50 : 34), Tile.METAL);
        }
        if (armed) {
            for (String side : new String[]{"l", "r"}) { double sx = side.equals("l") ? -W / 2 - 14 : W / 2 + 6; cube(fus, "pylon_" + side, sx, skidY + 6, -L / 2 + 40, sx + 8, skidY + 10, -L / 2 + 64, Tile.DARK); }
            String gun = group("gun", 0, skidY + 2, -L / 2 + 14, 0, 0, 0, root);
            cube(gun, "chin_gun", -1.5, skidY + 0.5, -L / 2, 1.5, skidY + 3.5, -L / 2 + 16, Tile.METAL);
            int mi = 1;
            for (VehicleType.Seat s : t.seats) for (String w : s.weapons) { var wt = t.weapons.get(w); if (wt == null) continue; for (String mz : wt.muzzles) if (!hasLocator(mz)) { if (wt.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.MG || wt.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.AUTOCANNON) locator(gun, mz, 0, skidY + 2, -L / 2 - 1); else locator(fus, mz, (mi % 2 == 0 ? -1 : 1) * (W / 2 + 10), skidY + 6, -L / 2 + 40); mi++; } }
        }
        for (int i = 0; i < t.seats.size(); i++) locator(fus, t.seats.get(i).locator, heavy && i < 2 ? 0 : (i % 2 == 0 ? -1 : 1) * W / 5, skidY + 4, -L / 2 + 26 + i * (heavy ? 14 : 10));
        hitbox(root, -W / 2 - 4, 0, -L / 2 + 10, W / 2 + 4, hubY + 3, L / 2);
    }

    private void plane(VehicleType t, boolean armed) {
        double L = 180, W = 24, H = 22, gearH = 14, span = 150;
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String fus = group("body", 0, gearH, 0, 0, 0, 0, root);
        cube(fus, "fuselage", -W / 2, gearH, -L / 2 + 16, W / 2, gearH + H, L / 2 - 30, Tile.LIGHT);
        cube(fus, "nose", -W / 2 + 4, gearH + 4, -L / 2, W / 2 - 4, gearH + H - 4, -L / 2 + 16, Tile.LIGHT);
        cube(fus, "canopy", -W / 2 + 3, gearH + H, -L / 2 + 30, W / 2 - 3, gearH + H + 8, -L / 2 + 56, Tile.GLASS);
        cube(fus, "wing_l", -span / 2, gearH + 8, -10, -W / 2, gearH + 11, 24, Tile.LIGHT);
        cube(fus, "wing_r", W / 2, gearH + 8, -10, span / 2, gearH + 11, 24, Tile.LIGHT);
        cube(fus, "tail_cone", -W / 2 + 4, gearH + 4, L / 2 - 30, W / 2 - 4, gearH + H - 4, L / 2, Tile.LIGHT);
        cube(fus, "stab_l", -40, gearH + 14, L / 2 - 20, -W / 2 + 4, gearH + 16, L / 2 - 4, Tile.LIGHT);
        cube(fus, "stab_r", W / 2 - 4, gearH + 14, L / 2 - 20, 40, gearH + 16, L / 2 - 4, Tile.LIGHT);
        cube(fus, "fin_l", -41, gearH + 16, L / 2 - 20, -39, gearH + 36, L / 2 - 4, Tile.DARK);
        cube(fus, "fin_r", 39, gearH + 16, L / 2 - 20, 41, gearH + 36, L / 2 - 4, Tile.DARK);
        cube(fus, "engine_l", -W / 2 - 14, gearH + H - 6, L / 2 - 70, -W / 2 - 2, gearH + H + 8, L / 2 - 38, Tile.METAL);
        cube(fus, "engine_r", W / 2 + 2, gearH + H - 6, L / 2 - 70, W / 2 + 14, gearH + H + 8, L / 2 - 38, Tile.METAL);
        String flapL = group("flap_l", -span / 4, gearH + 9, 24, 0, 0, 0, root);
        cube(flapL, "flap_l_surface", -span / 2 + 10, gearH + 8.5, 24, -W / 2 - 4, gearH + 10.5, 34, Tile.DARK);
        String flapR = group("flap_r", span / 4, gearH + 9, 24, 0, 0, 0, root);
        cube(flapR, "flap_r_surface", W / 2 + 4, gearH + 8.5, 24, span / 2 - 10, gearH + 10.5, 34, Tile.DARK);
        String elev = group("elevator", 0, gearH + 15, L / 2 - 4, 0, 0, 0, root);
        cube(elev, "elevator_surface", -38, gearH + 14.5, L / 2 - 4, 38, gearH + 15.5, L / 2 + 4, Tile.DARK);
        String rud = group("rudder", 0, gearH + 26, L / 2 - 4, 0, 0, 0, root);
        cube(rud, "rudder_l", -41, gearH + 16, L / 2 - 4, -39, gearH + 36, L / 2 + 4, Tile.ACCENT);
        cube(rud, "rudder_r", 39, gearH + 16, L / 2 - 4, 41, gearH + 36, L / 2 + 4, Tile.ACCENT);
        gear("gear_front", 0, gearH, -L / 2 + 24, root);
        gear("gear_l", -W / 2 - 10, gearH + 8, 6, root);
        gear("gear_r", W / 2 + 10, gearH + 8, 6, root);
        if (armed) {
            String gun = group("gun", 0, gearH + 6, -L / 2, 0, 0, 0, root);
            cube(gun, "cannon", -2, gearH + 4, -L / 2 - 14, 2, gearH + 8, -L / 2, Tile.METAL);
            for (int i = 0; i < 3; i++) for (String side : new String[]{"l", "r"}) { double sx = (side.equals("l") ? -1 : 1) * (W / 2 + 16 + i * 18); cube(fus, "pylon_" + side + i, sx - 2, gearH + 2, 0, sx + 2, gearH + 8, 14, Tile.DARK); }
            int mi = 0;
            for (VehicleType.Seat s : t.seats) for (String w : s.weapons) { var wt = t.weapons.get(w); if (wt == null) continue; for (String mz : wt.muzzles) if (!hasLocator(mz)) { if (wt.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.AUTOCANNON || wt.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.MG) locator(gun, mz, 0, gearH + 6, -L / 2 - 15); else { locator(fus, mz, (mi % 2 == 0 ? -1 : 1) * (W / 2 + 16 + (mi / 2) * 18), gearH + 1, 7); mi++; } } }
        }
        for (int i = 0; i < t.seats.size(); i++) locator(fus, t.seats.get(i).locator, 0, gearH + 8, -L / 2 + 36 + i * 14);
        hitbox(root, -span / 2, 0, -L / 2 - 14, span / 2, gearH + 36, L / 2 + 4);
    }

    private void emplacement(VehicleType t) {
        String root = group("root", 0, 0, 0, 0, 0, 0, null);
        String base = group("body", 0, 0, 0, 0, 0, 0, root);
        boolean tower = t.id.contains("tower") || t.health > 600;
        double baseH = tower ? 40 : 6;
        cube(base, "base_plate", -14, 0, -14, 14, baseH, 14, Tile.DARK);
        if (!tower) for (int i = 0; i < 3; i++) { double a = i * 2 * Math.PI / 3; cube(base, "leg_" + i, 10 * Math.cos(a) - 2, 0, 10 * Math.sin(a) - 2, 10 * Math.cos(a) + 2, 4, 10 * Math.sin(a) + 2, Tile.METAL); }
        double ty = baseH;
        String turret = group("turret", 0, ty, 0, 0, 0, 0, root);
        cube(turret, "turret_housing", -8, ty, -8, 8, ty + 10, 8, Tile.BODY);
        String gun = group("gun", 0, ty + 6, -6, 0, 0, 0, turret);
        boolean launcher = t.weapons.values().stream().anyMatch(w -> w.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.MISSILE || w.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.ROCKET);
        boolean mortar = t.weapons.values().stream().anyMatch(w -> w.kind == fi.alavesa.superbvehicles.weapon.WeaponType.Kind.MORTAR);
        if (launcher) { cube(gun, "launch_tube", -5, ty + 3, -24, 5, ty + 10, -4, Tile.METAL); cube(gun, "sight", 5, ty + 8, -12, 8, ty + 12, -6, Tile.GLASS); }
        else if (mortar) { cube(gun, "mortar_tube", -2.5, ty + 2, -4, 2.5, ty + 28, 4, Tile.METAL); }
        else { cube(gun, "barrel", -1.5, ty + 5, -30, 1.5, ty + 8, -6, Tile.METAL); cube(gun, "breech", -4, ty + 3, -6, 4, ty + 10, 2, Tile.DARK); }
        for (VehicleType.Seat s : t.seats) for (String w : s.weapons) { var wt = t.weapons.get(w); if (wt != null) for (String mz : wt.muzzles) if (!hasLocator(mz)) locator(gun, mz, 0, ty + 6.5, mortar ? ty + 28 : launcher ? -25 : -31); }
        if (t.weapons.isEmpty()) locator(gun, "muzzle_1", 0, ty + 6.5, -31);
        for (int i = 0; i < t.seats.size(); i++) locator(turret, t.seats.get(i).locator, 0, ty + 2, 10 + i * 10);
        hitbox(root, -14, 0, -14, 14, ty + 12, 14);
    }

    // ------------------------------------------------------------------ pieces

    private void wheel(String name, double x, double y, double z, double r, double w, String parent) {
        String g = group(name, x, y, z, 0, 0, 0, parent);
        cube(g, name + "_tire", x - w / 2, y - r, z - r, x + w / 2, y + r, z + r, Tile.TIRE);
        cube(g, name + "_rim", x - w / 2 - 0.3, y - r * 0.5, z - r * 0.5, x + w / 2 + 0.3, y + r * 0.5, z + r * 0.5, Tile.METAL);
        locator(g, name, x, y, z);
    }

    private void gear(String name, double x, double y, double z, String parent) {
        String g = group(name, x, y, z, 0, 0, 0, parent);
        cube(g, name + "_strut", x - 1, 4, z - 1, x + 1, y, z + 1, Tile.METAL);
        cube(g, name + "_wheel", x - 1.5, 0, z - 4, x + 1.5, 8, z + 4, Tile.TIRE);
    }

    private void turretAndGun(String parent, double x, double y, double z, double size, double barrelLen, VehicleType t) {
        String turret = group("turret", x, y, z, 0, 0, 0, parent);
        cube(turret, "turret_body", x - size, y, z - size, x + size, y + size * 0.7, z + size * 1.1, Tile.BODY);
        cube(turret, "hatch", x + size * 0.3, y + size * 0.7, z, x + size * 0.8, y + size * 0.85, z + size * 0.5, Tile.DARK);
        String gun = group("gun", x, y + size * 0.4, z - size, 0, 0, 0, turret);
        cube(gun, "barrel", x - size * 0.12, y + size * 0.3, z - size - barrelLen, x + size * 0.12, y + size * 0.5, z - size, Tile.METAL);
        cube(gun, "mantlet", x - size * 0.35, y + size * 0.15, z - size - 3, x + size * 0.35, y + size * 0.65, z - size + 2, Tile.DARK);
        boolean any = false;
        for (VehicleType.Seat s : t.seats) for (String w : s.weapons) { var wt = t.weapons.get(w); if (wt == null) continue; for (String mz : wt.muzzles) if (!hasLocator(mz)) { locator(gun, mz, x, y + size * 0.4, z - size - barrelLen - 1); any = true; } }
        if (!any) locator(gun, "muzzle_1", x, y + size * 0.4, z - size - barrelLen - 1);
    }

    private void hitbox(String parent, double x0, double y0, double z0, double x1, double y1, double z1) {
        String g = group("hitbox_hull", (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, 0, 0, 0, parent);
        // an invisible marker cube (zero-area uv faces are dropped by the converter), so the hitbox is in the file but never drawn
        JsonObject c = cubeJson(g, "hitbox_hull_box", x0, y0, z0, x1, y1, z1, Tile.DARK);
        for (String f : new String[]{"north", "south", "east", "west", "up", "down"}) { JsonObject face = c.getAsJsonObject("faces").getAsJsonObject(f); face.add("uv", arr(0, 0, 0, 0)); }
        c.addProperty("visibility", false);
    }

    private void idleAnimation(String bodyUuid) {
        JsonObject a = new JsonObject();
        a.addProperty("uuid", uuid()); a.addProperty("name", "idle"); a.addProperty("loop", "loop"); a.addProperty("length", 2.0); a.addProperty("snapping", 24);
        JsonObject animators = new JsonObject();
        JsonObject bone = new JsonObject(); bone.addProperty("name", "body"); bone.addProperty("type", "bone");
        JsonArray keys = new JsonArray();
        keys.add(key("position", 0, 0, 0, 0)); keys.add(key("position", 1.0, 0, 0.4, 0)); keys.add(key("position", 2.0, 0, 0, 0));
        bone.add("keyframes", keys);
        animators.add(bodyUuid, bone);
        a.add("animators", animators);
        animations.add(a);
    }

    private JsonObject key(String channel, double time, double x, double y, double z) {
        JsonObject k = new JsonObject();
        k.addProperty("channel", channel); k.addProperty("time", time); k.addProperty("interpolation", "catmullrom"); k.addProperty("uuid", uuid());
        JsonArray dp = new JsonArray(); JsonObject p = new JsonObject(); p.addProperty("x", String.valueOf(x)); p.addProperty("y", String.valueOf(y)); p.addProperty("z", String.valueOf(z)); dp.add(p);
        k.add("data_points", dp);
        return k;
    }

    private boolean hasLocator(String name) {
        for (var e : elements) if (e.getAsJsonObject().has("type") && e.getAsJsonObject().get("type").getAsString().equals("locator") && e.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(name)) return true;
        return false;
    }

    // ------------------------------------------------------------------ json

    private final java.util.Map<String, JsonObject> groups = new java.util.LinkedHashMap<>();

    private String group(String name, double ox, double oy, double oz, double rx, double ry, double rz, String parent) {
        JsonObject g = new JsonObject();
        String id = uuid();
        g.addProperty("name", name); g.add("origin", arr(ox, oy, oz)); g.add("rotation", arr(rx, ry, rz)); g.addProperty("uuid", id); g.addProperty("export", true); g.addProperty("visibility", true); g.addProperty("autouv", 0);
        g.add("children", new JsonArray());
        groups.put(id, g);
        if (parent == null) outliner.add(g); else groups.get(parent).getAsJsonArray("children").add(g);
        groupUuids.add(id);
        return id;
    }

    private void cube(String parent, String name, double x0, double y0, double z0, double x1, double y1, double z1, Tile tile) { cubeJson(parent, name, x0, y0, z0, x1, y1, z1, tile); }

    private JsonObject cubeJson(String parent, String name, double x0, double y0, double z0, double x1, double y1, double z1, Tile tile) {
        JsonObject c = new JsonObject();
        String id = uuid();
        c.addProperty("name", name); c.addProperty("type", "cube"); c.addProperty("box_uv", false); c.addProperty("rescale", false); c.addProperty("locked", false);
        c.add("from", arr(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1))); c.add("to", arr(Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)));
        c.addProperty("autouv", 0); c.addProperty("color", tile.i % 8);
        c.add("origin", arr((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2));
        JsonObject faces = new JsonObject();
        double[] uv = tile.uv();
        for (String f : new String[]{"north", "south", "east", "west", "up", "down"}) { JsonObject face = new JsonObject(); face.add("uv", arr(uv)); face.addProperty("texture", 0); faces.add(f, face); }
        c.add("faces", faces);
        c.addProperty("uuid", id);
        elements.add(c);
        groups.get(parent).getAsJsonArray("children").add(id);
        return c;
    }

    private void locator(String parent, String name, double x, double y, double z) {
        JsonObject l = new JsonObject();
        String id = uuid();
        l.addProperty("name", name); l.addProperty("type", "locator"); l.add("position", arr(x, y, z)); l.add("rotation", arr(0, 0, 0)); l.addProperty("uuid", id); l.addProperty("locked", false);
        elements.add(l);
        groups.get(parent).getAsJsonArray("children").add(id);
    }

    private String finish(VehicleType t) {
        int color = switch (t.vehicleClass) { case WHEELED -> 0x6E7F4A; case TRACKED -> 0x5B6B3F; case BOAT -> 0xD8D8D0; case HELICOPTER -> 0x4E5A4A; case PLANE -> 0x7C8591; case STATIC -> 0x5A5A52; };
        return finishRaw(t.model, color);
    }

    private String finishRaw(String name, int bodyColor) {
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject(); meta.addProperty("format_version", "4.10"); meta.addProperty("model_format", "free"); meta.addProperty("box_uv", false);
        root.add("meta", meta);
        root.addProperty("name", name);
        root.addProperty("model_identifier", name);
        root.add("visible_box", arr(1, 1, 0));
        root.addProperty("variable_placeholders", "");
        root.add("variable_placeholder_buttons", new JsonArray());
        root.addProperty("unhandled_root_fields", "{}");
        JsonObject res = new JsonObject(); res.addProperty("width", 64); res.addProperty("height", 64);
        root.add("resolution", res);
        root.add("elements", elements);
        root.add("outliner", outliner);
        JsonArray textures = new JsonArray();
        JsonObject tex = new JsonObject();
        tex.addProperty("path", ""); tex.addProperty("name", name + ".png"); tex.addProperty("folder", ""); tex.addProperty("namespace", ""); tex.addProperty("id", "0"); tex.addProperty("particle", true); tex.addProperty("render_mode", "default"); tex.addProperty("visible", true); tex.addProperty("mode", "bitmap"); tex.addProperty("saved", true); tex.addProperty("uuid", uuid());
        tex.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texture(bodyColor)));
        textures.add(tex);
        root.add("textures", textures);
        root.add("animations", animations);
        return GSON.toJson(root);
    }

    /** 64x64 atlas of eight 16 px colour tiles with faint panel noise. */
    private byte[] texture(int body) {
        try {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            int[] colors = {body, mix(body, 0x202020, 0.55), 0x8A8F96, 0x9FC4D8, 0x2B2B2B, mix(body, 0xFFFFFF, 0.25), 0xC0392B, 0x3A3A3A};
            java.util.Random r = new java.util.Random(seed);
            for (int ti = 0; ti < 16; ti++) {
                int c = colors[Math.min(ti, 7)];
                int cx = (ti % 4) * 16, cy = (ti / 4) * 16;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                    double n = 0.92 + r.nextDouble() * 0.12;
                    int v = scale(c, n);
                    if (ti == 3 && (x == 0 || y == 0)) v = scale(c, 0.7);     // glass frame
                    if (ti == 0 && (x == 7 || y == 7) && r.nextDouble() < 0.5) v = scale(c, 0.8);   // panel line
                    img.setRGB(cx + x, cy + y, 0xFF000000 | v);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) { return CompositeBuilder.placeholderPng(0); }
    }

    private static int mix(int a, int b, double t) { int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t); return (r << 16) | (g << 8) | bl; }
    private static int scale(int c, double f) { int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f)); return (r << 16) | (g << 8) | b; }

    private String uuid() { return new UUID(seed * 31 + counter, 0x5b5b00000000L + counter++).toString(); }
    private static JsonArray arr(double... v) { JsonArray a = new JsonArray(); for (double d : v) a.add(Math.round(d * 100.0) / 100.0); return a; }

    @SuppressWarnings("unused") private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
