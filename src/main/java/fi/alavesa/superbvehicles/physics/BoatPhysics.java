package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

/**
 * Boats: buoyancy from how deep the hull sits (four hull samples), drag in water, a rudder whose authority grows
 * with speed, planing (the bow lifts and drag drops at speed), a wake, and no propulsion on land (beached boats
 * only slide). Extra YAML keys: draft (blocks the hull sits in water), buoyancy, water-drag, planing-speed, rudder.
 */
public final class BoatPhysics extends VehiclePhysics {
    private double bodyPitch, bodyRoll;
    public BoatPhysics(Vehicle v) { super(v); s.idleRpm = t.physics("idle-rpm", 600); s.maxRpm = t.physics("max-rpm", 5000); }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "boat");
        double dt = 1 / 20.0, max = t.maxSpeed, revMax = t.physics("reverse-speed", t.maxSpeed * 0.3);
        double draft = t.physics("draft", 0.5), buoyancy = t.physics("buoyancy", 0.12);
        Obb h = v.hull();
        Vector3d f = v.pose.forwardFlat(), r = v.pose.rightFlat();
        double hl = h.half.z * 0.8, hw = h.half.x * 0.8;
        double[][] corners = {{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};
        double[] surf = new double[4]; int wet = 0; double sum = 0;
        for (int i = 0; i < 4; i++) {
            Vector3d p = new Vector3d(v.pose.x, v.pose.y, v.pose.z).add(new Vector3d(r).mul(corners[i][0])).add(new Vector3d(f).mul(corners[i][1]));
            double ws = col.waterSurface(p.x, v.pose.y + draft * 0.5, p.z);
            if (Double.isNaN(ws)) { surf[i] = Double.NaN; continue; }
            surf[i] = ws; wet++; sum += ws;
        }
        s.waterFraction = wet / 4.0;
        s.inWater = wet > 0;
        double throttle = c.present && s.engineOn && s.inWater ? c.throttle() : 0;
        s.throttle = smooth(s.throttle, throttle, 0.2);
        s.steer = smooth(s.steer, c.present ? c.steer() : 0, 0.25);
        double speed = s.speed;
        double accel = t.enginePower / Math.max(100, t.mass) * 20 * v.powerFactor();
        if (s.inWater) {
            double planing = Pose.clamp((Math.abs(speed) - t.physics("planing-speed", max * 0.45)) / Math.max(1, max * 0.3), 0, 1);
            s.wake = planing;
            double drag = t.physics("water-drag", 0.02) * (1 - 0.5 * planing);
            if (s.throttle > 0) speed += (speed < -0.3 ? accel : accel * Math.max(0.15, 1 - speed / max)) * s.throttle * dt;
            else if (s.throttle < 0) speed += (speed > 0.3 ? accel * 0.8 : accel * 0.4 * Math.max(0.1, 1 + speed / revMax)) * s.throttle * dt;
            speed -= Math.signum(speed) * Math.min(Math.abs(speed), (0.6 + drag * speed * speed) * dt);
        } else if (s.onGround) { speed -= Math.signum(speed) * Math.min(Math.abs(speed), 6 * dt); s.wake = 0; }
        speed = Pose.clamp(speed, -revMax, max);
        if (Math.abs(speed) < 0.02) speed = 0;
        double rudder = t.physics("rudder", 1.0);
        double yawRate = s.inWater && Math.abs(speed) > 0.2 ? s.steer * t.turnRate * rudder * Pose.clamp(Math.abs(speed) / Math.max(1, max * 0.4), 0.2, 1.0) * Math.signum(speed) : 0;
        if (s.inWater && Math.abs(speed) <= 0.2 && s.engineOn && s.steer != 0 && s.throttle != 0) yawRate = s.steer * t.turnRate * 0.3;
        s.yawRate = yawRate;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate);
        double grip = s.inWater ? t.physics("grip", 0.12) : 0.02;
        Vector3d desired = v.pose.forwardFlat().mul(speed * dt);
        Vector3d flat = new Vector3d(s.vel.x, 0, s.vel.z).mul(1 - grip).add(desired.mul(grip));
        s.vel.x = flat.x; s.vel.z = flat.z;
        // vertical: buoyancy toward (surface - draft), gravity otherwise
        if (wet > 0) {
            double target = sum / wet - draft;
            double depth = target - v.pose.y;
            s.vel.y += Pose.clamp(depth * buoyancy, -0.15, 0.15);
            s.vel.y *= 0.75;
            s.onGround = false;
        } else {
            s.vel.y = Math.max(-3.5, s.vel.y - gravity());
        }
        boolean wasGround = s.onGround;
        double fallBps = -s.vel.y * 20;
        BlockCollider.Result res = move(col, new Vector3d(s.vel), false);
        if (res.hitHorizontal()) speed *= 0.3;
        if (res.hitY && s.vel.y <= 0) { s.onGround = true; }
        s.speed = speed;
        s.vel.x = res.moved.x; s.vel.z = res.moved.z;
        if (wet == 0) { followGround(col, 0.3, 30); if (s.onGround && !wasGround) landing(fallBps); }
        // attitude: bow lifts when planing, banks into turns, follows the wave plane
        double pitchTarget, rollTarget;
        if (wet > 0) {
            double fl = Double.isNaN(surf[0]) ? sum / wet : surf[0], fr = Double.isNaN(surf[1]) ? sum / wet : surf[1], bl = Double.isNaN(surf[2]) ? sum / wet : surf[2], br = Double.isNaN(surf[3]) ? sum / wet : surf[3];
            double[] ang = planeAngles(fl, fr, bl, br, hl, hw);
            pitchTarget = ang[0] + s.wake * t.physics("planing-pitch", 6) * Math.signum(speed);
            rollTarget = ang[1] + yawRate * 2.5 * Math.min(1, Math.abs(speed) / Math.max(1, max));
        } else { pitchTarget = s.onGround ? s.bodyPitchTarget : bodyPitch; rollTarget = s.onGround ? s.bodyRollTarget : bodyRoll; }
        bodyPitch = Pose.approach(bodyPitch, pitchTarget, 0.1); bodyRoll = Pose.approach(bodyRoll, rollTarget, 0.1);
        v.pose.pitch = bodyPitch; v.pose.roll = bodyRoll;
        double rpmTarget = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * Pose.clamp(Math.abs(s.throttle) * 0.5 + 0.5 * Math.abs(speed) / Math.max(1, max), 0, 1) : 0;
        s.rpm = Pose.approach(s.rpm, rpmTarget, 0.15);
        s.propAngle = (s.propAngle + (s.engineOn ? 20 + 40 * Math.abs(s.throttle) : 0)) % 360;
        s.gear = speed < -0.2 ? -1 : speed > 0.2 ? 1 : 0;
        if (s.inWater && Math.abs(speed) > 1.5 && v.age % 2 == 0) {
            Vector3d back = v.pose.forwardFlat().mul(-hl);
            v.world.spawnParticle(Particle.SPLASH, v.pose.x + back.x, v.pose.y + 0.1, v.pose.z + back.z, (int) (4 + 10 * s.wake), hw, 0.1, 0.4, 0);
            if (s.wake > 0.2) v.world.spawnParticle(Particle.CLOUD, v.pose.x + back.x, v.pose.y + 0.05, v.pose.z + back.z, 2, hw * 0.5, 0.05, 0.3, 0.01);
        }
    }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn || Math.abs(s.speed) <= 0.3) return "idle";
        if (s.steer < -0.3) return "left"; if (s.steer > 0.3) return "right";
        return s.speed < 0 ? "reverse" : "drive";
    }
}
