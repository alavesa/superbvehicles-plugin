package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Helicopter flight with no server dependency (unit-tested). Units: blocks/s, blocks/s^2, degrees, degrees/s.
 * <ul>
 *   <li><b>Tilt to speed</b>: the rotor disc tilts with the body (pitch/roll, limited to max-tilt); the horizontal part of
 *       the rotor thrust accelerates the helicopter: a = 9.81 * tan(tilt) along the tilt direction.</li>
 *   <li><b>Quadratic drag</b> a = -c * |v| * v with c = a(max-tilt) / max-speed^2, so full forward cyclic saturates at
 *       max-speed by itself; a hard clamp at max-speed is the safety net.</li>
 *   <li><b>Releasing the cyclic</b> levels the disc; with auto-stabilise on, an extra brake decays the remaining speed
 *       to hover within hover-settle-time.</li>
 *   <li><b>Vertical</b>: the collective sets a target climb / descent rate, approached at vertical-accel and clamped;
 *       hover holds altitude; with the engine off a spinning rotor autorotates at autorotation-descent.</li>
 *   <li><b>Yaw</b>: pedals at up to max-yaw-rate degrees/s.</li>
 * </ul>
 */
public final class HelicopterModel {
    public static final class Params {
        public double maxSpeed = 28, climbRate = 8, descentRate = 10, maxYawRate = 70;
        public double maxTilt = 22, tiltRate = 40, cyclicGain = 1.0;
        public double verticalAccel = 8, spoolTime = 3, autorotationDescent = 6, groundEffect = 0.15;
        public double hoverSettleTime = 6, maxAltitude = 0;
        public double clampHorizontal = 28, clampClimb = 8, clampDescent = 10, clampYawRate = 70;   // hard limits
        public double gravity = 32;
        public boolean autoStabilise = true, autoStartEngine = true;

        public static Params of(VehicleType t) {
            Params p = new Params();
            p.maxSpeed = Math.max(1, t.physics("max-speed", t.maxSpeed));
            p.climbRate = Math.max(0.5, t.physics("climb-rate", t.physics("lift", 8)));
            p.descentRate = Math.max(0.5, t.physics("descent-rate", t.physics("descent", 10)));
            p.maxYawRate = Math.max(5, t.physics("max-yaw-rate", 70));
            p.maxTilt = Pose.clamp(t.physics("max-tilt", t.physics("cyclic-angle", 22)), 5, 45);
            p.tiltRate = Math.max(5, t.physics("tilt-rate-deg", 40));
            p.cyclicGain = t.physics("cyclic-gain", 1.0);
            p.verticalAccel = Math.max(0.5, t.physics("vertical-accel", 8));
            p.spoolTime = Math.max(0.05, t.physics("spool-time", 3));
            p.autorotationDescent = Math.max(1, t.physics("autorotation-descent", 6));
            p.groundEffect = t.physics("ground-effect", 0.15);
            p.hoverSettleTime = Math.max(0.5, t.physics("hover-settle-time", 6));
            p.maxAltitude = t.physics("max-altitude", 0);
            p.clampHorizontal = t.physics("max-horizontal-speed", p.maxSpeed);
            p.clampClimb = t.physics("max-climb-speed", p.climbRate);
            p.clampDescent = t.physics("max-descent-speed", Math.max(p.descentRate, p.autorotationDescent));
            p.clampYawRate = t.physics("max-yaw-rate-clamp", p.maxYawRate);
            p.autoStabilise = t.physicsFlag("auto-stabilise", true);
            p.autoStartEngine = t.physicsFlag("auto-start-engine", true);
            return p;
        }
        double maxTiltAccel() { return 9.81 * Math.tan(Math.toRadians(maxTilt)); }
        double dragCoefficient() { return maxTiltAccel() / (maxSpeed * maxSpeed); }
    }

    public static final class Input {
        public boolean driver, engineOn, levelOut;
        public double collective, pedal;          // -1..1
        public double lookPitch, lookYaw;         // the pilot's view (degrees)
        public boolean cyclicFromView = true;     // tilt follows the view (false: tests set cyclicPitch/cyclicRoll)
        public double cyclicPitch, cyclicRoll;    // -1..1 when not from the view (forward = +1)
        public double powerFactor = 1, speedLimitFactor = 1;
    }

    public static final class Report {
        public double thrust, drag, brake, horizontal, vertical, pitch, roll, yawRate;
        public boolean grounded;
        public String clamp = "";
        public String line(PhysicsState s) {
            return String.format(Locale.ROOT, "in coll %.2f pedal %.2f cyc %.2f/%.2f | v h %.2f b/s (%.0f km/h) v %.2f b/s | a thrust %.2f drag %.2f brake %.2f | tilt p %.1f r %.1f | yaw %.1f deg/s | grounded %s spool %.2f | clamp %s",
                s.collective, s.pedal, s.cyclicPitch, s.cyclicRoll, horizontal, Units.kmh(horizontal), vertical, thrust, drag, brake, pitch, roll, yawRate, grounded ? "Y" : "N", s.spool, clamp.isEmpty() ? "none" : clamp);
        }
    }

    private final Params p;
    private final WheeledVehicleController.Hull hull;
    private int sleepTicks;

    public HelicopterModel(Params p, WheeledVehicleController.Hull hull) { this.p = p; this.hull = hull; }
    public Params params() { return p; }

    public Report step(Pose pose, PhysicsState s, Input in, BlockCollider col) {
        Report r = new Report();
        double dt = Units.DT;
        s.spool = s.engineOn ? Math.min(1, s.spool + dt / p.spoolTime) : Math.max(0, s.spool - dt / p.spoolTime * (s.onGround ? 0.6 : 0.25));
        boolean live = s.engineOn && s.spool >= 0.95 && in.driver;
        // parked with the rotor stopped: nothing to simulate (re-checked once a second)
        if (!s.engineOn && s.spool <= 0 && s.onGround && s.vel.lengthSquared() < 1e-6) {
            if (++sleepTicks > 10 && sleepTicks % 20 != 0) { r.grounded = true; r.clamp = "asleep"; return r; }
        } else sleepTicks = 0;
        Obb h = hull.at(pose);
        double g = col.groundAt(pose.x, pose.y, pose.z, 0.3, 64);
        double agl = Double.isNaN(g) ? 64 : pose.y - g;
        s.altitude = pose.y;

        // controls
        s.collective = Pose.approach(s.collective, live ? Pose.clamp(in.collective, -1, 1) : 0, 0.15);
        s.pedal = Pose.approach(s.pedal, live ? Pose.clamp(in.pedal, -1, 1) : 0, 0.2);
        double cycP = 0, cycR = 0;   // -1..1, forward / right positive
        if (live && !s.onGround && !in.levelOut) {
            if (in.cyclicFromView) {
                cycP = Pose.clamp(in.lookPitch * p.cyclicGain / p.maxTilt, -1, 1);                       // look down = forward
                cycR = Pose.clamp(Pose.wrap(in.lookYaw - pose.yaw) * 0.5 * p.cyclicGain / p.maxTilt, -1, 1);
            } else { cycP = Pose.clamp(in.cyclicPitch, -1, 1); cycR = Pose.clamp(in.cyclicRoll, -1, 1); }
        }
        s.cyclicPitch = cycP; s.cyclicRoll = cycR;
        double targetPitch = s.onGround ? s.bodyPitchTarget : -cycP * p.maxTilt, targetRoll = s.onGround ? s.bodyRollTarget : cycR * p.maxTilt;
        pose.pitch = Pose.approachAngle(pose.pitch, targetPitch, p.tiltRate * dt);
        pose.roll = Pose.approachAngle(pose.roll, targetRoll, p.tiltRate * dt);
        pose.pitch = Pose.clamp(pose.pitch, -p.maxTilt, p.maxTilt);
        pose.roll = Pose.clamp(pose.roll, -p.maxTilt, p.maxTilt);
        r.pitch = pose.pitch; r.roll = pose.roll;

        // yaw (degrees/s)
        double yawRate = live ? s.pedal * p.maxYawRate * (s.onGround ? 0.3 : 1) : 0;
        if (live && !s.onGround && p.autoStabilise && Math.abs(s.pedal) < 0.05 && in.cyclicFromView) {
            double dyaw = Pose.wrap(in.lookYaw - pose.yaw);
            yawRate += Pose.clamp(dyaw * 0.6, -p.maxYawRate * 0.6, p.maxYawRate * 0.6) * Math.min(1, Math.abs(pose.roll) / p.maxTilt);
        }
        if (Math.abs(yawRate) > p.clampYawRate) { yawRate = Math.signum(yawRate) * p.clampYawRate; r.clamp = "yaw rate"; }
        pose.yaw = Pose.wrap(pose.yaw + yawRate * dt);
        s.yawRate = yawRate * dt;
        r.yawRate = yawRate;

        // horizontal: thrust from the tilted disc, quadratic drag, stabilise brake
        Vector3d vh = new Vector3d(s.vel.x, 0, s.vel.z);
        double speedLimit = p.maxSpeed * Pose.clamp(in.speedLimitFactor, 0.05, 1);
        if (s.spool >= 0.95 && s.engineOn && !s.onGround) {
            Vector3d up = pose.up();
            double k = 9.81 / Math.max(0.2, up.y) * in.powerFactor;
            Vector3d a = new Vector3d(up.x * k, 0, up.z * k);
            r.thrust = a.length();
            vh.add(a.mul(dt));
        }
        double sp = vh.length();
        double c = p.dragCoefficient() * (p.maxSpeed / speedLimit) * (p.maxSpeed / speedLimit);
        double drag = c * sp * sp;
        r.drag = drag;
        if (sp > 1e-6) vh.mul(Math.max(0, sp - drag * dt) / sp);
        boolean released = Math.abs(cycP) < 0.05 && Math.abs(cycR) < 0.05;
        if ((released && p.autoStabilise) || !live) {
            double kb = 3.0 / p.hoverSettleTime;     // e^-3 = 5% left after the settle time
            r.brake = kb * vh.length();
            vh.mul(Math.exp(-kb * dt));
        }
        if (s.onGround) vh.mul(Math.exp(-6 * dt));
        sp = vh.length();
        double hClamp = Math.min(p.clampHorizontal, speedLimit);
        if (sp > hClamp) { vh.mul(hClamp / sp); r.clamp = speedLimit < p.maxSpeed ? "unloaded chunks ahead" : "max horizontal speed"; }
        if (vh.length() < 0.02) vh.set(0, 0, 0);

        // vertical
        double maxAlt = p.maxAltitude > 0 ? p.maxAltitude : Double.MAX_VALUE;
        double vy = s.vel.y;
        if (s.spool >= 0.95 && s.engineOn) {
            double target = s.collective > 0.02 ? p.climbRate * s.collective : s.collective < -0.02 ? -p.descentRate * -s.collective : 0;
            if (pose.y >= maxAlt && target > 0) target = 0;
            double ge = 1 + p.groundEffect * Pose.clamp(1 - agl / Math.max(1, h.half.y * 4), 0, 1);
            if (target < 0) target /= ge;   // ground effect cushions the last metres of a descent
            double maxStep = p.verticalAccel * dt;
            vy += Pose.clamp(target - vy, -maxStep, maxStep);
            if (s.onGround && target <= 0) vy = 0;
        } else if (s.spool > 0.2 && !s.onGround) {
            vy = Math.max(vy - p.gravity * 0.5 * dt, -p.autorotationDescent);
        } else if (!s.onGround) vy = Math.max(-70, vy - p.gravity * dt);
        else vy = Math.min(0, vy);
        if (vy > p.clampClimb) { vy = p.clampClimb; r.clamp = "max climb"; }
        if (vy < -p.clampDescent && (s.engineOn || s.spool > 0.2)) { vy = -p.clampDescent; r.clamp = "max descent"; }
        s.vel.set(vh.x, vy, vh.z);

        // integrate
        Vector3d delta = new Vector3d(Units.perTick(s.vel.x), Units.perTick(s.vel.y), Units.perTick(s.vel.z));
        int sub = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        Vector3d step = new Vector3d(delta).div(sub);
        double lost = 0;
        boolean landed = false;
        double fall = -s.vel.y;
        double vxB = s.vel.x, vzB = s.vel.z;
        for (int i = 0; i < sub; i++) {
            BlockCollider.Result cr = col.sweep(hull.at(pose), step, 0, false);
            pose.x += cr.moved.x; pose.y += cr.moved.y; pose.z += cr.moved.z;
            lost += cr.blockedSpeed;
            if (cr.hitX) { s.vel.x = 0; step.x = 0; }
            if (cr.hitZ) { s.vel.z = 0; step.z = 0; }
            if (cr.hitY) { if (step.y < 0) landed = true; s.vel.y = 0; step.y = 0; }
        }
        s.lastImpact = Math.hypot(vxB - s.vel.x, vzB - s.vel.z);   // speed taken away by blocks
        double gNow = col.groundAt(pose.x, pose.y, pose.z, 0.3, 0.6);
        boolean wasGround = s.onGround;
        s.onGround = (!Double.isNaN(gNow) && pose.y - gNow <= 0.12 && s.vel.y <= 0.001) || landed;
        if (s.onGround && !Double.isNaN(gNow) && pose.y > gNow) pose.y = Pose.approach(pose.y, gNow, 0.5);
        s.landingSpeed = s.onGround && !wasGround ? fall : 0;
        r.grounded = s.onGround;
        s.airborne = !s.onGround;
        s.verticalSpeed = s.vel.y;
        s.airspeed = s.vel.length();
        s.speed = s.vel.x * pose.forwardFlat().x + s.vel.z * pose.forwardFlat().z;
        s.hover = live && Math.abs(s.collective) < 0.05 && !s.onGround;
        r.horizontal = Math.hypot(s.vel.x, s.vel.z); r.vertical = s.vel.y;
        s.activeClamp = r.clamp;
        return r;
    }
}
