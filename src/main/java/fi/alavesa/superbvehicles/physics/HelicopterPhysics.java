package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

/**
 * Helicopters. Engine (F) spools the rotor; W/S is the collective (climb / descend, hover in between), A/D the
 * pedals (yaw), the pilot's view the cyclic: looking down tips the nose and the helicopter flies forward, looking
 * left or right of the heading banks and slides sideways. Torque pulls the nose without pedal input unless
 * auto-stabilise is on, ground effect adds lift near the ground, and with the engine off a spinning rotor
 * autorotates (slow descent) instead of falling. Extra YAML keys: lift (blocks/s climb), descent, spool-time,
 * cyclic-angle (max tilt deg), cyclic-gain, torque, auto-stabilise, ground-effect, autorotation-descent, max-altitude.
 */
public final class HelicopterPhysics extends VehiclePhysics {
    private double pitch, roll;   // body attitude (deg)
    public HelicopterPhysics(Vehicle v) { super(v); }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "helicopter");
        double dt = 1 / 20.0;
        double spoolTicks = Math.max(1, t.physics("spool-time", 3.0) * 20);
        s.spool = s.engineOn ? Math.min(1, s.spool + 1 / spoolTicks) : Math.max(0, s.spool - (s.onGround ? 0.6 : 0.25) / spoolTicks);
        boolean live = s.engineOn && s.spool >= 0.95 && c.present;
        // ground
        Obb h = v.hull();
        double g = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.6, 64);
        double agl = Double.isNaN(g) ? 64 : v.pose.y - g;
        s.altitude = v.pose.y;
        s.inWater = inWater(col);
        // inputs
        double collective = live ? c.throttle() : 0;
        s.collective = smooth(s.collective, collective, 0.15);
        s.pedal = smooth(s.pedal, live ? c.steer() : 0, 0.2);
        double cycAngle = t.physics("cyclic-angle", 22), gain = t.physics("cyclic-gain", 1.0);
        double targetPitch = 0, targetRoll = 0;
        if (live && !s.onGround) {
            // cyclic from the view: look down = nose down (forward), look up = nose up (back off); yaw difference = bank
            targetPitch = Pose.clamp(c.lookPitch * gain, -cycAngle, cycAngle) * -1;
            double dyaw = Pose.wrap(c.lookYaw - v.pose.yaw);
            targetRoll = Pose.clamp(dyaw * 0.5 * gain, -cycAngle, cycAngle);
            if (c.sneak) { targetPitch = 0; targetRoll = 0; }   // hold sneak = level out (also the exit key on the ground)
        }
        s.cyclicPitch = targetPitch / Math.max(1, cycAngle); s.cyclicRoll = targetRoll / Math.max(1, cycAngle);
        double tiltRate = t.physics("tilt-rate", 0.12);
        pitch = Pose.approach(pitch, s.onGround ? s.bodyPitchTarget : targetPitch, s.onGround ? 0.3 : tiltRate);
        roll = Pose.approach(roll, s.onGround ? s.bodyRollTarget : targetRoll, s.onGround ? 0.3 : tiltRate);
        // yaw: pedals + torque drift
        double torque = t.physics("torque", 0.15) * (s.spool > 0.95 && !s.onGround ? 1 : 0) * (t.physicsFlag("auto-stabilise", true) ? 0 : 1);
        double yawRate = live && !s.onGround ? s.pedal * t.turnRate : (live && s.onGround ? s.pedal * t.turnRate * 0.3 : 0);
        yawRate += torque * Math.max(0, s.collective + 0.5);
        if (live && !s.onGround && Math.abs(s.pedal) < 0.05 && t.physicsFlag("auto-stabilise", true)) {
            // gentle heading follow of the view when banking hard, so sustained turns feel natural
            double dyaw = Pose.wrap(c.lookYaw - v.pose.yaw);
            yawRate += Pose.clamp(dyaw * 0.03, -t.turnRate * 0.6, t.turnRate * 0.6) * Math.min(1, Math.abs(roll) / cycAngle);
        }
        s.yawRate = yawRate;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate);
        v.pose.pitch = pitch; v.pose.roll = roll;
        // forces: lift along the rotor axis
        double lift = t.physics("lift", 8) / 20.0, descent = t.physics("descent", 6) / 20.0;
        double groundEffect = 1 + t.physics("ground-effect", 0.15) * Pose.clamp(1 - agl / Math.max(1, h.half.y * 4), 0, 1);
        double maxAlt = t.physics("max-altitude", 0); if (maxAlt <= 0) maxAlt = v.world.getMaxHeight() - 4;
        double hoverThrust = gravity();   // exactly cancels gravity when hovering
        double vertCmd = s.collective > 0 ? (v.pose.y >= maxAlt ? 0 : lift * s.collective * 0.25) : s.collective < 0 ? descent * s.collective * 0.25 : 0;
        if (s.spool >= 0.95 && s.engineOn) {
            double thrust = (hoverThrust + vertCmd) * groundEffect * v.powerFactor();
            Vector3d up = v.pose.up();
            s.vel.x += up.x * thrust * 1.0 * (t.physics("cyclic-thrust", 2.2));
            s.vel.z += up.z * thrust * 1.0 * (t.physics("cyclic-thrust", 2.2));
            s.vel.y += up.y * thrust - gravity();
            if (s.collective == 0 && !s.onGround) s.vel.y *= 0.85;   // hover damping
            if (s.collective == 0 && s.onGround) s.vel.y = Math.min(0, s.vel.y);
        } else if (s.spool > 0.2 && !s.onGround) {
            // autorotation: the spinning rotor limits the descent
            double autoDesc = t.physics("autorotation-descent", 6) / 20.0;
            s.vel.y = Math.max(s.vel.y - gravity() * 0.5, -autoDesc);
        } else if (!s.onGround) s.vel.y = Math.max(-3.5, s.vel.y - gravity());
        else s.vel.y = Math.min(0, s.vel.y);
        double airDrag = t.physics("air-drag", 0.02);
        s.vel.x *= 1 - airDrag; s.vel.z *= 1 - airDrag;
        if (s.onGround) { s.vel.x *= 0.7; s.vel.z *= 0.7; }
        double max = t.maxSpeed / 20.0;
        double hs = Math.hypot(s.vel.x, s.vel.z);
        if (hs > max) { s.vel.x *= max / hs; s.vel.z *= max / hs; }
        boolean wasGround = s.onGround;
        double fallBps = -s.vel.y * 20;
        BlockCollider.Result r = move(col, new Vector3d(s.vel), false);
        if (r.hitHorizontal()) { s.vel.x *= 0.2; s.vel.z *= 0.2; }
        if (r.hitY) { if (fallBps > 0) { s.onGround = true; } else s.vel.y = 0; }
        s.vel.x = r.moved.x; s.vel.z = r.moved.z;
        // on the ground?
        double gNow = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.3, 0.6);
        boolean grounded = !Double.isNaN(gNow) && v.pose.y - gNow <= 0.12 && s.vel.y <= 0.001;
        if (grounded) followGround(col, 0.3, 25); else s.onGround = false;
        s.onGround = grounded || (r.hitY && fallBps > 0);
        if (s.onGround && !wasGround) landing(fallBps);
        s.airborne = !s.onGround;
        s.verticalSpeed = s.vel.y * 20; s.airspeed = s.vel.length() * 20; s.speed = new Vector3d(s.vel).dot(v.pose.forwardFlat()) * 20;
        s.rotorAngle = (s.rotorAngle + s.spool * t.physics("rotor-speed", 40)) % 360;
        s.rpm = s.spool * s.maxRpm;
        s.hover = live && Math.abs(s.collective) < 0.05 && !s.onGround;
        if (s.spool > 0.5 && agl < 4 && v.age % 2 == 0) {
            org.bukkit.block.Block ground = col.below(v.pose.x, g + 0.1, v.pose.z);
            if (!ground.isPassable()) v.world.spawnParticle(Particle.BLOCK, v.pose.x, g + 0.1, v.pose.z, 4, h.half.x, 0.05, h.half.z, ground.getBlockData());
        }
    }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn && s.spool <= 0.05) return "idle";
        if (s.spool < 0.95) return "start";
        if (s.onGround) return "hover";
        if (s.collective > 0.2) return "ascend"; if (s.collective < -0.2) return "descend";
        if (s.airspeed > 3) return "fly";
        return "hover";
    }
}
