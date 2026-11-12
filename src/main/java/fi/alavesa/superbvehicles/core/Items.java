package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.pack.PackBuilder;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * The items: a PDC-tagged spawn item per vehicle type (right-click the ground to place; sneak + right-click a
 * parked, undamaged vehicle you own to pick it up), and the fuel can, ammunition crate and repair kit (right-click
 * a vehicle). Icons come from the generated pack (item_model); the base material is configurable.
 */
public final class Items {
    private final SuperbVehiclesPlugin plugin;
    public Items(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    private Material base() { try { return Material.valueOf(plugin.getConfig().getString("items.material", "PAPER").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return Material.PAPER; } }

    public ItemStack spawnItem(VehicleType t, int amount) {
        ItemStack it = new ItemStack(base(), Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(PackBuilder.iconModel(t.model)));
        m.displayName(plugin.messages().parse(t.displayName).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(plugin.messages().get("item-lore-vehicle").decoration(TextDecoration.ITALIC, false),
            plugin.messages().get("item-lore-class", Messages.str("class", t.vehicleClass.name().toLowerCase(Locale.ROOT)), Messages.num("seats", t.seats.size())).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(plugin.keys().itemType, PersistentDataType.STRING, t.id);
        m.getPersistentDataContainer().set(plugin.keys().itemKind, PersistentDataType.STRING, "vehicle");
        it.setItemMeta(m);
        return it;
    }

    /** fuel_can | ammo_crate | repair_kit */
    public ItemStack utility(String kind, int amount) {
        ItemStack it = new ItemStack(base(), Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(PackBuilder.iconModel(kind)));
        String key = kind.startsWith("fuel") ? "fuel" : kind.startsWith("ammo") ? "ammo" : "repair";
        m.displayName(plugin.messages().get("item-" + key).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(plugin.messages().get("item-lore-" + key, Messages.num("amount", key.equals("fuel") ? plugin.cfg("fuel.can-amount", 40) : plugin.cfg("items.repair-amount", 100))).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(plugin.keys().itemKind, PersistentDataType.STRING, key);
        it.setItemMeta(m);
        return it;
    }

    public String kind(ItemStack it) { return it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(plugin.keys().itemKind, PersistentDataType.STRING); }
    public VehicleType typeOf(ItemStack it) { if (it == null || !it.hasItemMeta()) return null; String id = it.getItemMeta().getPersistentDataContainer().get(plugin.keys().itemType, PersistentDataType.STRING); return id == null ? null : plugin.types().get(id); }

    /** Right-click on a block with a spawn item: place the vehicle on top, facing the player's way. */
    public boolean place(Player p, ItemStack item, Block block, BlockFace face) {
        VehicleType t = typeOf(item);
        if (t == null) return false;
        if (!p.hasPermission(t.permissionSpawn) && !p.hasPermission("superbvehicles.spawn.*") && !p.hasPermission("superbvehicles.admin")) { plugin.messages().send(p, "no-permission"); return true; }
        if (plugin.types().rigOf(t) == null) { plugin.messages().send(p, "pack-busy"); return true; }
        Block target = block.getRelative(face);
        Location at = target.getLocation().add(0.5, 0, 0.5);
        if (!target.isPassable()) at.add(0, 1, 0);
        at.setYaw(p.getLocation().getYaw() + 180f);   // nose away from the player
        Vehicle v = plugin.vehicles().spawn(t, at, p.getUniqueId());
        if (v == null) return true;
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        plugin.messages().send(p, "spawned", Messages.str("name", t.displayName));
        return true;
    }

    /** Sneak + right-click by the owner (or an admin) on an unoccupied vehicle: back into an item. */
    public boolean pickUp(Player p, Vehicle v) {
        if (!plugin.flag("items.pick-up", true) || v.occupied() || v.wreck) return false;
        if (v.owner != null && !v.owner.equals(p.getUniqueId()) && !p.hasPermission("superbvehicles.admin")) { plugin.messages().send(p, "not-owner"); return true; }
        if (v.health < v.maxHealth() * plugin.cfg("items.pick-up-min-health", 0.99)) return false;
        p.getInventory().addItem(spawnItem(v.type, 1));
        plugin.vehicles().remove(v, true);
        return true;
    }

    /** Right-click a vehicle holding a utility item. */
    public boolean useOn(Player p, Vehicle v) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        String kind = kind(hand);
        if (kind == null || kind.equals("vehicle")) return false;
        boolean creative = p.getGameMode() == org.bukkit.GameMode.CREATIVE;
        switch (kind) {
            case "fuel" -> {
                if (v.fuel >= v.type.fuelCapacity) return true;
                v.fuel = Math.min(v.type.fuelCapacity, v.fuel + plugin.cfg("fuel.can-amount", 40));
                if (!creative) hand.setAmount(hand.getAmount() - 1);
                v.playSound("minecraft:item.bucket.empty", 1f, 1f);
                plugin.messages().send(p, "refueled", Messages.str("name", v.displayName()), Messages.num("fuel", v.fuel), Messages.num("capacity", v.type.fuelCapacity));
            }
            case "ammo" -> {
                if (v.weapons.isEmpty()) return true;
                for (MountedWeapon w : v.weapons.values()) w.restock();
                if (!creative) hand.setAmount(hand.getAmount() - 1);
                v.playSound("minecraft:block.chest.close", 1f, 0.8f);
                plugin.messages().send(p, "ammo-restocked", Messages.str("name", v.displayName()));
            }
            case "repair" -> {
                if (v.health >= v.maxHealth() || v.wreck) return true;
                double amount = plugin.cfg("items.repair-amount", 100);
                double before = v.health;
                v.health = Math.min(v.maxHealth(), v.health + amount);
                if (!creative) hand.setAmount(hand.getAmount() - 1);
                v.playSound("minecraft:block.anvil.use", 1f, 1.2f);
                plugin.messages().send(p, "repaired", Messages.str("name", v.displayName()), Messages.num("amount", v.health - before));
            }
            default -> { return false; }
        }
        return true;
    }
}
