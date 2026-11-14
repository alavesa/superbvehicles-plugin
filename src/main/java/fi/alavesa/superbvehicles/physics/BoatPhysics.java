package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

/**
 * Boats: server adapter around {@link BoatModel} (afloat, beaching up slabs and shores, land grace, stranding).
 * Tells the driver when the boat runs dry and when it is stranded. YAML keys: see {@link BoatModel.Params#of}.
 */
public final class BoatPhysics extends VehiclePhysics {
    private final BoatModel model;
    private BoatModel.Report last = new BoatModel.Report();
    private boolean warnedDry, warnedStranded;

    public BoatPhysics(Vehicle v) {
        super(v);
        model = new BoatModel(BoatModel.Params.of(t), v::hullAt);
        model.params().gravity = gravity();
        s.idleRpm = t.physics("idle-rpm", 600); s.maxRpm = t.physics("max-rpm", 5000);
    }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "boat", model.params().autoStartEngine);
        BoatModel.Input in = new BoatModel.Input();
        in.driver = c.present; in.engineOn = s.engineOn; in.outOfFuel = v.outOfFuel();
        in.throttle = c.throttle(); in.steer = c.steer();
        in.powerFactor = v.powerFactor(); in.speedLimitFactor = chunkSpeedFactor(col);
        last = model.step(v.pose, s, in, col);
        afterMove(s.lastImpact);
        if (s.landingSpeed > 0) landing(s.landingSpeed);
        // driver feedback when the hull leaves the water
        if (last.wet == 0 && c.present) {
            if (last.stranded && !warnedStranded) { warnedStranded = true; message("boat-stranded"); }
            else if (!last.stranded && !warnedDry) { warnedDry = true; message("boat-dry"); }
        } else if (last.wet > 0) { warnedDry = false; warnedStranded = false; }
        double max = model.params().maxSpeed;
        s.rpm = Pose.approach(s.rpm, s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * Pose.clamp(Math.abs(s.throttle) * 0.5 + 0.5 * Math.abs(s.speed) / Math.max(1, max), 0, 1) : 0, 0.15);
        s.propAngle = (s.propAngle + (s.engineOn && last.wet > 0 ? 20 + 40 * Math.abs(s.throttle) : 0)) % 360;
        s.gear = s.speed < -0.2 ? -1 : s.speed > 0.2 ? 1 : 0;
        if (s.inWater && Math.abs(s.speed) > 1.5 && v.age % 2 == 0) {
            Obb h = v.hull();
            Vector3d back = v.pose.forwardFlat().mul(-h.half.z * 0.8);
            v.world.spawnParticle(Particle.SPLASH, v.pose.x + back.x, v.pose.y + 0.1, v.pose.z + back.z, (int) (4 + 10 * s.wake), h.half.x * 0.8, 0.1, 0.4, 0);
            if (s.wake > 0.2) v.world.spawnParticle(Particle.CLOUD, v.pose.x + back.x, v.pose.y + 0.05, v.pose.z + back.z, 2, h.half.x * 0.4, 0.05, 0.3, 0.01);
        }
    }

    @Override public String debugLine() { return last.line(s, v.fuel); }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn || Math.abs(s.speed) <= 0.3) return "idle";
        if (s.steer < -0.3) return "left"; if (s.steer > 0.3) return "right";
        return s.speed < 0 ? "reverse" : "drive";
    }
}
