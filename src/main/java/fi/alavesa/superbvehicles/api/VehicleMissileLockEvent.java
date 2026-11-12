package fi.alavesa.superbvehicles.api;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** A guided weapon achieved a lock (targetVehicle is null when the target is a plain entity). */
public final class VehicleMissileLockEvent extends VehicleEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MountedWeapon weapon; private final Player gunner; private final Entity target; private final Vehicle targetVehicle;
    public VehicleMissileLockEvent(Vehicle v, MountedWeapon weapon, Player gunner, Entity target, Vehicle targetVehicle) { super(v); this.weapon = weapon; this.gunner = gunner; this.target = target; this.targetVehicle = targetVehicle; }
    public MountedWeapon getWeapon() { return weapon; }
    public Player getGunner() { return gunner; }
    public Entity getTarget() { return target; }
    public Vehicle getTargetVehicle() { return targetVehicle; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
