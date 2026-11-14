package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * Boats with no server dependency (unit-tested). Units: blocks/s, blocks/s^2, N, kg, degrees/s.
 * <ul>
 *   <li><b>In water</b>: buoyancy toward the float line (surface - draft), engine force falling off toward
 *       max-speed, quadratic water drag (lower when planing), rudder authority growing with speed, lateral grip on the
 *       sideways component only.</li>
 *   <li><b>Shore</b>: the hull steps up slabs and blocks like a car (step-height), so it can run up a ramp or a beach.
 *       Thrust scales with how much of the hull is still in water.</li>
 *   <li><b>Dry land</b>: strong ground friction slows the boat at once; for land-grace seconds the engine still pushes
 *       (land-push blocks/s^2 against land-friction, only toward the water it came from) so you can steer or reverse back - after that the boat is
 *       stranded and the engine does nothing until water is under it again.</li>
 * </ul>
 */
public final class BoatModel {
    public static final class Params {
        public double mass = 1400, engineForce = 8000, maxSpeed = 22, maxReverseSpeed = 5, yawRate = 52;
        public double draft = 0.45, buoyancy = 6, waterDrag = 1.2, planingSpeed = 9, planingPitch = 6, rudder = 1, lateralGrip = 2.5;
        public double stepHeight = 0.6, landFriction = 9, landGrace = 3.0, landPush = 13, gravity = 32;
        public boolean autoStartEngine = true;

        public static Params of(VehicleType t) {
            Params p = new Params();
            p.mass = Math.max(10, t.physics("mass", t.mass));
            p.maxSpeed = Math.max(0.5, t.physics("max-speed", t.maxSpeed));
            p.maxReverseSpeed = Math.max(0.2, t.physics("max-reverse-speed", t.physics("reverse-speed", p.maxSpeed * 0.3)));
            p.engineForce = t.physics("engine-force", p.mass * p.maxSpeed * 1.1 / 6.0 * 1.2);
            p.yawRate = t.physics("yaw-rate", t.turnRate * Units.TPS);
            p.draft = t.physics("draft", 0.5);
            p.buoyancy = t.physics("buoyancy", 6.0); if (p.buoyancy < 1) p.buoyancy *= Units.TPS * 2;   // 0.1.0 per-tick factor
            p.waterDrag = t.physics("water-drag", p.mass * 0.3 / Math.max(1, p.maxSpeed * p.maxSpeed));
            p.planingSpeed = t.physics("planing-speed", p.maxSpeed * 0.45);
            p.planingPitch = t.physics("planing-pitch", 6);
            p.rudder = t.physics("rudder", 1.0);
            p.lateralGrip = t.physics("lateral-grip", 2.5);
            p.stepHeight = Math.max(0, t.physics("step-height", 0.6)) * t.scale;
            p.landFriction = t.physics("land-friction", 9);
            p.landGrace = Math.max(0, t.physics("land-grace", 3.0));
            p.landPush = Math.max(0, t.physics("land-push", 13));
            p.autoStartEngine = t.physicsFlag("auto-start-engine", true);
            return p;
        }
    }

    public static final class Input {
        public boolean driver, engineOn, outOfFuel;
        public double throttle, steer, powerFactor = 1, speedLimitFactor = 1;
    }

    public static final class Report {
        public int wet; public double drySeconds, engineAccel, dragAccel; public boolean stranded, grounded; public String collision = "free";
        public String line(PhysicsState s, double fuel) {
            return String.format(Locale.ROOT, "thr %.2f steer %.2f | engine %s fuel %.1f | water %d/5 | %s | v fwd %.2f b/s (%.1f km/h) vy %.2f | a engine %.2f drag %.2f | %s",
                s.throttle, s.steer, s.engineOn ? "on" : "off", fuel, wet, stranded ? "STRANDED - no water under the hull" : wet == 0 ? String.format(Locale.ROOT, "on land %.1f s", drySeconds) : grounded ? "beached" : "afloat",
                s.speed, Units.kmh(s.speed), s.vel.y, engineAccel, dragAccel, collision);
        }
    }

    private final Params p;
    private final WheeledVehicleController.Hull hull;
    private int dryTicks;
    private double lastWetX, lastWetZ;
    private boolean hasWet;
    private double bodyPitch, bodyRoll;

    public BoatModel(Params p, WheeledVehicleController.Hull hull) {
        this.p = p;
        this.hull = pose -> { Pose flat = pose.copy(); flat.pitch = 0; flat.roll = 0; return hull.at(flat); };
    }
    public Params params() { return p; }
    public boolean stranded() { return dryTicks > p.landGrace * Units.TPS; }

    public Report step(Pose pose, PhysicsState s, Input in, BlockCollider col) {
        Report r = new Report();
        double dt = Units.DT;
        s.visualYOffset *= 0.55; if (Math.abs(s.visualYOffset) < 0.005) s.visualYOffset = 0;
        Obb h = hull.at(pose);
        Vector3d f = pose.forwardFlat(), rt = pose.rightFlat();
        double hl = h.half.z * 0.8, hw = h.half.x * 0.8;
        double[][] pts = {{0, 0}, {-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};   // centre + FL FR BL BR
        double[] surf = new double[5];
        int wet = 0; double sum = 0;
        for (int i = 0; i < 5; i++) {
            double x = pose.x + rt.x * pts[i][0] + f.x * pts[i][1], z = pose.z + rt.z * pts[i][0] + f.z * pts[i][1];
            double ws = col.waterSurface(x, pose.y + p.draft * 0.5, z);
            surf[i] = ws;
            if (!Double.isNaN(ws) && ws > pose.y - 0.3) { wet++; sum += ws; }   // water at most 0.3 below the hull counts (a stern over the water)
        }
        r.wet = wet;
        s.waterFraction = wet / 5.0;
        s.inWater = wet > 0;
        if (wet > 0) { dryTicks = Math.max(0, dryTicks - 4); lastWetX = pose.x; lastWetZ = pose.z; hasWet = true; } else dryTicks++;
        r.drySeconds = dryTicks * dt;
        r.stranded = stranded();
        boolean powered = in.driver && in.engineOn && !in.outOfFuel;
        // thrust: the propeller needs water (share by wet samples); on dry land a push that beats the land friction at
        // first and fades out over land-grace seconds, then nothing (stranded)
        double share = wet >= 3 ? 1 : wet > 0 ? 0.5 + 0.5 * wet / 3.0 : 0;
        double landAcc = wet == 0 && !r.stranded ? p.landPush : 0;   // a steady push during the grace, then nothing
        // ... and only TOWARD the water it came from: driving further inland is impossible
        if (landAcc > 0) {
            double dir = Math.signum(in.throttle);
            double tx = hasWet ? lastWetX - pose.x : 0, tz = hasWet ? lastWetZ - pose.z : 0;
            if (!hasWet || (f.x * tx + f.z * tz) * dir <= 0) landAcc = 0;
        }
        s.throttle = Pose.approach(s.throttle, powered ? Pose.clamp(in.throttle, -1, 1) : 0, 0.2);
        s.steer = Pose.approach(s.steer, in.driver ? Pose.clamp(in.steer, -1, 1) : 0, 0.25);
        double max = p.maxSpeed * Pose.clamp(in.speedLimitFactor, 0.05, 1), rev = p.maxReverseSpeed * Pose.clamp(in.speedLimitFactor, 0.05, 1);
        double vF = s.vel.x * f.x + s.vel.z * f.z, vL = s.vel.x * rt.x + s.vel.z * rt.z;
        double aEngine = 0, aDrag;
        if (s.throttle > 0) { double u = Math.max(0, vF) / Math.max(0.5, max); aEngine = (wet > 0 ? p.engineForce / p.mass * share * Math.max(0, 1 - u * u) : landAcc) * in.powerFactor * s.throttle; }
        else if (s.throttle < 0) { double u = Math.max(0, -vF) / Math.max(0.5, rev); aEngine = (wet > 0 ? p.engineForce / p.mass * 0.7 * share * Math.max(0, 1 - u * u) : landAcc) * in.powerFactor * s.throttle; }
        double planing = wet > 0 ? Pose.clamp((Math.abs(vF) - p.planingSpeed) / Math.max(1, max * 0.3), 0, 1) : 0;
        s.wake = planing;
        boolean dryGround = wet == 0 || (wet < 3 && s.onGround);
        if (dryGround) { aDrag = p.landFriction * (wet == 0 ? 1 : 0.15); vL *= Math.exp(-6 * dt); }   // beached with water under the stern: little friction
        else { aDrag = p.waterDrag * (1 - 0.4 * planing) * vF * vF / p.mass + 0.4; vL *= Math.exp(-p.lateralGrip * dt); }
        r.engineAccel = aEngine; r.dragAccel = aDrag;
        double v1 = vF + aEngine * dt;
        vF = Pose.clamp(Math.signum(v1) * Math.max(0, Math.abs(v1) - aDrag * dt), -rev * 1.02, max * 1.02);
        if (Math.abs(vF) < 0.02 && Math.abs(s.throttle) < 0.05) vF = 0;
        // rudder: needs speed through water; on land the hull only pivots a little while it still slides
        double yawRate = 0;
        if (Math.abs(vF) > 0.2) yawRate = s.steer * p.yawRate * p.rudder * Pose.clamp(Math.abs(vF) / Math.max(1, max * 0.4), 0.2, 1.0) * Math.signum(vF) * (wet > 0 ? 1 : 0.5);
        else if (wet > 0 && powered && s.steer != 0 && s.throttle != 0) yawRate = s.steer * p.yawRate * 0.3;
        s.yawRate = yawRate * dt;
        pose.yaw = Pose.wrap(pose.yaw + yawRate * dt);
        Vector3d f2 = pose.forwardFlat(), r2 = pose.rightFlat();
        s.vel.x = f2.x * vF + r2.x * vL; s.vel.z = f2.z * vF + r2.z * vL;
        // vertical: float on the water, else gravity with ground contact like a car
        if (wet > 0 && !s.onGround) {
            double target = sum / wet - p.draft;
            s.vel.y += Pose.clamp((target - pose.y) * p.buoyancy, -3, 3);
            s.vel.y *= Math.exp(-5 * dt);
        } else if (!s.onGround) s.vel.y = Math.max(-70, s.vel.y - p.gravity * dt);
        else s.vel.y = 0;
        double fall = -s.vel.y, yBefore = pose.y;
        Vector3d delta = new Vector3d(Units.perTick(s.vel.x), Units.perTick(s.vel.y), Units.perTick(s.vel.z));
        int sub = Math.max(1, (int) Math.ceil(delta.length() / 0.5));
        Vector3d step = new Vector3d(delta).div(sub);
        double lost = 0; boolean landed = false;
        double vxB = s.vel.x, vzB = s.vel.z;
        for (int i = 0; i < sub; i++) {
            BlockCollider.Result cr = col.sweep(hull.at(pose), step, p.stepHeight, true);
            pose.x += cr.moved.x; pose.y += cr.moved.y; pose.z += cr.moved.z;
            lost += cr.blockedSpeed;
            if (!r.collision.startsWith("blocked")) r.collision = cr.describe();
            if (cr.hitX) { s.vel.x = 0; step.x = 0; }
            if (cr.hitZ) { s.vel.z = 0; step.z = 0; }
            if (cr.hitY && step.y < 0) { landed = true; step.y = 0; }
        }
        s.lastImpact = Math.hypot(vxB - s.vel.x, vzB - s.vel.z);   // speed taken away by blocks
        boolean wasGround = s.onGround;
        boolean on;
        if (landed) on = true;
        else if (wasGround && s.vel.y <= 0) { double dy = GroundContact.snapDown(col, hull.at(pose), p.stepHeight + 0.06); on = !Double.isNaN(dy); if (on) pose.y += dy; }
        else on = s.vel.y <= 0.5 && GroundContact.contact(col, hull.at(pose), 0.03);
        // afloat in deep enough water the hull is not "on the ground" even if it brushes the bed
        if (on && wet >= 3 && pose.y < sum / wet - p.draft + 0.05) on = false;
        s.onGround = on;
        if (on) s.vel.y = 0;
        double jump = pose.y - yBefore;
        if (wasGround && on && Math.abs(jump) > 0.04 && Math.abs(jump) <= p.stepHeight + 0.1) s.visualYOffset -= jump;
        s.landingSpeed = on && !wasGround && wet == 0 ? fall : 0;
        r.grounded = on;
        // attitude: the water plane afloat, the ground plane on land, bow up when planing, bank into turns
        double pt, rl;
        if (wet > 0 && !on) {
            double avg = sum / wet;
            double[] ang = WheeledVehicleController.planeAngles(val(surf[1], avg), val(surf[2], avg), val(surf[3], avg), val(surf[4], avg), hl, hw);
            pt = ang[0] + planing * p.planingPitch * Math.signum(vF);
            rl = ang[1] + yawRate * 0.12 * Math.min(1, Math.abs(vF) / Math.max(1, max));
        } else { double[] att = GroundContact.attitude(col, pose, hull.at(pose), p.stepHeight + 0.1, 3.0, 25); pt = att[0]; rl = att[1]; }
        bodyPitch = Pose.approach(bodyPitch, pt, 0.12); bodyRoll = Pose.approach(bodyRoll, rl, 0.12);
        pose.pitch = bodyPitch; pose.roll = bodyRoll;
        s.speed = s.vel.x * f2.x + s.vel.z * f2.z;
        s.airborne = !on && wet == 0;
        return r;
    }

    private static double val(double v, double fallback) { return Double.isNaN(v) ? fallback : v; }
}
