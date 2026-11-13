package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Startup self-check: every wheeled / tracked type is simulated for 3 s of full throttle on a flat floor; a type that
 * reaches less than 20% of its max-speed gets a warning naming the likely cause (engine-force too small for the
 * mass, or rolling resistance / drag larger than the thrust). Helicopters are checked for a max-speed clamp.
 */
public final class PhysicsSelfCheck {
    private PhysicsSelfCheck() { }

    /** null = fine, else a warning for the console. */
    public static String wheeled(VehicleType t) {
        if (!t.vehicleClass.ground()) return null;
        WheeledVehicleController.Params p = WheeledVehicleController.Params.of(t);
        double hx = 1.0 * t.scale, hy = 0.8 * t.scale, hz = 2.0 * t.scale;
        WheeledVehicleController ctl = new WheeledVehicleController(p, pose -> new Obb(pose.toWorld(0, hy * 16 / t.scale, 0, t.scale), new Vector3d(hx, hy, hz), pose.rotation()));
        Pose pose = new Pose(); pose.y = 64;
        PhysicsState s = new PhysicsState();
        BlockCollider col = new BlockCollider(Terrain.flat(64));
        WheeledVehicleController.Input in = new WheeledVehicleController.Input();
        in.driver = true; in.engineOn = true; in.throttle = 1;
        WheeledVehicleController.Report r = null;
        for (int i = 0; i < 60; i++) r = ctl.step(pose, s, in, col);
        if (s.speed >= 0.2 * p.maxSpeed) return null;
        double thrust = p.engineForce / p.mass, resist = p.rollingResistance * 9.81;
        return String.format(Locale.ROOT, "%s: only %.1f of %.1f blocks/s after 3 s of full throttle - %s (engine-force %.0f N, mass %.0f kg => %.2f blocks/s^2; rolling resistance %.2f blocks/s^2)",
            t.id, s.speed, p.maxSpeed, r == null ? "" : r.reason.isEmpty() ? (thrust <= resist ? "resistance beats thrust" : "engine-force too small for the mass") : r.reason, p.engineForce, p.mass, thrust, resist);
    }

    public static String any(VehicleType t) {
        if (t.vehicleClass == VehicleClass.WHEELED || t.vehicleClass == VehicleClass.TRACKED) return wheeled(t);
        if (t.vehicleClass == VehicleClass.HELICOPTER) {
            HelicopterModel.Params p = HelicopterModel.Params.of(t);
            if (p.clampHorizontal > p.maxSpeed * 1.5) return t.id + ": max-horizontal-speed " + p.clampHorizontal + " is far above max-speed " + p.maxSpeed;
        }
        return null;
    }
}
