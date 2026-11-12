package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** A vehicle's health is about to drop. Change the amount or cancel. */
public final class VehicleDamageEvent extends VehicleEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final DamageType type; private final Entity source; private final String zone; private double amount; private boolean cancelled;
    public VehicleDamageEvent(Vehicle v, double amount, DamageType type, Entity source, String zone) { super(v); this.amount = amount; this.type = type; this.source = source; this.zone = zone; }
    public double getAmount() { return amount; }
    public void setAmount(double a) { amount = Math.max(0, a); }
    public DamageType getType() { return type; }
    public Entity getSource() { return source; }
    public String getZone() { return zone; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean c) { cancelled = c; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
