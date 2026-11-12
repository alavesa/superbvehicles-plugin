package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.joml.Vector3d;

/** A mounted weapon is about to fire. */
public final class VehicleWeaponFireEvent extends VehicleEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MountedWeapon weapon; private final Player gunner; private final Vector3d from, direction; private boolean cancelled;
    public VehicleWeaponFireEvent(Vehicle v, MountedWeapon weapon, Player gunner, Vector3d from, Vector3d direction) { super(v); this.weapon = weapon; this.gunner = gunner; this.from = from; this.direction = direction; }
    public MountedWeapon getWeapon() { return weapon; }
    public Player getGunner() { return gunner; }
    public Vector3d getFrom() { return from; }
    public Vector3d getDirection() { return direction; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean c) { cancelled = c; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
