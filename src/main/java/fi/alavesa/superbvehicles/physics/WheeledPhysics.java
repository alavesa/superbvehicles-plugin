package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

import java.util.List;

/**
 * Cars, trucks, armoured cars. Engine curve + gears give an RPM for sound and torque, a bicycle model steers
 * (speed-dependent angle), velocity chases the heading with grip (handbrake = less grip = drift), per-corner ground
 * probes act as suspension (body pitch/roll follow the ground, plus roll from lateral and pitch from longitudinal
 * acceleration), gravity when airborne. YAML physics keys: mass, engine-power, max-speed, reverse-speed, turn-rate,
 * step-height, brake, grip, drag, gears [speeds], idle-rpm, max-rpm, max-tilt, suspension-rate, body-roll, body-pitch.
 */
public class WheeledPhysics extends VehiclePhysics {
    protected double bodyPitch, bodyRoll;

    public WheeledPhysics(Vehicle v) { super(v); s.idleRpm = t.physics("idle-rpm", 800); s.maxRpm = t.physics("max-rpm", 6000); }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "wheeled");
        double max = t.maxSpeed, revMax = t.physics("reverse-speed", t.maxSpeed * 0.4);
        double accel = t.enginePower / Math.max(100, t.mass) * 20 * v.powerFactor();       // blocks/s per second at full torque
        double brake = t.physics("brake", 14), friction = t.physics("rolling-friction", 1.2), drag = t.physics("drag", 0.0045);
        boolean handbrake = c.present && c.key(plugin.getConfig().getString("controls.wheeled.handbrake", "jump"));
        double throttle = c.present && s.engineOn ? c.throttle() : 0;
        s.throttle = smooth(s.throttle, throttle, 0.3);
        s.steer = smooth(s.steer, c.present ? c.steer() : 0, 0.35);
        s.inWater = inWater(col);
        double speed = s.speed;   // blocks/s, signed
        double dt = 1 / 20.0;
        if (s.onGround || s.inWater) {
            if (s.throttle > 0) { if (speed < -0.5) speed += brake * dt; else speed += accel * torque(speed, max) * s.throttle * dt * (s.inWater ? 0.4 : 1); }
            else if (s.throttle < 0) { if (speed > 0.5) speed += brake * dt * s.throttle; else speed += accel * 0.6 * s.throttle * torque(-speed, revMax) * dt; }
            if (handbrake) speed -= Math.signum(speed) * Math.min(Math.abs(speed), brake * 1.4 * dt);
            speed -= Math.signum(speed) * Math.min(Math.abs(speed), (friction + drag * speed * speed) * dt);
        } else speed -= Math.signum(speed) * Math.min(Math.abs(speed), drag * speed * speed * dt);
        speed = Pose.clamp(speed, -revMax * (s.inWater ? 0.5 : 1), max * (s.inWater ? 0.5 : 1));
        if (Math.abs(speed) < 0.02) speed = 0;
        // steering: bicycle model, angle shrinks with speed
        double maxSteer = t.physics("steer-angle", 32), wheelbase = Math.max(1.5, v.hitboxHalfPx[2] / 16 * t.scale * 1.6);
        double steerLimit = maxSteer * (1 - 0.6 * Math.min(1, Math.abs(speed) / Math.max(1, max)));
        s.steerAngle = Pose.approach(s.steerAngle, s.steer * steerLimit, 0.3);
        double yawRate = 0;
        if (Math.abs(speed) > 0.05 && (s.onGround || s.inWater)) yawRate = Math.toDegrees(speed * dt / wheelbase * Math.tan(Math.toRadians(s.steerAngle))) * t.turnRate / 3.0;
        if (handbrake && Math.abs(speed) > 4) yawRate *= 1.4;
        s.yawRate = yawRate;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate);
        // velocity chases the heading (grip); handbrake lowers grip -> drift
        double grip = t.physics("grip", 0.35) * (s.inWater ? 0.4 : 1) * (handbrake ? 0.25 : 1);
        if (!s.onGround && !s.inWater) grip = 0.02;
        Vector3d desired = v.pose.forwardFlat().mul(speed * dt);
        Vector3d flat = new Vector3d(s.vel.x, 0, s.vel.z).mul(1 - grip).add(desired.mul(grip));
        s.vel.x = flat.x; s.vel.z = flat.z;
        // vertical: gravity unless resting on the ground
        if (!s.onGround) s.vel.y = Math.max(-3.5, s.vel.y - gravity()); else s.vel.y = Math.min(0, s.vel.y);
        if (s.inWater) { s.vel.y = Math.max(s.vel.y, -0.05); if (s.vel.y < 0) s.vel.y *= 0.5; }
        boolean wasGround = s.onGround;
        double fallBps = -s.vel.y * 20;
        BlockCollider.Result r = move(col, new Vector3d(s.vel), true);
        if (r.hitHorizontal()) speed *= 0.3;
        s.speed = speed;
        s.vel.x = r.moved.x; s.vel.z = r.moved.z;
        followGround(col, t.physics("suspension-rate", 0.35), t.physics("max-tilt", 45));
        if (s.onGround && !wasGround) landing(fallBps);
        // body attitude: ground plane + acceleration lean
        double lat = yawRate * speed * t.physics("body-roll", 0.03), lon = (s.throttle * (speed >= 0 ? 1 : -1)) * t.physics("body-pitch", 1.5) * Math.min(1, Math.abs(speed) / Math.max(1, max));
        bodyPitch = Pose.approach(bodyPitch, s.onGround ? s.bodyPitchTarget - lon : airPitch(), s.onGround ? 0.3 : 0.08);
        bodyRoll = Pose.approach(bodyRoll, s.onGround ? s.bodyRollTarget + lat : bodyRoll * 0.98, 0.3);
        v.pose.pitch = bodyPitch; v.pose.roll = bodyRoll;
        // engine state
        List<Double> gears = t.physicsList("gears");
        if (gears.isEmpty()) gears = List.of(max * 0.2, max * 0.4, max * 0.65, max * 1.0);
        double a = Math.abs(speed);
        int gear = 1; double lo = 0, hi = gears.get(0);
        for (int i = 0; i < gears.size(); i++) { if (a <= gears.get(i) || i == gears.size() - 1) { gear = i + 1; hi = gears.get(i); lo = i == 0 ? 0 : gears.get(i - 1); break; } }
        s.gear = speed < -0.2 ? -1 : a < 0.1 ? 0 : gear;
        double frac = hi > lo ? Pose.clamp((a - lo) / (hi - lo), 0, 1) : 0;
        double rpmTarget = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * (a < 0.1 ? Math.abs(s.throttle) * 0.3 : 0.25 + 0.75 * frac) : 0;
        s.rpm = Pose.approach(s.rpm, rpmTarget, 0.2);
        s.wheelSpin = (s.wheelSpin + Math.toDegrees(speed * dt / Math.max(0.2, wheelRadius()))) % 360;
        s.airborne = !s.onGround;
        if (s.onGround && a > 4 && v.age % 3 == 0) dust(col);
        if (s.inWater && a > 2 && v.age % 5 == 0) v.world.spawnParticle(Particle.SPLASH, v.pose.x, v.pose.y + 0.2, v.pose.z, 8, 0.6, 0.1, 0.6, 0);
    }

    protected double airPitch() { double vy = s.vel.y * 20; return Pose.clamp(Math.toDegrees(Math.atan2(vy, Math.max(2.0, Math.abs(s.speed)))), -60, 60); }

    /** Normalised torque: full at low speed, fading towards max speed. */
    protected static double torque(double speed, double max) { double f = Math.max(0, speed) / Math.max(0.1, max); return f >= 1 ? 0 : 1 - f * f * 0.85; }

    protected double wheelRadius() { return t.physics("wheel-radius", v.hitboxHalfPx[1] / 16 * t.scale * 0.35); }

    protected void dust(BlockCollider col) {
        org.bukkit.block.Block b = col.below(v.pose.x, v.pose.y + 0.1, v.pose.z);
        if (b.isPassable()) return;
        Vector3d back = v.pose.forwardFlat().mul(-v.hitboxHalfPx[2] / 16 * t.scale * 0.8);
        v.world.spawnParticle(Particle.BLOCK, v.pose.x + back.x, v.pose.y + 0.15, v.pose.z + back.z, 3, v.hitboxHalfPx[0] / 16 * t.scale * 0.5, 0.05, 0.3, b.getBlockData());
    }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (Math.abs(s.speed) <= 0.3) return "idle";
        boolean rev = s.speed < 0;
        if (s.steer < -0.3) return rev ? "reverse_left" : "left";
        if (s.steer > 0.3) return rev ? "reverse_right" : "right";
        return rev ? "reverse" : "drive";
    }
}
