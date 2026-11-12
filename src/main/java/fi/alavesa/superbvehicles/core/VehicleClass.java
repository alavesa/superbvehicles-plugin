package fi.alavesa.superbvehicles.core;

import java.util.Locale;

public enum VehicleClass {
    WHEELED, TRACKED, BOAT, HELICOPTER, PLANE, STATIC;

    public boolean aircraft() { return this == HELICOPTER || this == PLANE; }
    public boolean ground() { return this == WHEELED || this == TRACKED; }

    public static VehicleClass parse(String s) {
        if (s == null) return WHEELED;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "tracked", "tank", "tracks" -> TRACKED;
            case "boat", "ship", "naval", "watercraft" -> BOAT;
            case "helicopter", "heli", "rotorcraft" -> HELICOPTER;
            case "plane", "aircraft", "jet", "fixedwing", "fixed-wing" -> PLANE;
            case "static", "emplacement", "turret", "tower" -> STATIC;
            default -> WHEELED;
        };
    }
}
