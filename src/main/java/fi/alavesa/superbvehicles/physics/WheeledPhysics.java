package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.joml.Vector3d;

import java.util.List;

/**
 * Cars, trucks, armoured cars (and, through {@link TrackedPhysics}, tanks): the server-side adapter around the
 * pure {@link WheeledVehicleController}. It turns the driver's cached input into controller input, runs the
 * engine key, and applies the side effects the controller does not know about: crash and fall damage, RPM/gear for
 * the sound and HUD, dust and splashes. YAML keys: see {@link WheeledVehicleController.Params#of}.
 */
public class WheeledPhysics extends VehiclePhysics {
    protected final WheeledVehicleController controller;
    protected WheeledVehicleController.Report last = new WheeledVehicleController.Report();

    public WheeledPhysics(Vehicle v) {
        super(v);
        controller = new WheeledVehicleController(WheeledVehicleController.Params.of(t), v::hullAt);
        s.idleRpm = t.physics("idle-rpm", 800); s.maxRpm = t.physics("max-rpm", 6000);
    }

    public WheeledVehicleController controller() { return controller; }
    public WheeledVehicleController.Report lastReport() { return last; }

    /** Wheelbase and wheel radius from the model's wheel locators (front-most to rear-most), else from the hull. */
    @Override public void rigBound() {
        WheeledVehicleController.Params p = controller.params();
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (double[] w : v.wheelLocators.values()) { minZ = Math.min(minZ, w[2]); maxZ = Math.max(maxZ, w[2]); minY = Math.min(minY, w[1]); }
        if (v.wheelLocators.size() >= 2 && maxZ - minZ > 4) p.wheelbase = (maxZ - minZ) / 16.0 * t.scale;
        else p.wheelbase = Math.max(1.0, v.hitboxHalfPx[2] / 16.0 * t.scale * 1.6);
        if (!v.wheelLocators.isEmpty() && minY > 0.5) p.wheelRadius = Math.max(0.1, minY / 16.0 * t.scale);
        p.wheelbase = t.physics("wheelbase", p.wheelbase);
        p.wheelRadius = t.physics("wheel-radius", p.wheelRadius);
    }

    protected String controlClass() { return "wheeled"; }

    @Override public void tick(Controls c, BlockCollider col) {
        WheeledVehicleController.Params p = controller.params();
        engineControl(c, controlClass(), p.autoStartEngine);
        WheeledVehicleController.Input in = new WheeledVehicleController.Input();
        in.driver = c.present;
        in.throttle = c.throttle();
        in.steer = c.steer();
        in.handbrake = c.present && c.key(plugin.getConfig().getString("controls." + controlClass() + ".handbrake", "jump"));
        in.engineOn = s.engineOn;
        in.outOfFuel = v.outOfFuel();
        in.powerFactor = v.powerFactor();
        in.speedLimitFactor = chunkSpeedFactor(col);
        last = controller.step(v.pose, s, in, col);
        afterMove(last.lostSpeed);
        if (s.landingSpeed > 0) landing(s.landingSpeed);
        engineSound(p);
        double a = Math.abs(s.speed);
        if (s.onGround && a > 4 && v.age % 3 == 0) dust(col);
        if (s.inWater && a > 2 && v.age % 5 == 0) v.world.spawnParticle(Particle.SPLASH, v.pose.x, v.pose.y + 0.2, v.pose.z, 8, 0.6, 0.1, 0.6, 0);
    }

    /** Gear and RPM for the engine sound and the HUD (cosmetic: the controller's engine curve is what limits speed). */
    protected void engineSound(WheeledVehicleController.Params p) {
        List<Double> gears = t.physicsList("gears");
        double max = p.maxSpeed;
        if (gears.isEmpty()) gears = List.of(max * 0.2, max * 0.4, max * 0.65, max * 1.0);
        double a = Math.abs(s.speed);
        int gear = gears.size(); double lo = 0, hi = gears.get(gears.size() - 1);
        for (int i = 0; i < gears.size(); i++) if (a <= gears.get(i)) { gear = i + 1; hi = gears.get(i); lo = i == 0 ? 0 : gears.get(i - 1); break; }
        s.gear = s.speed < -0.2 ? -1 : a < 0.1 ? 0 : gear;
        double frac = hi > lo ? Pose.clamp((a - lo) / (hi - lo), 0, 1) : 0;
        double target = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * (a < 0.1 ? Math.abs(s.throttle) * 0.3 : 0.25 + 0.75 * frac) : 0;
        s.rpm = Pose.approach(s.rpm, target, 0.2);
    }

    protected void dust(BlockCollider col) {
        Block b = col.below(v.pose.x, v.pose.y + 0.1, v.pose.z);
        if (b == null || b.isPassable()) return;
        Vector3d back = v.pose.forwardFlat().mul(-v.hitboxHalfPx[2] / 16 * t.scale * 0.8);
        v.world.spawnParticle(Particle.BLOCK, v.pose.x + back.x, v.pose.y + 0.15, v.pose.z + back.z, 3, v.hitboxHalfPx[0] / 16 * t.scale * 0.5, 0.05, 0.3, b.getBlockData());
    }

    @Override public String debugLine() { return last.line(v.fuel, t.fuelCapacity); }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (Math.abs(s.speed) <= 0.3) return t.vehicleClass == VehicleClass.TRACKED && Math.abs(s.steer) > 0.3 ? (s.steer < 0 ? "left" : "right") : "idle";
        boolean rev = s.speed < 0;
        if (s.steer < -0.3) return rev ? "reverse_left" : "left";
        if (s.steer > 0.3) return rev ? "reverse_right" : "right";
        return rev ? "reverse" : "drive";
    }
}
