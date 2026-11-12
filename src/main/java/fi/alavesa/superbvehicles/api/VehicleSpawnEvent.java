package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.event.HandlerList;

/** A vehicle was spawned (item, command, API or persistence restore). */
public final class VehicleSpawnEvent extends VehicleEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final boolean restored;
    public VehicleSpawnEvent(Vehicle v, boolean restored) { super(v); this.restored = restored; }
    public boolean isRestored() { return restored; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
