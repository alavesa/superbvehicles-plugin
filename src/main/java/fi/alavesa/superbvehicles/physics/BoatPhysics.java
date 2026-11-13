package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Boats (blocks/s throughout): buoyancy from four hull samples, an engine force that falls off toward max-speed,
 * quadratic water drag (lower when planing), a rudder whose authority grows with speed, lateral grip on the
 * sideways component only, and no propulsion on land. YAML keys: mass, engine-force (N), max-speed,
 * max-reverse-speed, turn-rate, draft, buoyancy (1/s), water-drag (N per (b/s)^2), planing-speed, planing-pitch,
 * rudder, lateral-grip (1/s).
 */
public final class BoatPhysics extends VehiclePhysics {
    private double bodyPitch, bodyRoll;
    private String lastLine = "";

    public BoatPhysics(Vehicle v) { super(v); s.idleRpm = t.physics("idle-rpm", 600); s.maxRpm = t.physics("max-rpm", 5000); }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "boat", t.physicsFlag("auto-start-engine", true));
        double dt = Units.DT, chunk = chunkSpeedFactor(col);
        double mass = Math.max(10, t.physics("mass", t.mass));
        double max = t.physics("max-speed", t.maxSpeed) * chunk, revMax = t.physics("max-reverse-speed", t.physics("reverse-speed", max * 0.3)) * chunk;
        double force = t.physics("engine-force", mass * max * 1.1 / 6.0 * 1.2);
        double draft = t.physics("draft", 0.5), buoyancy = t.physics("buoyancy", 6.0);
        if (buoyancy < 1) buoyancy *= Units.TPS * 2;   // 0.1.0 files held a per-tick factor
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
        boolean powered = c.present && s.engineOn && s.inWater && !v.outOfFuel();
        s.throttle = smooth(s.throttle, powered ? c.throttle() : 0, 0.2);
        s.steer = smooth(s.steer, c.present ? c.steer() : 0, 0.25);
        double vF = s.vel.x * f.x + s.vel.z * f.z, vL = s.vel.x * r.x + s.vel.z * r.z;
        double aEngine = 0, aDrag = 0;
        if (s.inWater) {
            double planing = Pose.clamp((Math.abs(vF) - t.physics("planing-speed", max * 0.45)) / Math.max(1, max * 0.3), 0, 1);
            s.wake = planing;
            if (s.throttle > 0) { double u = Math.max(0, vF) / Math.max(0.5, max); aEngine = force / mass * v.powerFactor() * s.throttle * Math.max(0, 1 - u * u); }
            else if (s.throttle < 0) { double u = Math.max(0, -vF) / Math.max(0.5, revMax); aEngine = force / mass * 0.5 * v.powerFactor() * s.throttle * Math.max(0, 1 - u * u); }
            double cd = t.physics("water-drag", mass * 0.3 / Math.max(1, max * max)) * (1 - 0.4 * planing);
            aDrag = cd * vF * vF / mass + 0.4;
            vL *= Math.exp(-t.physics("lateral-grip", 2.5) * dt);
        } else if (s.onGround) { aDrag = 8; vL *= Math.exp(-6 * dt); s.wake = 0; }
        double v1 = vF + aEngine * dt;
        double sign = Math.signum(v1);
        vF = Pose.clamp(sign * Math.max(0, Math.abs(v1) - aDrag * dt), -revMax * 1.02, max * 1.02);
        if (Math.abs(vF) < 0.02 && Math.abs(s.throttle) < 0.05) vF = 0;
        double rudder = t.physics("rudder", 1.0);
        double yawRate = 0;   // degrees/s
        if (s.inWater && Math.abs(vF) > 0.2) yawRate = s.steer * t.physics("yaw-rate", t.turnRate * Units.TPS) * rudder * Pose.clamp(Math.abs(vF) / Math.max(1, max * 0.4), 0.2, 1.0) * Math.signum(vF);
        else if (s.inWater && powered && s.steer != 0 && s.throttle != 0) yawRate = s.steer * t.physics("yaw-rate", t.turnRate * Units.TPS) * 0.3;
        s.yawRate = yawRate * dt;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate * dt);
        Vector3d f2 = v.pose.forwardFlat(), r2 = v.pose.rightFlat();
        s.vel.x = f2.x * vF + r2.x * vL; s.vel.z = f2.z * vF + r2.z * vL;
        if (wet > 0) {
            double target = sum / wet - draft;
            double depth = target - v.pose.y;                         // blocks below where it should float
            s.vel.y += Pose.clamp(depth * buoyancy, -3, 3);
            s.vel.y *= Math.exp(-5 * dt);
            s.onGround = false;
        } else s.vel.y = Math.max(-70, s.vel.y - gravity() * dt);
        boolean wasGround = s.onGround;
        double fall = -s.vel.y;
        BlockCollider.Result res = integrate(col, false);
        if (res.hitY && fall > 0) s.onGround = true;
        if (wet == 0) { followGround(col, 0.3, 30); if (s.onGround && !wasGround) landing(fall); }
        double pitchTarget, rollTarget;
        if (wet > 0) {
            double avg = sum / wet;
            double[] ang = planeAngles(Double.isNaN(surf[0]) ? avg : surf[0], Double.isNaN(surf[1]) ? avg : surf[1], Double.isNaN(surf[2]) ? avg : surf[2], Double.isNaN(surf[3]) ? avg : surf[3], hl, hw);
            pitchTarget = ang[0] + s.wake * t.physics("planing-pitch", 6) * Math.signum(vF);
            rollTarget = ang[1] + yawRate * 0.12 * Math.min(1, Math.abs(vF) / Math.max(1, max));
        } else { pitchTarget = s.onGround ? s.bodyPitchTarget : bodyPitch; rollTarget = s.onGround ? s.bodyRollTarget : bodyRoll; }
        bodyPitch = Pose.approach(bodyPitch, pitchTarget, 0.1); bodyRoll = Pose.approach(bodyRoll, rollTarget, 0.1);
        v.pose.pitch = bodyPitch; v.pose.roll = bodyRoll;
        s.speed = s.vel.x * f2.x + s.vel.z * f2.z;
        s.rpm = Pose.approach(s.rpm, s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * Pose.clamp(Math.abs(s.throttle) * 0.5 + 0.5 * Math.abs(s.speed) / Math.max(1, max), 0, 1) : 0, 0.15);
        s.propAngle = (s.propAngle + (s.engineOn ? 20 + 40 * Math.abs(s.throttle) : 0)) % 360;
        s.gear = s.speed < -0.2 ? -1 : s.speed > 0.2 ? 1 : 0;
        lastLine = String.format(Locale.ROOT, "driver %s | thr %.2f steer %.2f | engine %s fuel %.1f | wet %d/4 | v fwd %.2f lat %.2f b/s (%.1f km/h) vy %.2f | a engine %.2f drag %.2f | %s",
            c.present ? "Y" : "N", s.throttle, s.steer, s.engineOn ? "on" : "off", v.fuel, wet, s.speed, vL, Units.kmh(s.speed), s.vel.y, aEngine, aDrag, res.describe());
        if (s.inWater && Math.abs(s.speed) > 1.5 && v.age % 2 == 0) {
            Vector3d back = v.pose.forwardFlat().mul(-hl);
            v.world.spawnParticle(Particle.SPLASH, v.pose.x + back.x, v.pose.y + 0.1, v.pose.z + back.z, (int) (4 + 10 * s.wake), hw, 0.1, 0.4, 0);
            if (s.wake > 0.2) v.world.spawnParticle(Particle.CLOUD, v.pose.x + back.x, v.pose.y + 0.05, v.pose.z + back.z, 2, hw * 0.5, 0.05, 0.3, 0.01);
        }
    }

    @Override public String debugLine() { return lastLine; }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn || Math.abs(s.speed) <= 0.3) return "idle";
        if (s.steer < -0.3) return "left"; if (s.steer > 0.3) return "right";
        return s.speed < 0 ? "reverse" : "drive";
    }
}
