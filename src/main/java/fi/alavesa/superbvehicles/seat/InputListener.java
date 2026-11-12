package fi.alavesa.superbvehicles.seat;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Turns player packets into vehicle input: held keys (PlayerInputEvent), hotbar selection (weapon choice), F
 * (swap hands) and Q (drop) as one-shot keys, left/right clicks in the air, and the spawn item (right-click a
 * block with a vehicle item places it).
 */
public final class InputListener implements Listener {
    private final SuperbVehiclesPlugin plugin;
    public InputListener(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    @EventHandler public void onInput(PlayerInputEvent e) { plugin.seats().input(e.getPlayer(), e.getInput()); }

    @EventHandler public void onHotbar(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Vehicle v = plugin.vehicles().byPlayer(p);
        if (v == null) return;
        Vehicle.SeatSlot s = v.seatOf(p);
        if (s != null && !s.def.weapons.isEmpty()) plugin.seats().hotbar(p, e.getNewSlot());
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        if (plugin.vehicles().byPlayer(e.getPlayer()) == null) return;
        e.setCancelled(true);
        plugin.seats().edge(e.getPlayer(), "swap");
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if (plugin.vehicles().byPlayer(e.getPlayer()) == null) return;
        e.setCancelled(true);
        plugin.seats().edge(e.getPlayer(), "drop");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        Vehicle riding = plugin.vehicles().byPlayer(p);
        if (riding != null) {
            if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) plugin.seats().edge(p, "attack");
            else if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) plugin.seats().edge(p, "use");
            Vehicle.SeatSlot s = riding.seatOf(p);
            if (s != null && !s.def.weapons.isEmpty() && e.getAction() != Action.RIGHT_CLICK_AIR) e.setCancelled(true);
            return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getItem() != null) {
            if (plugin.items().place(p, e.getItem(), e.getClickedBlock(), e.getBlockFace())) e.setCancelled(true);
        }
    }
}
