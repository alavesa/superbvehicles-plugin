package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;

/**
 * Tanks and IFVs: the same {@link WheeledVehicleController} with DIFFERENTIAL steering (A/D turn the tracks against
 * each other, on the spot when stopped). Defaults differ through {@link WheeledVehicleController.Params#of}: higher
 * rolling resistance and lateral grip, lower reverse speed. Extra YAML key: neutral-turn-rate (degrees/s).
 */
public final class TrackedPhysics extends WheeledPhysics {
    public TrackedPhysics(Vehicle v) { super(v); }
    @Override protected String controlClass() { return "tracked"; }
}
