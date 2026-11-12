package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** A player is about to take a seat; cancel to refuse. */
public final class VehicleEnterEvent extends VehicleEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player; private final int seat; private boolean cancelled;
    public VehicleEnterEvent(Vehicle v, Player player, int seat) { super(v); this.player = player; this.seat = seat; }
    public Player getPlayer() { return player; }
    public int getSeat() { return seat; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean c) { cancelled = c; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
