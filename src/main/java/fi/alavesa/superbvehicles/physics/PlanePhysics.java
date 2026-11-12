package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

/**
 * Fixed-wing aircraft. W/S step the throttle, the view steers (pitch follows the pilot's look pitch, the bank
 * follows the look yaw and the bank turns the aircraft), A/D is the rudder, ctrl the airbrake, F toggles the gear,
 * flaps extend automatically below the flap speed with the gear down. Lift grows with airspeed squared; below the
 * stall speed the nose drops and controls go mushy. On the ground the plane rolls on its wheels (takeoff roll).
 * Extra YAML keys: stall-speed, lift, pitch-rate, roll-rate, flap-speed, airbrake-drag, throttle-step, min-takeoff-speed.
 */
public final class PlanePhysics extends VehiclePhysics {
    private double pitch, roll, throttleLevel;
    public PlanePhysics(Vehicle v) { super(v); s.gearDown = true; s.gearPos = 1; }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "plane");
        double dt = 1 / 20.0, max = t.maxSpeed;
        double stall = t.physics("stall-speed", max * 0.3), step = t.physics("throttle-step", 0.02);
        if (c.present && s.engineOn) { if (c.forward) throttleLevel = Math.min(1, throttleLevel + step); if (c.backward) throttleLevel = Math.max(0, throttleLevel - step); }
        if (!s.engineOn) throttleLevel = 0;
        s.throttle = throttleLevel;
        s.airbrake = c.present && c.key(plugin.getConfig().getString("controls.plane.airbrake", "sprint"));
        if (c.present && c.pressed(plugin.getConfig().getString("controls.plane.gear", "swap_hands")) && !s.onGround) { s.gearDown = !s.gearDown; message(s.gearDown ? "gear" : "gear"); }
        if (s.onGround) s.gearDown = true;
        s.gearPos = Pose.approach(s.gearPos, s.gearDown ? 1 : 0, 0.08);
        double airspeed = s.vel.length() * 20;
        s.flapsDown = s.gearDown && airspeed < t.physics("flap-speed", max * 0.45);
        s.flapPos = Pose.approach(s.flapPos, s.flapsDown ? 1 : 0, 0.05);
        s.stalled = !s.onGround && airspeed < stall;
        // attitude from the view
        double pitchRate = t.physics("pitch-rate", 2.5), rollRate = t.physics("roll-rate", 4.0), authority = s.onGround ? 0.3 : Pose.clamp(airspeed / Math.max(1, stall * 1.5), 0.15, 1);
        double targetPitch = 0, targetRoll = 0;
        if (c.present) {
            targetPitch = Pose.clamp(-c.lookPitch, -60, 70);
            double dyaw = Pose.wrap(c.lookYaw - v.pose.yaw);
            targetRoll = Pose.clamp(dyaw * 1.2, -70, 70);
            if (c.sneak && !s.onGround) { targetPitch = Pose.clamp(targetPitch, -5, 5); targetRoll = 0; }
        }
        if (s.onGround) { targetRoll = s.bodyRollTarget; targetPitch = airspeed > t.physics("min-takeoff-speed", stall * 1.1) && c.present && c.lookPitch < -8 ? 10 : s.bodyPitchTarget; }
        if (s.stalled) { targetPitch = Math.min(targetPitch, -25); authority *= 0.5; }
        s.cyclicPitch = Pose.clamp((targetPitch - pitch) / 30, -1, 1); s.cyclicRoll = Pose.clamp((targetRoll - roll) / 45, -1, 1);
        pitch = Pose.approachAngle(pitch, targetPitch, pitchRate * authority);
        roll = Pose.approachAngle(roll, targetRoll, rollRate * authority);
        s.pedal = smooth(s.pedal, c.present ? c.steer() : 0, 0.2);
        // yaw: bank turns the plane, rudder yaws it, nose wheel steers on the ground
        double yawRate;
        if (s.onGround) yawRate = s.pedal * t.turnRate * Pose.clamp(airspeed / 8, 0, 1) * (airspeed > 0.5 ? 1 : 0);
        else yawRate = Math.toDegrees(Math.tan(Math.toRadians(roll)) * gravity() * 20 / Math.max(6, airspeed)) * t.physics("turn-gain", 1.0) + s.pedal * t.turnRate * 0.4;
        s.yawRate = yawRate;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate);
        v.pose.pitch = pitch; v.pose.roll = roll;
        // forces
        Vector3d fwd = v.pose.forward(), up = v.pose.up();
        double thrust = t.enginePower / Math.max(100, t.mass) * throttleLevel * v.powerFactor() * dt;   // blocks/tick^2
        double drag = (t.physics("drag", 0.0018) + (s.airbrake ? t.physics("airbrake-drag", 0.004) : 0) + s.flapPos * 0.0012 + s.gearPos * 0.0008) * airspeed * airspeed * dt / 20;
        double liftCoeff = t.physics("lift", 1.0) * (1 + 0.4 * s.flapPos);
        double aoa = Math.toRadians(Pose.clamp(pitch, -20, 20));
        double lift = s.onGround ? 0 : Pose.clamp(liftCoeff * airspeed * airspeed / Math.max(1, stall * stall) * (1 + 0.5 * Math.sin(aoa)), 0, 1.6) * gravity();
        if (s.stalled) lift *= 0.3;
        s.vel.add(new Vector3d(fwd).mul(thrust));
        if (airspeed > 0.01) s.vel.sub(new Vector3d(s.vel).normalize().mul(Math.min(drag, s.vel.length() * 0.5)));
        s.vel.add(new Vector3d(up).mul(lift));
        if (!s.onGround) s.vel.y -= gravity();
        // velocity aligns with the nose (the airframe flies where it points)
        double align = s.onGround ? 0.5 : t.physics("alignment", 0.08) * Pose.clamp(airspeed / Math.max(1, stall), 0.2, 1);
        double spd = s.vel.length();
        if (spd > 0.05) { Vector3d dir = s.onGround ? v.pose.forwardFlat() : fwd; if (new Vector3d(s.vel).normalize().dot(dir) > 0) { Vector3d aligned = new Vector3d(dir).mul(spd); s.vel.lerp(aligned, align); } }
        if (s.onGround) { s.vel.y = Math.min(0, s.vel.y); if (throttleLevel < 0.05 && !c.forward) { s.vel.x *= 0.96; s.vel.z *= 0.96; } }
        double cap = max / 20.0; if (s.vel.length() > cap) s.vel.normalize(cap);
        boolean wasGround = s.onGround;
        double fallBps = -s.vel.y * 20;
        BlockCollider.Result r = move(col, new Vector3d(s.vel), s.onGround);
        if (r.hitHorizontal()) { s.vel.x *= 0.1; s.vel.z *= 0.1; }
        if (r.hitY && fallBps > 0) s.onGround = true;
        s.vel.x = r.moved.x; s.vel.z = r.moved.z;
        double gNow = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.3, 0.6);
        boolean grounded = !Double.isNaN(gNow) && v.pose.y - gNow <= 0.12 && s.vel.y <= 0.02 && lift < gravity();
        if (grounded) { followGround(col, 0.3, 20); s.onGround = true; if (!s.gearDown) { plugin.damage().crash(v, 25, 10); } }
        else s.onGround = r.hitY && fallBps > 0;
        if (s.onGround && !wasGround) { landing(fallBps); if (Math.abs(roll) > 25 || pitch < -15) plugin.damage().crash(v, 30, fallBps + 10); roll = Pose.approach(roll, 0, 0.5); }
        s.airborne = !s.onGround;
        s.airspeed = s.vel.length() * 20; s.verticalSpeed = s.vel.y * 20; s.altitude = v.pose.y; s.speed = s.airspeed;
        s.propAngle = (s.propAngle + (s.engineOn ? 15 + 50 * throttleLevel : 0)) % 360;
        s.rpm = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * throttleLevel : 0;
        s.wheelSpin = (s.wheelSpin + (s.onGround ? Math.toDegrees(s.airspeed * dt / 0.5) : 0)) % 360;
        s.gear = s.onGround ? 0 : 1;
        if (s.engineOn && throttleLevel > 0.3 && v.age % 2 == 0) {
            Vector3d back = new Vector3d(fwd).mul(-v.hitboxHalfPx[2] / 16 * t.scale);
            v.world.spawnParticle(Particle.CLOUD, v.pose.x + back.x, v.pose.y + v.hitboxHalfPx[1] / 16 * t.scale + back.y, v.pose.z + back.z, 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (s.onGround) return s.airspeed > 1 ? "drive" : "idle";
        if (s.stalled) return "stall";
        return "fly";
    }
}
