package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;

/** Health reached zero: the vehicle becomes a wreck (or is removed). */
public final class VehicleDestroyEvent extends VehicleEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity source;
    public VehicleDestroyEvent(Vehicle v, Entity source) { super(v); this.source = source; }
    public Entity getSource() { return source; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
