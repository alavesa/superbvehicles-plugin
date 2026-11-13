package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Ground vehicle physics with no server dependency (unit-tested against {@link Terrain#flat}). One {@link #step} per
 * server tick:
 * <ol>
 *   <li>cached input -> throttle / brake / steer (only the DRIVER seat feeds input);</li>
 *   <li>velocity split into forward and lateral components (blocks/s) along the heading;</li>
 *   <li>forward: engine force (falls off with speed, so the engine itself caps the speed), service brake when the
 *       throttle opposes the motion, handbrake, rolling resistance and quadratic drag - all as accelerations
 *       (N / kg = blocks/s^2), resistance never reverses the motion (no creep), the parked brake holds when nobody
 *       drives or the throttle is released at walking speed;</li>
 *   <li>lateral: only the sideways component is damped by the grip (drift grip under the handbrake);</li>
 *   <li>heading: bicycle model (front wheels steer, angle shrinks with speed) or differential (tracks, turns on the
 *       spot); the velocity is rebuilt on the NEW heading so it follows the nose;</li>
 *   <li>gravity off the ground, integration in substeps through the block collider, collision response;</li>
 *   <li>ground follow: four corner probes set grounded, ride height and body pitch/roll.</li>
 * </ol>
 * Units: blocks, blocks/s, blocks/s^2, kg, N, degrees, degrees/s; the only per-tick conversion is the position delta
 * ({@link Units#perTick}).
 */
public final class WheeledVehicleController {
    public enum Steering { BICYCLE, DIFFERENTIAL }

    /** Hull of the vehicle at a pose (world OBB). */
    public interface Hull { Obb at(Pose pose); }

    /** Tunables, read from the vehicle YAML physics section (see README "Tuning"). */
    public static final class Params {
        public Steering steering = Steering.BICYCLE;
        public double mass = 2000;                 // kg
        public double engineForce = 9000;          // N at standstill
        public double maxSpeed = 25;               // blocks/s
        public double maxReverseSpeed = 8;         // blocks/s
        public double rollingResistance = 0.015;   // coefficient (x 9.81 = blocks/s^2)
        public double dragCoefficient = 0.4;       // N per (blocks/s)^2
        public double brakeForce = 16000;          // N
        public double handbrakeForce = 20000;      // N
        public double parkingBrakeForce = 12000;   // N, nobody driving / throttle released at walking speed
        public double lateralGrip = 8;             // 1/s: lateral speed decays by e^(-grip*t)
        public double driftGrip = 1.2;             // 1/s under the handbrake
        public double airGrip = 0.2;               // 1/s airborne
        public double stepHeight = 0.6;            // blocks
        public double steerAngle = 32;             // degrees at standstill
        public double steerFalloff = 0.85;         // fraction of the steer angle lost at max speed
        public double maxYawRate = 60;             // degrees/s hard limit
        public double wheelbase = 2.5;             // blocks
        public double turnScale = 1.0;             // multiplier on the yaw rate
        public double neutralTurnRate = 40;        // degrees/s, differential steering at standstill
        public double gravity = 32;                // blocks/s^2 (vanilla entities: 0.08 blocks/tick^2)
        public double maxTilt = 45;                // degrees
        public double suspensionRate = 0.35;       // per tick, how fast the body follows the ground plane
        public double groundTolerance = 0.35;      // blocks: a corner this far above the ground still has contact
        public double wheelRadius = 0.45;          // blocks, for the wheel spin
        public double waterFactor = 0.4;           // engine and speed limit in water
        public boolean autoStartEngine = true;

        /** Read from a vehicle type. Old 0.1.0 keys (engine-power, reverse-speed, grip, brake) are understood. */
        public static Params of(VehicleType t) {
            Params p = new Params();
            p.steering = t.vehicleClass == VehicleClass.TRACKED ? Steering.DIFFERENTIAL : Steering.BICYCLE;
            p.mass = Math.max(10, t.physics("mass", t.mass));
            p.maxSpeed = Math.max(0.5, t.physics("max-speed", t.maxSpeed));
            p.maxReverseSpeed = Math.max(0.2, t.physics("max-reverse-speed", t.physics("reverse-speed", p.maxSpeed * (p.steering == Steering.DIFFERENTIAL ? 0.4 : 0.35))));
            // without engine-force: enough to reach ~80% of max speed in about 8 s (0.1.0 files had engine-power,
            // a unit-less number that gave trucks less thrust than rolling resistance)
            double defaultForce = p.mass * p.maxSpeed * 1.0986 / 8.0 * 1.15;
            p.engineForce = Math.max(1, t.physics("engine-force", defaultForce));
            p.rollingResistance = Math.max(0, t.physics("rolling-resistance", p.steering == Steering.DIFFERENTIAL ? 0.03 : 0.015));
            p.dragCoefficient = Math.max(0, t.physics("drag-coefficient", 0.4 + p.mass / 20000.0));
            p.brakeForce = Math.max(0, t.physics("brake-force", p.mass * 8));
            p.handbrakeForce = Math.max(0, t.physics("handbrake-force", p.mass * 10));
            p.parkingBrakeForce = Math.max(0, t.physics("parking-brake-force", p.mass * 6));
            p.lateralGrip = Math.max(0, t.physics("lateral-grip", p.steering == Steering.DIFFERENTIAL ? 12 : 8));
            p.driftGrip = Math.max(0, t.physics("drift-grip", 1.2));
            p.stepHeight = Math.max(0, t.physics("step-height", t.stepHeight)) * t.scale;
            p.steerAngle = t.physics("steer-angle", 32);
            p.steerFalloff = Pose.clamp(t.physics("steer-falloff", 0.85), 0, 0.95);
            p.maxYawRate = Math.max(5, t.physics("max-yaw-rate", p.steering == Steering.DIFFERENTIAL ? 45 : 60));
            p.turnScale = t.physics("turn-scale", t.physics("turn-rate", 3.0) / 3.0);
            p.neutralTurnRate = t.physics("neutral-turn-rate", 40) * (t.physics("turn-rate", 2.0) / 2.0);
            p.maxTilt = t.physics("max-tilt", 45);
            p.suspensionRate = t.physics("suspension-rate", 0.35);
            p.autoStartEngine = t.physicsFlag("auto-start-engine", t.physicsFlag("auto-start", true));
            return p;
        }
    }

    /** One tick of input. */
    public static final class Input {
        public boolean driver;          // somebody sits in the DRIVER seat
        public double throttle;         // -1 (S) .. 1 (W)
        public double steer;            // -1 (A) .. 1 (D)
        public boolean handbrake;
        public boolean engineOn;
        public boolean outOfFuel;
        public double powerFactor = 1;  // damage
        public double speedLimitFactor = 1;   // unloaded chunks ahead
    }

    /** What happened this tick, for /sbv debug physics and the tests. */
    public static final class Report {
        public boolean driver, engineOn, outOfFuel, grounded, handbrake, parked;
        public double throttle, brake, steer;
        public double forwardSpeed, lateralSpeed;         // blocks/s, after the tick
        public double engineAccel, dragAccel, rollingAccel, brakeAccel;   // blocks/s^2 (signed toward motion where it applies)
        public double yawRate;                            // degrees/s
        public String collision = "free";
        public double lostSpeed;                          // blocks/s lost to collisions this tick
        public String reason = "";

        public String line(double fuel, double capacity) {
            return String.format(Locale.ROOT,
                "driver %s | in thr %.2f brk %.2f steer %.2f hb %s | engine %s fuel %.1f/%.0f | grounded %s | v fwd %.2f lat %.2f b/s (%.1f km/h) | a engine %.2f drag %.2f roll %.2f brake %.2f | yaw %.1f deg/s | %s | %s",
                driver ? "Y" : "N", throttle, brake, steer, handbrake ? "Y" : "N", engineOn ? "on" : "off", fuel, capacity, grounded ? "Y" : "N",
                forwardSpeed, lateralSpeed, Units.kmh(forwardSpeed), engineAccel, dragAccel, rollingAccel, brakeAccel, yawRate, collision, reason.isEmpty() ? "moving" : "why not moving: " + reason);
        }
    }

    private final Params p;
    private final Hull hull;
    private double bodyPitch, bodyRoll;
    private int sleepTicks;

    public WheeledVehicleController(Params p, Hull hull) {
        this.p = p;
        // collision uses the yaw-aligned hull: the body's pitch/roll (acceleration lean, ground plane) is visual and must
        // not dip the hull's corners into the floor; slopes are climbed by the step-up and the ground follow
        this.hull = pose -> { Pose flat = pose.copy(); flat.pitch = 0; flat.roll = 0; return hull.at(flat); };
    }
    public Params params() { return p; }

    /** One server tick. Mutates pose and state; returns the report. */
    public Report step(Pose pose, PhysicsState s, Input in, BlockCollider col) {
        Report r = new Report();
        double dt = Units.DT;
        r.driver = in.driver; r.engineOn = in.engineOn; r.outOfFuel = in.outOfFuel; r.handbrake = in.handbrake && in.driver;
        double throttle = in.driver ? Pose.clamp(in.throttle, -1, 1) : 0, steer = in.driver ? Pose.clamp(in.steer, -1, 1) : 0;
        boolean powered = in.driver && in.engineOn && !in.outOfFuel;
        // sleep: a vehicle resting on the ground with no input skips the collider (parked vehicles cost ~nothing);
        // any throttle, steering or handbrake input wakes it at once, and it never sleeps while the throttle is held
        boolean idle = Math.abs(throttle) < 0.01 && Math.abs(steer) < 0.01 && !in.handbrake;
        if (idle && s.onGround && s.vel.lengthSquared() < 1e-6 && Math.abs(s.throttle) < 0.01) {
            if (++sleepTicks > 10 && sleepTicks % 20 != 0) {
                r.grounded = true; r.parked = true; r.collision = "asleep";
                s.speed = 0; s.steer = 0; s.yawRate = 0;
                r.reason = whyNotMoving(r, in, s);
                return r;
            }
        } else sleepTicks = 0;
        s.throttle = Pose.approach(s.throttle, powered ? throttle : 0, 0.35);
        s.steer = Pose.approach(s.steer, steer, 0.35);

        // 1. ground contact at the current position
        groundProbe(pose, s, col);
        boolean grounded = s.onGround || s.inWater;
        r.grounded = grounded;

        // 2. velocity components along the heading (blocks/s)
        Vector3d f = pose.forwardFlat(), rt = pose.rightFlat();
        double vF = s.vel.x * f.x + s.vel.z * f.z, vL = s.vel.x * rt.x + s.vel.z * rt.z;
        double water = s.inWater ? p.waterFactor : 1;
        double vMax = p.maxSpeed * water * Pose.clamp(in.speedLimitFactor, 0.05, 1), vRev = p.maxReverseSpeed * water * Pose.clamp(in.speedLimitFactor, 0.05, 1);

        // 3. forward forces as accelerations (N / kg = blocks/s^2)
        double aEngine = 0, aBrake = 0, aRoll = 0, aDrag = 0;
        if (grounded) {
            double thr = s.throttle;
            boolean opposing = (thr > 0.05 && vF < -0.3) || (thr < -0.05 && vF > 0.3);
            if (opposing) {
                aBrake = Units.accel(p.brakeForce, p.mass) * Math.abs(thr);
                r.brake = Math.abs(thr);
            } else if (thr > 0) {
                double u = Math.max(0, vF) / Math.max(0.1, vMax);
                aEngine = Units.accel(p.engineForce, p.mass) * in.powerFactor * water * thr * Math.max(0, 1 - u * u);
            } else if (thr < 0) {
                double u = Math.max(0, -vF) / Math.max(0.1, vRev);
                aEngine = Units.accel(p.engineForce, p.mass) * 0.6 * in.powerFactor * water * thr * Math.max(0, 1 - u * u);
            }
            if (r.handbrake) aBrake += Units.accel(p.handbrakeForce, p.mass);
            boolean released = Math.abs(thr) < 0.05;
            if (!in.driver || (released && Math.abs(vF) < 1.5)) { aBrake += Units.accel(p.parkingBrakeForce, p.mass); r.parked = true; }
            aRoll = p.rollingResistance * 9.81;
        }
        aDrag = p.dragCoefficient * vF * vF / p.mass;
        r.engineAccel = aEngine; r.rollingAccel = aRoll; r.dragAccel = aDrag; r.brakeAccel = aBrake; r.throttle = s.throttle;
        // engine first, then every resistance toward zero, never past it (resistance cannot make a vehicle creep)
        double v1 = vF + aEngine * dt;
        double resist = (aBrake + aRoll + aDrag) * dt;
        double sign = Math.signum(v1);
        v1 = sign * Math.max(0, Math.abs(v1) - resist);
        vF = Pose.clamp(v1, -vRev * 1.02, vMax * 1.02);
        if (Math.abs(vF) < 0.02 && Math.abs(s.throttle) < 0.05) vF = 0;

        // 4. lateral grip damps ONLY the sideways component
        double grip = !grounded ? p.airGrip : r.handbrake ? p.driftGrip : p.lateralGrip * (s.inWater ? 0.4 : 1);
        vL *= Math.exp(-grip * dt);
        if (Math.abs(vL) < 0.01) vL = 0;

        // 5. heading
        double yawRate = 0;   // degrees/s, positive = turning right
        if (p.steering == Steering.BICYCLE) {
            double limit = p.steerAngle * (1 - p.steerFalloff * Math.min(1, Math.abs(vF) / Math.max(1, p.maxSpeed)));
            s.steerAngle = Pose.approach(s.steerAngle, s.steer * limit, 0.3);
            if (grounded && Math.abs(vF) > 0.05) yawRate = Math.toDegrees(vF / Math.max(0.5, p.wheelbase) * Math.tan(Math.toRadians(s.steerAngle))) * p.turnScale;
            if (r.handbrake && Math.abs(vF) > 4) yawRate *= 1.35;
        } else {
            s.steerAngle = 0;
            if (grounded && powered && Math.abs(s.steer) > 0.01) {
                double k = Math.abs(vF) < 0.5 ? 1.0 : Pose.clamp(1.2 - Math.abs(vF) / Math.max(1, p.maxSpeed), 0.35, 1.0);
                yawRate = s.steer * p.neutralTurnRate * k * (vF < -0.5 ? -1 : 1);
            }
        }
        if (Math.abs(yawRate) > p.maxYawRate) yawRate = Math.signum(yawRate) * p.maxYawRate;
        s.yawRate = yawRate * dt;
        pose.yaw = Pose.wrap(pose.yaw + yawRate * dt);
        r.yawRate = yawRate; r.steer = s.steer;
        Vector3d f2 = pose.forwardFlat(), r2 = pose.rightFlat();
        if (!finite(f2)) { r.reason = "heading is not a number (yaw " + pose.yaw + ")"; pose.yaw = 0; f2 = pose.forwardFlat(); r2 = pose.rightFlat(); }
        s.vel.x = f2.x * vF + r2.x * vL;
        s.vel.z = f2.z * vF + r2.z * vL;

        // 6. vertical
        if (!s.onGround) s.vel.y = Math.max(-70, s.vel.y - p.gravity * dt);
        else s.vel.y = Math.min(0, s.vel.y);
        if (s.inWater) { s.vel.y = Math.max(s.vel.y, -1.0); if (s.vel.y < 0) s.vel.y *= 0.5; }
        double fallSpeed = -s.vel.y;

        // 7. integrate through the collider (the one per-tick conversion)
        Vector3d delta = new Vector3d(Units.perTick(s.vel.x), Units.perTick(s.vel.y), Units.perTick(s.vel.z));
        int sub = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        Vector3d step = new Vector3d(delta).div(sub);
        double lost = 0;
        boolean blockedX = false, blockedZ = false, landed = false;
        String collision = "free";
        for (int i = 0; i < sub; i++) {
            BlockCollider.Result cr = col.sweep(hull.at(pose), step, p.stepHeight, true);
            pose.x += cr.moved.x; pose.y += cr.moved.y; pose.z += cr.moved.z;
            lost += cr.blockedSpeed;
            if (!collision.startsWith("blocked") && !collision.startsWith("depen")) collision = cr.describe();
            if (cr.hitX) { blockedX = true; step.x = 0; }
            if (cr.hitZ) { blockedZ = true; step.z = 0; }
            if (cr.hitY && step.y < 0) { landed = true; step.y = 0; }
        }
        if (blockedX) s.vel.x = 0;
        if (blockedZ) s.vel.z = 0;
        if (landed) s.vel.y = 0;
        r.collision = collision;
        r.lostSpeed = Units.perSecond(lost);
        s.lastImpact = r.lostSpeed;

        // 8. ground follow and body attitude
        boolean wasGround = s.onGround;
        followGround(pose, s, col);
        s.landingSpeed = s.onGround && !wasGround ? fallSpeed : 0;
        double lon = s.throttle * 1.2 * Math.min(1, Math.abs(vF) / Math.max(1, p.maxSpeed));
        double lat = yawRate * vF * 0.0012;
        bodyPitch = Pose.approach(bodyPitch, s.onGround ? s.bodyPitchTarget - lon : airPitch(s), s.onGround ? 0.3 : 0.08);
        bodyRoll = Pose.approach(bodyRoll, s.onGround ? s.bodyRollTarget + lat : bodyRoll * 0.98, 0.3);
        pose.pitch = bodyPitch; pose.roll = bodyRoll;

        // 9. derived state
        Vector3d f3 = pose.forwardFlat(), r3 = pose.rightFlat();
        s.speed = s.vel.x * f3.x + s.vel.z * f3.z;
        r.forwardSpeed = s.speed;
        r.lateralSpeed = s.vel.x * r3.x + s.vel.z * r3.z;
        s.wheelSpin = (s.wheelSpin + Math.toDegrees(s.speed * dt / Math.max(0.1, p.wheelRadius))) % 360;
        double halfW = Math.max(0.3, hull.at(pose).half.x);
        double turnLin = Math.toRadians(yawRate) * halfW;
        s.trackLeft += (s.speed - turnLin) * dt * 16; s.trackRight += (s.speed + turnLin) * dt * 16;
        s.distance += Math.hypot(Units.perTick(s.vel.x), Units.perTick(s.vel.z));
        s.airborne = !s.onGround;
        if (r.reason.isEmpty()) r.reason = whyNotMoving(r, in, s);
        return r;
    }

    /** The first failing link of the input -> physics chain, in plain English; empty when the vehicle moves. */
    static String whyNotMoving(Report r, Input in, PhysicsState s) {
        if (Math.abs(r.forwardSpeed) > 0.3) return "";
        if (!in.driver) return "nobody in the driver seat (passengers do not drive)";
        if (Math.abs(in.throttle) < 0.05 && Math.abs(in.steer) < 0.05) return "no throttle input (hold W or S)";
        if (in.outOfFuel) return "out of fuel";
        if (!in.engineOn) return "engine is off (press F or W to start)";
        if (!r.grounded) return "not on the ground (no wheel contact)";
        if (Math.abs(in.throttle) >= 0.05 && Math.abs(r.engineAccel) < 0.05) return "engine force too small (engine-force / mass)";
        if (Math.abs(r.engineAccel) <= r.rollingAccel + r.dragAccel + r.brakeAccel && !r.parked) return "resistance beats thrust (rolling-resistance, drag-coefficient, brakes)";
        if (r.collision.startsWith("blocked")) return "blocked by a block in front";
        if (Math.abs(in.throttle) < 0.05) return "only steering input: wheeled vehicles need speed to turn";
        return "";
    }

    private static boolean finite(Vector3d v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z) && v.lengthSquared() > 0.5; }
    private double airPitch(PhysicsState s) { return Pose.clamp(Math.toDegrees(Math.atan2(s.vel.y, Math.max(2.0, Math.abs(s.speed)))), -60, 60); }

    /** Contact check before the forces: are the hull's corners on the ground (or in water)? */
    private void groundProbe(Pose pose, PhysicsState s, BlockCollider col) {
        Obb h = hull.at(pose);
        s.inWater = col.isWater(h.center.x, h.center.y - h.half.y + 0.2, h.center.z);
        int touching = 0;
        for (double[] c : corners(h)) {
            Vector3d pt = cornerPoint(pose, c);
            double g = col.groundAt(pt.x, pose.y, pt.z, p.stepHeight + 0.1, p.groundTolerance + 0.05);
            if (!Double.isNaN(g) && pose.y - g <= p.groundTolerance && g - pose.y <= p.stepHeight + 0.1) touching++;
        }
        s.wheelContact = touching / 4.0;
        s.onGround = touching >= 2 && s.vel.y <= 0.5;
    }

    /** After the move: ride height on the contact points and the ground plane for the body attitude. */
    private void followGround(Pose pose, PhysicsState s, BlockCollider col) {
        Obb h = hull.at(pose);
        double[] hts = new double[4];
        int touching = 0;
        double[][] cs = corners(h);
        for (int i = 0; i < 4; i++) {
            Vector3d pt = cornerPoint(pose, cs[i]);
            double g = col.groundAt(pt.x, pose.y, pt.z, p.stepHeight + 0.1, 3.0);
            if (Double.isNaN(g)) hts[i] = pose.y - 3.0;
            else { hts[i] = g; if (pose.y - g <= p.groundTolerance && g - pose.y <= p.stepHeight + 0.1) touching++; }
        }
        s.wheelContact = touching / 4.0;
        double hl = h.half.z * 0.85, hw = h.half.x * 0.85;
        double[] ang = planeAngles(hts[0], hts[1], hts[2], hts[3], hl, hw);
        s.bodyPitchTarget = Pose.clamp(ang[0], -p.maxTilt, p.maxTilt);
        s.bodyRollTarget = Pose.clamp(ang[1], -p.maxTilt, p.maxTilt);
        boolean contact = touching >= 2 && s.vel.y <= 0.5;
        if (contact) {
            double target = Math.max(Math.max(hts[0], hts[1]), Math.max(hts[2], hts[3]));
            double avg = (hts[0] + hts[1] + hts[2] + hts[3]) / 4;
            target = Math.max(avg, target - 0.9);
            if (target > pose.y) pose.y = Math.min(target, pose.y + Math.max(0.05, p.stepHeight * 0.6));      // climb onto a step
            else pose.y = Pose.approach(pose.y, target, p.suspensionRate);   // settle down
            if (Math.abs(pose.y - target) < 0.002) pose.y = target;
            s.vel.y = 0;
        }
        s.onGround = contact;
        s.groundClearance = pose.y - (hts[0] + hts[1] + hts[2] + hts[3]) / 4;
    }

    /** {right, forward} offsets (blocks) of the four probe corners: FL FR BL BR. */
    private static double[][] corners(Obb h) {
        double hl = h.half.z * 0.85, hw = h.half.x * 0.85;
        return new double[][]{{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};
    }

    private static Vector3d cornerPoint(Pose pose, double[] c) {
        Vector3d f = pose.forwardFlat(), r = pose.rightFlat();
        return new Vector3d(pose.x, pose.y, pose.z).add(new Vector3d(r).mul(c[0])).add(new Vector3d(f).mul(c[1]));
    }

    /** Body pitch/roll from four corner heights (deg, nose up / right side down positive). */
    static double[] planeAngles(double fl, double fr, double bl, double br, double halfLength, double halfWidth) {
        double pitch = Math.toDegrees(Math.atan2((fl + fr) / 2 - (bl + br) / 2, 2 * Math.max(0.1, halfLength)));
        double roll = Math.toDegrees(Math.atan2((fl + bl) / 2 - (fr + br) / 2, 2 * Math.max(0.1, halfWidth)));
        return new double[]{pitch, roll};
    }
}
