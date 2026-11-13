package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Fixed-wing aircraft (blocks/s throughout). W/S step the throttle, the view steers (pitch follows the look pitch,
 * the bank follows the look yaw and the bank turns the aircraft), A/D = rudder, ctrl = airbrake, F = landing gear;
 * flaps extend automatically below flap-speed with the gear down. Thrust = engine-force / mass; quadratic drag sized
 * so full throttle levels off at max-speed; lift grows with airspeed squared and collapses below stall-speed (the nose
 * drops). Hard clamps: max-speed (any direction), max-climb-speed, max-descent-speed, max-yaw-rate.
 * YAML: mass, engine-force (N), max-speed, stall-speed, min-takeoff-speed, lift, pitch-rate and roll-rate (deg/s),
 * flap-speed, airbrake-drag, throttle-rate (per second), alignment (1/s), max-climb-speed, max-descent-speed, max-yaw-rate.
 */
public final class PlanePhysics extends VehiclePhysics {
    private double throttleLevel;
    private String lastLine = "";

    public PlanePhysics(Vehicle v) { super(v); s.gearDown = true; s.gearPos = 1; }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "plane", t.physicsFlag("auto-start-engine", true));
        double dt = Units.DT, g = gravity();
        double mass = Math.max(10, t.physics("mass", t.mass));
        double max = t.physics("max-speed", t.maxSpeed) * chunkSpeedFactor(col);
        double stall = t.physics("stall-speed", max * 0.33);
        double force = t.physics("engine-force", mass * 9.0);
        double aThrustMax = force / mass;
        double cd = aThrustMax / Math.max(1, max * max);                 // full throttle levels off at max
        double rate = t.physics("throttle-rate", 0.35);
        if (c.present && s.engineOn && !v.outOfFuel()) { if (c.forward) throttleLevel = Math.min(1, throttleLevel + rate * dt); if (c.backward) throttleLevel = Math.max(0, throttleLevel - rate * dt); }
        if (!s.engineOn || v.outOfFuel()) throttleLevel = 0;
        s.throttle = throttleLevel;
        s.airbrake = c.present && c.key(plugin.getConfig().getString("controls.plane.airbrake", "sprint"));
        if (c.present && c.pressed(plugin.getConfig().getString("controls.plane.gear", "swap_hands")) && !s.onGround) { s.gearDown = !s.gearDown; message("gear"); }
        if (s.onGround) s.gearDown = true;
        s.gearPos = Pose.approach(s.gearPos, s.gearDown ? 1 : 0, 0.08);
        double airspeed = s.vel.length();
        s.flapsDown = s.gearDown && airspeed < t.physics("flap-speed", max * 0.45);
        s.flapPos = Pose.approach(s.flapPos, s.flapsDown ? 1 : 0, 0.05);
        s.stalled = !s.onGround && airspeed < stall;
        // attitude from the view (degrees/s rates)
        double pitchRate = t.physics("pitch-rate", 50), rollRate = t.physics("roll-rate", 80);
        if (pitchRate < 10) pitchRate *= Units.TPS;     // 0.1.0 files held degrees per tick
        if (rollRate < 10) rollRate *= Units.TPS;
        double authority = s.onGround ? 0.3 : Pose.clamp(airspeed / Math.max(1, stall * 1.5), 0.15, 1);
        double targetPitch = 0, targetRoll = 0;
        if (c.present) {
            targetPitch = Pose.clamp(-c.lookPitch, -60, 70);
            targetRoll = Pose.clamp(Pose.wrap(c.lookYaw - v.pose.yaw) * 1.2, -70, 70);
            if (c.sneak && !s.onGround) { targetPitch = Pose.clamp(targetPitch, -5, 5); targetRoll = 0; }
        }
        if (s.onGround) { targetRoll = s.bodyRollTarget; targetPitch = airspeed > t.physics("min-takeoff-speed", stall * 1.1) && c.present && c.lookPitch < -8 ? 10 : s.bodyPitchTarget; }
        if (s.stalled) { targetPitch = Math.min(targetPitch, -25); authority *= 0.5; }
        s.cyclicPitch = Pose.clamp((targetPitch - v.pose.pitch) / 30, -1, 1); s.cyclicRoll = Pose.clamp((targetRoll - v.pose.roll) / 45, -1, 1);
        v.pose.pitch = Pose.approachAngle(v.pose.pitch, targetPitch, pitchRate * authority * dt);
        v.pose.roll = Pose.approachAngle(v.pose.roll, targetRoll, rollRate * authority * dt);
        s.pedal = smooth(s.pedal, c.present ? c.steer() : 0, 0.2);
        double maxYaw = t.physics("max-yaw-rate", 45);
        double yawRate = s.onGround ? s.pedal * maxYaw * Pose.clamp(airspeed / 8, 0, 1)
            : Math.toDegrees(Math.tan(Math.toRadians(v.pose.roll)) * 9.81 / Math.max(6, airspeed)) * t.physics("turn-gain", 1.0) + s.pedal * maxYaw * 0.3;
        String clamp = "";
        if (Math.abs(yawRate) > maxYaw) { yawRate = Math.signum(yawRate) * maxYaw; clamp = "max yaw rate"; }
        s.yawRate = yawRate * dt;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate * dt);
        // forces (blocks/s^2)
        Vector3d fwd = v.pose.forward(), up = v.pose.up();
        double aThrust = aThrustMax * throttleLevel * v.powerFactor();
        double extraDrag = (s.airbrake ? t.physics("airbrake-drag", 3.0) : 0) + s.flapPos * 0.8 + s.gearPos * 0.4;   // blocks/s^2 at max speed
        double aDrag = cd * airspeed * airspeed + extraDrag * (airspeed * airspeed) / Math.max(1, max * max);
        double lift = s.onGround ? 0 : Pose.clamp(t.physics("lift", 1.0) * (1 + 0.4 * s.flapPos) * airspeed * airspeed / Math.max(1, stall * stall), 0, 1.6) * g;
        if (s.stalled) lift *= 0.3;
        s.vel.add(new Vector3d(fwd).mul(aThrust * dt));
        if (airspeed > 0.01) s.vel.sub(new Vector3d(s.vel).normalize().mul(Math.min(aDrag * dt, airspeed)));
        s.vel.add(new Vector3d(up).mul(lift * dt));
        if (!s.onGround) s.vel.y -= g * dt;
        double align = s.onGround ? 10 : t.physics("alignment", 1.6) * Pose.clamp(airspeed / Math.max(1, stall), 0.2, 1);
        double spd = s.vel.length();
        if (spd > 1) { Vector3d dir = s.onGround ? v.pose.forwardFlat() : fwd; if (new Vector3d(s.vel).normalize().dot(dir) > 0) s.vel.lerp(new Vector3d(dir).mul(spd), 1 - Math.exp(-align * dt)); }
        if (s.onGround) { s.vel.y = Math.min(0, s.vel.y); if (throttleLevel < 0.05 && !c.forward) { s.vel.x *= Math.exp(-0.8 * dt); s.vel.z *= Math.exp(-0.8 * dt); } }
        if (s.vel.length() > max) { s.vel.normalize(max); clamp = "max speed"; }
        double climb = t.physics("max-climb-speed", max * 0.5), descent = t.physics("max-descent-speed", max * 0.8);
        if (s.vel.y > climb) { s.vel.y = climb; clamp = "max climb"; }
        if (s.vel.y < -descent) { s.vel.y = -descent; clamp = "max descent"; }
        boolean wasGround = s.onGround;
        double fall = -s.vel.y;
        BlockCollider.Result r = integrate(col, s.onGround);
        if (r.hitY && fall > 0) s.onGround = true;
        double gNow = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.3, 0.6);
        boolean grounded = !Double.isNaN(gNow) && v.pose.y - gNow <= 0.12 && s.vel.y <= 0.4 && lift < g;
        if (grounded) { followGround(col, 0.3, 20); s.onGround = true; if (!s.gearDown) plugin.damage().crash(v, 25, 10); }
        else s.onGround = r.hitY && fall > 0;
        if (s.onGround && !wasGround) { landing(fall); if (Math.abs(v.pose.roll) > 25 || v.pose.pitch < -15) plugin.damage().crash(v, 30, fall + 10); v.pose.roll = Pose.approach(v.pose.roll, 0, 0.5); }
        s.airborne = !s.onGround;
        s.airspeed = s.vel.length(); s.verticalSpeed = s.vel.y; s.altitude = v.pose.y; s.speed = s.airspeed;
        s.propAngle = (s.propAngle + (s.engineOn ? 15 + 50 * throttleLevel : 0)) % 360;
        s.rpm = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * throttleLevel : 0;
        s.wheelSpin = (s.wheelSpin + (s.onGround ? Math.toDegrees(s.airspeed * dt / 0.5) : 0)) % 360;
        s.gear = s.onGround ? 0 : 1;
        s.activeClamp = clamp;
        lastLine = String.format(Locale.ROOT, "throttle %.0f%% | v %.2f b/s (%.0f km/h) vy %.2f | a thrust %.2f drag %.2f lift %.2f | pitch %.1f roll %.1f yaw %.1f deg/s | stall %s gear %s flaps %.1f | grounded %s | clamp %s",
            throttleLevel * 100, s.airspeed, Units.kmh(s.airspeed), s.vel.y, aThrust, aDrag, lift, v.pose.pitch, v.pose.roll, yawRate, s.stalled ? "Y" : "N", s.gearDown ? "down" : "up", s.flapPos, s.onGround ? "Y" : "N", clamp.isEmpty() ? "none" : clamp);
        if (s.engineOn && throttleLevel > 0.3 && v.age % 2 == 0) {
            Vector3d back = new Vector3d(fwd).mul(-v.hitboxHalfPx[2] / 16 * t.scale);
            v.world.spawnParticle(Particle.CLOUD, v.pose.x + back.x, v.pose.y + v.hitboxHalfPx[1] / 16 * t.scale + back.y, v.pose.z + back.z, 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override public String debugLine() { return lastLine; }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (s.onGround) return s.airspeed > 1 ? "drive" : "idle";
        if (s.stalled) return "stall";
        return "fly";
    }
}
