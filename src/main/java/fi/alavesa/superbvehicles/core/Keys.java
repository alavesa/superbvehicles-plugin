package fi.alavesa.superbvehicles.core;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PDC keys and scoreboard tags. */
public final class Keys {
    public static final String NS = "superbvehicles";
    public static final String TAG_ENTITY = "sbv.entity";   // every entity we own
    public static final String TAG_ROOT = "sbv.root";
    public static final String TAG_PART = "sbv.part";
    public static final String TAG_SEAT = "sbv.seat";
    public static final String TAG_CLICK = "sbv.click";
    public static final String TAG_PROJECTILE = "sbv.projectile";

    public final NamespacedKey vehicleId, typeId, seatIndex, itemType, itemKind, skin, owner;
    public Keys(Plugin p) {
        vehicleId = new NamespacedKey(p, "vehicle");
        typeId = new NamespacedKey(p, "type");
        seatIndex = new NamespacedKey(p, "seat");
        itemType = new NamespacedKey(p, "item_type");
        itemKind = new NamespacedKey(p, "item_kind");
        skin = new NamespacedKey(p, "skin");
        owner = new NamespacedKey(p, "owner");
    }
}
