package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Base of every SuperbVehicles event. */
public abstract class VehicleEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Vehicle vehicle;
    protected VehicleEvent(Vehicle vehicle) { this.vehicle = vehicle; }
    public Vehicle getVehicle() { return vehicle; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
