package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** A player left a seat (voluntarily, ejected, or the vehicle went away). */
public final class VehicleExitEvent extends VehicleEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player; private final int seat; private final boolean forced;
    public VehicleExitEvent(Vehicle v, Player player, int seat, boolean forced) { super(v); this.player = player; this.seat = seat; this.forced = forced; }
    public Player getPlayer() { return player; }
    public int getSeat() { return seat; }
    public boolean isForced() { return forced; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
