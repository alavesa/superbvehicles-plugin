package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.Obstacle;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.VoxelSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/**
 * Rigid-body ground vehicle (cars, trucks, tanks), no server dependency: one {@link #step} per server tick against a
 * {@link VoxelSource}. SI units throughout (1 block = 1 m): kg, N, m/s, m/s^2, rad inside, degrees at the edges.
 * <ol>
 *   <li><b>Drivetrain</b> - wheel force = engine torque x torque curve / wheel radius, limited by the traction mu N of
 *       the surface under the wheels; aerodynamic drag 1/2 rho v^2 CdA; rolling resistance Crr N; brakes limited by
 *       traction; gravity projected on the chassis: -g sin(pitch) along the nose, -g sin(bank) across it. Resistances
 *       never reverse the motion, so a braked vehicle holds on a slope exactly as long as friction can hold it.</li>
 *   <li><b>Contact vertices</b> - four corner wheels (six on long or tracked hulls) plus the two bumper vertices, kept
 *       in the chassis frame and carried to the world with the chassis matrix every sub-step. Each wheel casts down
 *       onto the block shapes ({@link VoxelCollisionAndStepSolver#support}) and pushes with a spring and damper,
 *       F = k x + c dx/dt; the sum of those forces against gravity is the heave of the body.</li>
 *   <li><b>Orientation</b> - the chassis normal is the cross product of the two diagonals between the contact points,
 *       N = (FR - RL) x (FL - RR); wheels in the air hang at full droop, so the nose drops over a crest. The target
 *       quaternion puts the chassis up axis on N at the steering heading (plus the lean from load transfer); the body
 *       follows it by slerp, the angular velocity is carried and damped, and in the air the body keeps rotating.</li>
 *   <li><b>Steps</b> - up to the free step (slabs, stairs, snow) the wheels roll up; a full block is climbed only
 *       with enough kinetic energy or drive force and costs speed; anything higher, or a ledge under a low ceiling,
 *       is a hard hit with an impulse and an angular kick.</li>
 * </ol>
 * The position is kept in doubles (floats lose centimetres a few thousand blocks out); everything relative to the
 * vehicle uses the pre-allocated JOML scratch objects below. {@link #step} allocates nothing.
 */
public final class VehicleRigidBodyPhysics {
    private static final float DT = (float) Units.DT;
    private static final int FL = 0, FR = 1, RL = 2, RR = 3;

    /** Tunables; see README "Rigid-body ground model". */
    public static final class Params {
        public boolean differential;                 // tracks: turn on the spot
        public float mass = 2000;                    // kg
        // drivetrain
        public float engineForce = 9000;             // N at the wheels at standstill (engine-force), unless engineTorque is set
        public float engineTorque = Float.NaN;       // N m at the wheels (engine-torque); force = torque / wheel radius
        public float[] torqueCurve = {1f, 1f, 0.95f, 0.85f, 0.7f, 0.5f, 0.3f};   // share of the peak, evenly spaced over 0..max-speed
        public float reverseFactor = 0.6f;
        public float maxSpeed = 25, maxReverseSpeed = 8;   // m/s: where the torque curve ends
        public float wheelRadius = 0.45f;
        // resistances
        public float airDensity = 1.225f;            // kg/m^3
        public float dragArea = Float.NaN;           // Cd x A in m^2 (drag-area); NaN = solved so that top speed = max-speed
        public float rollingResistance = 0.015f;     // Crr
        public float brakeForce = 16000, handbrakeForce = 20000, parkingBrakeForce = 12000;   // N
        // tyres
        public float tireFriction = 1.0f;            // mu on a surface of grip 1
        public float lateralGrip = 8, driftGrip = 1.2f, airGrip = 0.2f;   // 1/s
        // steering
        public float steerAngle = 32, steerFalloff = 0.85f, maxYawRate = 60, wheelbase = 2.5f, turnScale = 1, neutralTurnRate = 40;
        // body
        public float gravity = 9.81f;
        public float halfTrack = 0.8f, halfBase = 1.25f;      // wheel contact points: +-right, +-forward of the origin
        public float hullHalfWidth = 1f, hullHalfLength = 2f, hullHeight = 1.6f;
        public float hullBottom = 0f;                // chassis plane above the vehicle origin
        public float hullForward = 0f, hullRight = 0f;   // hull centre relative to the origin
        public float comRight = 0f, comHeight = 0.6f, comForward = 0f;   // centre of mass relative to the middle of the contact plane
        // suspension
        public float suspensionFrequency = 2.2f;     // Hz, natural frequency of the sprung mass
        public float suspensionDamping = 0.7f;       // damping ratio
        public float bumpTravel = 0.25f, droopTravel = 0.3f;
        public float tiltRate = 14f;                 // 1/s, slerp toward the contact plane
        public float angularDamping = 25f;           // 1/s, how fast the angular velocity follows
        public float airAngularDamping = 2f;       // 1/s, how fast a tumble dies down in the air
        public float maxTilt = 50f;                  // degrees
        // steps
        public float freeStep = 0.55f, fullStep = 1.05f;
        public float fullBlockClimbThreshold = 0.5f; // kinetic energy needed, as a share of m g h
        public float fullBlockClimbTraction = 0.3f;  // or drive force as a share of the weight
        public float uphillGravity = 0.3f;           // share of the slope force that works against a climb on anything but a full-block ledge
        public float climbLoss = 0.25f;              // share of m g h lost on top of the work against gravity
        public float wheelRiseRate = 4f;             // m/s a wheel can rise at standstill (+2 per m/s of speed)
        public float restitutionLow = 0.3f, restitutionHigh = 0.1f;
        public float waterFactor = 0.4f;
        public boolean sixContacts;
        /** Contact vertices from the model (blocks right / forward of the origin), FL FR RL RR and optionally two more; null = from halfTrack / halfBase. */
        public float[] contactRight, contactForward;

        /** From a vehicle type: every key the classic controller reads, plus the rigid-body keys. */
        public static Params of(VehicleType t) {
            WheeledVehicleController.Params o = WheeledVehicleController.Params.of(t);
            Params p = new Params();
            p.differential = o.steering == WheeledVehicleController.Steering.DIFFERENTIAL;
            p.mass = (float) o.mass; p.engineForce = (float) o.engineForce;
            p.engineTorque = (float) t.physics("engine-torque", Double.NaN);
            List<Double> curve = t.physicsList("torque-curve");
            if (curve.size() >= 2) { p.torqueCurve = new float[curve.size()]; for (int i = 0; i < curve.size(); i++) p.torqueCurve[i] = (float) Math.max(0, curve.get(i)); }
            p.maxSpeed = (float) o.maxSpeed; p.maxReverseSpeed = (float) o.maxReverseSpeed;
            p.dragArea = (float) t.physics("drag-area", Double.NaN);
            p.airDensity = (float) t.physics("air-density", 1.225);
            p.rollingResistance = (float) o.rollingResistance;
            p.brakeForce = (float) o.brakeForce; p.handbrakeForce = (float) o.handbrakeForce; p.parkingBrakeForce = (float) o.parkingBrakeForce;
            p.tireFriction = (float) t.physics("tire-friction", p.differential ? 1.2 : 1.0);
            p.lateralGrip = (float) o.lateralGrip; p.driftGrip = (float) o.driftGrip; p.airGrip = (float) o.airGrip;
            p.steerAngle = (float) o.steerAngle; p.steerFalloff = (float) o.steerFalloff; p.maxYawRate = (float) o.maxYawRate;
            p.turnScale = (float) o.turnScale; p.neutralTurnRate = (float) o.neutralTurnRate;
            p.gravity = (float) t.physics("gravity", 9.81);
            p.comForward = (float) t.physics("com-forward", 0); p.comRight = (float) t.physics("com-right", 0); p.comHeight = (float) t.physics("com-height", 0.6);
            p.suspensionFrequency = (float) Math.max(0.5, t.physics("suspension-frequency", p.differential ? 1.8 : 2.2));
            p.suspensionDamping = (float) Math.max(0.05, t.physics("suspension-damping", 0.7));
            p.bumpTravel = (float) Math.max(0.02, t.physics("suspension-travel", 0.25));
            p.droopTravel = (float) Math.max(0.02, t.physics("suspension-droop", 0.3));
            p.tiltRate = (float) Math.max(1, t.physics("tilt-rate", 14)); p.angularDamping = (float) Math.max(1, t.physics("angular-damping", 25));
            p.airAngularDamping = (float) Math.max(0, t.physics("air-angular-damping", 2.0));
            p.maxTilt = (float) o.maxTilt;
            p.freeStep = (float) o.freeStep; p.fullStep = (float) Math.max(o.stepHeight, o.freeStep);
            p.fullBlockClimbThreshold = (float) Math.max(0, t.physics("full-block-climb-threshold", 0.5));
            p.fullBlockClimbTraction = (float) Math.max(0, t.physics("full-block-climb-traction", p.differential ? 0.2 : 0.3));
            p.climbLoss = (float) Math.max(0, t.physics("climb-loss", 0.25));
            p.uphillGravity = (float) clamp01(t.physics("uphill-gravity", 0.3));
            p.restitutionLow = (float) clamp01(t.physics("bumper-restitution", 0.3)); p.restitutionHigh = (float) clamp01(t.physics("crash-restitution", 0.1));
            p.waterFactor = (float) o.waterFactor;
            return p;
        }
        private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    }

    /** One tick of input. */
    public static final class Input {
        public boolean driver, engineOn, handbrake;
        public float throttle, steer;                // -1..1
        public float powerFactor = 1, speedLimitFactor = 1;
    }

    // ------------------------------------------------------------------ state

    public final Params p;
    public final Input input = new Input();
    public final VoxelCollisionAndStepSolver solver = new VoxelCollisionAndStepSolver();
    /** World position of the vehicle origin. */
    public double x, y, z;
    /** m/s, world. */
    public final Vector3f velocity = new Vector3f();
    /** Chassis frame -> world: +X right, +Y up, -Z forward. */
    public final Quaternionf orientation = new Quaternionf();
    /** rad/s, world. */
    public final Vector3f angularVelocity = new Vector3f();
    public float yaw;                                // Minecraft yaw, degrees
    public float yawRate, yawKick;                   // degrees/s: steering, and what a hit added
    public float throttle, steer, steerAngle;
    public float speed, lateralSpeed;                // m/s along the nose (on the slope) / to the right
    public float pitch, roll;                        // degrees: nose up / right side down positive (Pose convention)
    public boolean grounded, inWater, parked, wheelspin, frozen;
    public int wheelsOnGround;
    public float driveForce, dragForce, rollingForce, brakeForce, slopeAccel;   // last tick, for the debug line
    public float impactSpeed, landingSpeed;          // m/s stopped by a hit / of the touchdown this tick
    public Obstacle lastObstacle = Obstacle.NONE;
    public boolean climbing;                         // the full-block gate is open
    public float surfaceGrip = 1, surfaceRolling = 1;
    public float wheelSpin;                          // degrees, accumulated
    public float distance;

    private int contacts = 4, gateTicks, ledgeTicks;
    private final float[] lx = new float[6], lz = new float[6];   // contact vertices in the chassis frame (right, -forward)
    private final double[] ground = new double[6];                // support height under each wheel (NaN = none)
    private final float[] comp = new float[6];                    // spring travel beyond the static sag
    private final boolean[] contact = new boolean[6];
    private final float[] restOff = new float[6];                 // height of each rest point above the origin
    private float alphaPartial, tiltStop;
    private final float[] px = new float[6], py = new float[6], pz = new float[6];   // contact points relative to the origin (world axes)
    private float kWheel, cWheel, sag, droop;
    private double prevMean = Double.NaN;
    private int prevCount;
    private float spread;
    // bumper vertices (front, rear) in the world, relative to the origin: probes and lever arms
    public final Vector3f frontBumper = new Vector3f(), rearBumper = new Vector3f();

    // scratch - never reallocated
    private final Matrix4f xf = new Matrix4f();
    private final Matrix3f basis = new Matrix3f();
    private final Quaternionf qTarget = new Quaternionf(), qStep = new Quaternionf(), qTmp = new Quaternionf();
    private final Vector3f f3 = new Vector3f(), r3 = new Vector3f(), n3 = new Vector3f();
    private final Vector3f tmp = new Vector3f(), nrm = new Vector3f(), fwd = new Vector3f(), rgt = new Vector3f();
    private final Vector3f arm = new Vector3f(), dOmega = new Vector3f(), invInertia = new Vector3f(), scratch = new Vector3f();
    private float fx, fz, rx, rz;                     // flat heading and right

    public VehicleRigidBodyPhysics(Params p) {
        this.p = p;
        java.util.Arrays.fill(ground, Double.NaN);
        configure();
    }

    /** Recompute everything derived from the parameters (call after changing the geometry). */
    public void configure() {
        contacts = p.sixContacts ? 6 : 4;
        lx[FL] = -p.halfTrack; lz[FL] = -p.halfBase; lx[FR] = p.halfTrack; lz[FR] = -p.halfBase;
        lx[RL] = -p.halfTrack; lz[RL] = p.halfBase; lx[RR] = p.halfTrack; lz[RR] = p.halfBase;
        lx[4] = -p.halfTrack; lz[4] = 0; lx[5] = p.halfTrack; lz[5] = 0;
        if (p.contactRight != null && p.contactForward != null && p.contactRight.length >= 4 && p.contactForward.length == p.contactRight.length) {
            contacts = Math.min(6, p.contactRight.length) == 5 ? 4 : Math.min(6, p.contactRight.length);
            for (int i = 0; i < contacts; i++) { lx[i] = p.contactRight[i]; lz[i] = -p.contactForward[i]; }
        }
        float w = (float) (2 * Math.PI * p.suspensionFrequency);
        kWheel = p.mass * w * w / contacts;
        cWheel = 2 * p.suspensionDamping * p.mass * w / contacts;
        sag = p.gravity / (w * w);
        droop = Math.max(p.droopTravel, sag + 0.01f);
        float W = 2 * p.hullHalfWidth, L = 2 * p.hullHalfLength, H = p.hullHeight;
        invInertia.set(12f / (p.mass * (H * H + L * L)), 12f / (p.mass * (W * W + L * L)), 12f / (p.mass * (W * W + H * H)));
        // with an axle in the air the body can only rotate as fast as gravity turns it about the other axle
        alphaPartial = 1.2f * p.gravity * p.halfBase / ((H * H + L * L) / 12f + p.halfBase * p.halfBase);
        tiltStop = (float) Math.cos(Math.toRadians(Math.min(80, p.maxTilt + 5)));
        if (Float.isNaN(p.dragArea)) {
            // top speed = max-speed: thrust at the end of the torque curve = drag + rolling resistance there
            float thrust = peakForce() * p.torqueCurve[p.torqueCurve.length - 1], roll = p.rollingResistance * p.mass * p.gravity;
            p.dragArea = Math.max(0.3f, 2 * (thrust - roll) / (p.airDensity * p.maxSpeed * p.maxSpeed));
        }
    }

    /** Peak force at the wheels (N). */
    public float peakForce() { return Float.isNaN(p.engineTorque) ? p.engineForce : p.engineTorque / Math.max(0.05f, p.wheelRadius); }

    /** Wheel force share at speed fraction u (speed / max speed): the torque curve, tapering to nothing past its end. */
    float torqueAt(float u) {
        float[] c = p.torqueCurve;
        if (u <= 0) return c[0];
        if (u >= 1) return c[c.length - 1] * (float) Math.exp(-10.0 * (u - 1));
        float f = u * (c.length - 1);
        int i = (int) f;
        return c[i] + (c[i + 1] - c[i]) * (f - i);
    }

    /** Put the body somewhere (spawn, teleport, a pose restored by the sanity guard). */
    public void reset(double x, double y, double z, float yaw) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw;
        orientation.rotationY((float) Math.toRadians(-(yaw + 180.0)));
        velocity.zero(); angularVelocity.zero(); yawKick = 0; yawRate = 0;
        java.util.Arrays.fill(ground, Double.NaN);
        java.util.Arrays.fill(contact, false);
        prevMean = Double.NaN; prevCount = 0; gateTicks = 0; ledgeTicks = 0; spread = 0;
        wheelsOnGround = 0; grounded = false;
    }

    /** At rest: on its wheels and not moving, turning or settling. */
    public boolean atRest() {
        return grounded && velocity.lengthSquared() < 1e-6f && angularVelocity.lengthSquared() < 1e-5f && Math.abs(yawKick) < 0.01f;
    }

    /** Spring travel of a wheel beyond its static sag (blocks; negative = extended), for wheel/suspension bones. */
    public float compression(int wheel) { return comp[wheel]; }
    public boolean wheelContact(int wheel) { return contact[wheel]; }
    public int contactCount() { return contacts; }

    // ------------------------------------------------------------------ step

    public void step(VoxelSource src) {
        final Input in = input;
        final float dt = DT, g = p.gravity, m = p.mass;
        impactSpeed = 0; landingSpeed = 0; lastObstacle = Obstacle.NONE; parked = false; wheelspin = false; frozen = false;

        // 1. input: throttle and steering follow the keys; released steering returns to centre
        boolean powered = in.driver && in.engineOn;
        float thrTarget = powered ? clamp(in.throttle, -1, 1) : 0, steerTarget = in.driver ? clamp(in.steer, -1, 1) : 0;
        throttle += (thrTarget - throttle) * 0.35f;
        steer += (steerTarget - steer) * (steerTarget == 0 ? 0.25f : 0.35f);
        if (thrTarget == 0 && Math.abs(throttle) < 0.01f) throttle = 0;
        if (steerTarget == 0 && Math.abs(steer) < 0.01f) steer = 0;

        // 2. the world around the hull, once
        heading();
        axes();
        float hspeed = (float) Math.hypot(velocity.x, velocity.z);
        double bottom = y + p.hullBottom;
        gatherAround(src, hspeed);
        if (!solver.complete()) { velocity.zero(); angularVelocity.zero(); yawKick = 0; speed = 0; lateralSpeed = 0; frozen = true; return; }
        inWater = src.water(VoxelCollisionAndStepSolver.floor(x), VoxelCollisionAndStepSolver.floor(bottom + 0.2), VoxelCollisionAndStepSolver.floor(z));
        boolean wasAirborne = wheelsOnGround == 0;
        boolean onGround = wheelsOnGround > 0;

        // 3. forces along the nose
        float sinP = f3.y, cosP = onGround ? (float) Math.sqrt(Math.max(0.25f, 1 - sinP * sinP)) : 1f;   // in the air the speed is simply horizontal
        float water = inWater ? p.waterFactor : 1;
        float mu = surfaceGrip * p.tireFriction;
        float load = onGround ? m * g * Math.max(0, n3.y) * wheelsOnGround / contacts : 0;      // normal force N
        float traction = mu * load;
        float vF = (velocity.x * fx + velocity.z * fz) / cosP, vL = velocity.x * rx + velocity.z * rz;
        float limit = clamp(in.speedLimitFactor, 0.05f, 1f);
        float vMax = Math.max(0.1f, p.maxSpeed * water * limit), vRev = Math.max(0.1f, p.maxReverseSpeed * water * limit);
        float drive = 0, brake = 0;
        if (onGround) {
            boolean opposing = (throttle > 0.05f && vF < -0.3f) || (throttle < -0.05f && vF > 0.3f);
            if (opposing) brake = p.brakeForce * Math.abs(throttle);
            else if (throttle > 0) drive = peakForce() * torqueAt(vF / vMax) * throttle * in.powerFactor * water;
            else if (throttle < 0) drive = peakForce() * p.reverseFactor * torqueAt(-vF / vRev) * throttle * in.powerFactor * water;
            if (in.handbrake && in.driver) brake += p.handbrakeForce;
            if (!in.driver || (Math.abs(throttle) < 0.05f && Math.abs(vF) < 1.5f)) { brake += p.parkingBrakeForce; parked = true; }
            if (Math.abs(drive) > traction) { drive = Math.signum(drive) * traction; wheelspin = true; }
            if (brake > traction) brake = traction;
        }
        float sp = velocity.length();
        float drag = 0.5f * p.airDensity * p.dragArea * sp * sp * (inWater ? 25f : 1f);
        float rolling = p.rollingResistance * surfaceRolling * load;
        float aSlope = onGround ? -g * sinP : 0;                                             // gravity projected on the nose
        // going up a ramp of slabs or stairs is a road, not a wall: only a full-block ledge is fought at full weight
        float heading = Math.abs(vF) > 0.2f ? Math.signum(vF) : Math.signum(throttle);
        if (aSlope * heading < 0 && ledgeTicks == 0) aSlope *= p.uphillGravity;
        driveForce = drive; dragForce = drag; rollingForce = rolling; brakeForce = brake; slopeAccel = aSlope;
        float v1 = vF + (drive / m + aSlope) * dt;
        float resist = ((brake + rolling) / m + (sp > 1e-3f ? drag / m * Math.abs(vF) / sp : 0)) * dt;
        v1 = Math.signum(v1) * Math.max(0, Math.abs(v1) - resist);                         // resistance stops, never reverses
        v1 = clamp(v1, -2 * p.maxSpeed, 2 * p.maxSpeed);
        if (onGround && Math.abs(v1) < 0.02f && Math.abs(throttle) < 0.05f) v1 = 0;

        // 4. across the nose: gravity on a banked surface against the tyres' side force (at most mu g)
        if (onGround) {
            vL += -g * r3.y * dt;
            float cap = mu * g * Math.max(0, n3.y) * dt * (in.handbrake && in.driver ? 0.4f : 1f);
            float k = in.handbrake && in.driver ? p.driftGrip : p.lateralGrip * (inWater ? 0.4f : 1f);
            float dv = vL * (1 - (float) Math.exp(-k * dt));
            if (Math.abs(vL) <= cap) vL = 0;
            else vL -= Math.abs(dv) > cap ? Math.signum(dv) * cap : dv;
        } else vL *= (float) Math.exp(-p.airGrip * dt);

        // 5. heading: front wheels steer (the angle shrinks with speed) or the tracks turn against each other;
        //    the tyres cannot turn the car harder than mu g allows
        float rate = 0;
        if (!p.differential) {
            float lim = p.steerAngle * (1 - p.steerFalloff * Math.min(1, Math.abs(vF) / Math.max(1, p.maxSpeed)));
            steerAngle += (steer * lim - steerAngle) * 0.3f;
            if (onGround && Math.abs(v1) > 0.05f) rate = (float) Math.toDegrees(v1 / Math.max(0.5f, p.wheelbase) * Math.tan(Math.toRadians(steerAngle))) * p.turnScale;
            if (in.handbrake && in.driver && Math.abs(v1) > 4) rate *= 1.35f;
        } else {
            steerAngle = 0;
            if (onGround && powered && Math.abs(steer) > 0.01f) {
                float k = Math.abs(v1) < 0.5f ? 1f : clamp(1.2f - Math.abs(v1) / Math.max(1, p.maxSpeed), 0.35f, 1f);
                rate = steer * p.neutralTurnRate * k * (v1 < -0.5f ? -1 : 1);
            }
        }
        if (onGround && Math.abs(v1) > 1f) { float gripRate = (float) Math.toDegrees(mu * g / Math.abs(v1)) * 1.25f; if (Math.abs(rate) > gripRate) rate = Math.signum(rate) * gripRate; }
        if (Math.abs(rate) > p.maxYawRate) rate = Math.signum(rate) * p.maxYawRate;
        yawRate = rate;
        yawKick *= (float) Math.exp(-(onGround ? 6f : 0.5f) * dt);
        if (Math.abs(yawKick) < 0.01f) yawKick = 0;

        // 6. the full-block gate: a ledge ahead is climbable with enough energy or drive force, unless a ceiling is in the way
        float dir = Math.abs(v1) > 0.2f ? Math.signum(v1) : Math.signum(throttle);
        float slopeF = f3.y / Math.max(0.2f, (float) Math.hypot(f3.x, f3.z)) , slopeR = r3.y / Math.max(0.2f, (float) Math.hypot(r3.x, r3.z));
        double hx = x + fx * p.hullForward + rx * p.hullRight, hz = z + fz * p.hullForward + rz * p.hullRight;
        if (dir != 0) {
            double look = dir * (Math.abs(v1) * cosP * dt * 2 + p.fullStep + 0.1);
            solver.probeAhead(hx, hz, hx + fx * look, hz + fz * look, fx, fz, rx, rz, p.hullHalfWidth, p.hullHalfLength, bottom, slopeF, slopeR, p.hullHeight, p.freeStep, p.fullStep);
            boolean pushing = drive * dir > 0;
            if (solver.aheadRise() > 0 && !solver.aheadCeiling()
                && VoxelCollisionAndStepSolver.canClimbFullBlock(m, Math.abs(v1), pushing ? Math.abs(drive) : 0, solver.aheadRise(), g, p.fullBlockClimbThreshold, p.fullBlockClimbTraction)) { gateTicks = 12; ledgeTicks = 24; }
        }
        if (ledgeTicks > 0) ledgeTicks--;
        boolean ledge = ledgeTicks > 0;
        if (spread > p.freeStep * 0.9f && gateTicks < 4) gateTicks = 4;      // astride a ledge (up or down): finish it
        climbing = gateTicks > 0;
        if (gateTicks > 0) gateTicks--;
        float climbLimit = climbing ? p.fullStep : p.freeStep;
        float profile = Math.max(p.wheelRadius, climbLimit + 0.05f);

        // 7. turn (refused when the turned hull would be inside something), then the velocity follows the nose
        float dYaw = (yawRate + yawKick) * dt;
        if (dYaw != 0) {
            float old = yaw;
            yaw = wrap(yaw + dYaw);
            heading();
            double tx = x + fx * p.hullForward + rx * p.hullRight, tz = z + fz * p.hullForward + rz * p.hullRight;
            if (solver.blocked(tx, tz, fx, fz, rx, rz, p.hullHalfWidth, p.hullHalfLength, bottom, slopeF, slopeR, climbLimit, p.hullHeight, p.freeStep, p.fullStep)
                && !solver.blocked(hx, hz, -(float) Math.sin(Math.toRadians(old)), (float) Math.cos(Math.toRadians(old)), -(float) Math.cos(Math.toRadians(old)), -(float) Math.sin(Math.toRadians(old)), p.hullHalfWidth, p.hullHalfLength, bottom, slopeF, slopeR, climbLimit, p.hullHeight, p.freeStep, p.fullStep)) {
                yaw = old; yawKick = 0; yawRate = 0; heading();
            } else orientation.rotateLocalY((float) Math.toRadians(-dYaw));
        }
        float flat = v1 * cosP;
        velocity.x = fx * flat + rx * vL;
        velocity.z = fz * flat + rz * vL;

        // 8. integrate in sub-steps short enough for the springs and for thin obstacles
        float vmaxAxis = Math.max((float) Math.hypot(velocity.x, velocity.z), Math.abs(velocity.y));
        int n = (int) Math.ceil(vmaxAxis * dt / 0.25f);
        n = n < 4 ? 4 : n > 12 ? 12 : n;
        float h = dt / n;
        float riseRate = p.wheelRiseRate + 2 * Math.abs(v1);
        // lean from load transfer: m a h_com over the axle / track, taken up by the springs
        float w2 = (2 * (float) Math.PI * p.suspensionFrequency); w2 *= w2;
        float L = 2 * p.halfBase, W = 2 * p.halfTrack;
        float aLong = onGround ? (v1 - vF) / dt - aSlope : 0, aLat = onGround ? v1 * (float) Math.toRadians(yawRate) : 0;
        float pitchLean = (float) Math.atan2(2 * (2 * aLong * p.comHeight / (L * w2)) - 2 * (2 * g * p.comForward / (L * w2)), L);
        float rollLean = (float) Math.atan2(2 * (2 * g * p.comRight / (W * w2)) - 2 * (2 * aLat * p.comHeight / (W * w2)), W);   // the body rolls out of the turn
        int count = wheelsOnGround;
        for (int s = 0; s < n; s++) {
            axes();
            slopeF = f3.y / Math.max(0.2f, (float) Math.hypot(f3.x, f3.z)); slopeR = r3.y / Math.max(0.2f, (float) Math.hypot(r3.x, r3.z));
            double plane = y + p.hullBottom;

            // a. horizontal move, axis by axis: block faces are axis-aligned, so a blocked axis IS the contact normal.
            //    A hull that is already inside something (a block was placed into it) is never held there: it may drive out.
            boolean embedded = hullBlocked(x, z, plane, slopeF, slopeR, climbLimit);
            if (velocity.x != 0) {
                double nx = x + velocity.x * h;
                if (!embedded && hullBlocked(nx, z, plane, slopeF, slopeR, climbLimit)) hit(velocity.x > 0 ? -1 : 1, 0); else x = nx;
            }
            if (velocity.z != 0) {
                double nz = z + velocity.z * h;
                if (!embedded && hullBlocked(x, nz, plane, slopeF, slopeR, climbLimit)) hit(0, velocity.z > 0 ? -1 : 1); else z = nz;
            }

            // b. contact vertices to the world, a ray down from each, spring and damper
            xf.rotation(orientation);
            float fsum = 0, over = 0, gripSum = 0, rollSum = 0, work = 0, deepest = -1;
            boolean full = true;
            double sum = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int c = 0;
            for (int i = 0; i < contacts; i++) {
                xf.transformPosition(lx[i], p.hullBottom, lz[i], tmp);
                px[i] = tmp.x; pz[i] = tmp.z; restOff[i] = tmp.y;
                double rest = y + tmp.y, hang = rest - droop;
                double ref = Double.isNaN(ground[i]) ? hang : Math.max(ground[i], hang);
                double raw = solver.support(x + tmp.x, z + tmp.z, ref, climbLimit, profile, ref - hang + 0.05);
                if (Double.isNaN(raw)) { ground[i] = Double.NaN; comp[i] = -droop; contact[i] = false; py[i] = tmp.y - droop; if (i < 4) full = false; continue; }
                if (!Double.isNaN(ground[i]) && raw > ground[i] + riseRate * h) raw = ground[i] + riseRate * h;   // a wheel climbs, it does not teleport
                float cmp = (float) (raw - rest);
                boolean touching = cmp > -sag;
                if (touching) {
                    float f = kWheel * (sag + cmp) + cWheel * (contact[i] ? (cmp - comp[i]) / h : Math.max(0, -velocity.y));
                    float fmax = 3 * m * g / contacts;
                    f = f < 0 ? 0 : f > fmax ? fmax : f;
                    // a wheel forced up a ledge: what the spring takes in beyond the static load is paid by the forward motion
                    if (ledge && !Double.isNaN(ground[i]) && raw > ground[i] && f > m * g / contacts) work += 0.5f * (f - m * g / contacts) * (float) (raw - ground[i]);
                    fsum += f; c++;
                    VoxelCollisionAndStepSolver.Surface sf = solver.supportSurface();
                    gripSum += sf.grip; rollSum += sf.rolling;
                    sum += raw; if (raw < lo) lo = raw; if (raw > hi) hi = raw;
                    if (cmp - p.bumpTravel > over) over = cmp - p.bumpTravel;
                    if (cmp > deepest) deepest = cmp;
                }
                ground[i] = raw; comp[i] = cmp; contact[i] = touching; py[i] = (float) (raw - y);
            }
            if (c > 0 && count == 0 && wasAirborne && landingSpeed == 0) landingSpeed = Math.max(0, -velocity.y);
            count = c;
            if (c > 0) { surfaceGrip = gripSum / c; surfaceRolling = rollSum / c; spread = (float) (hi - lo); } else spread = 0;

            // c. bump stops: the springs are solid past their travel, the body is carried up
            if (over > 0) {
                y += over;
                for (int i = 0; i < contacts; i++) if (contact[i]) { comp[i] -= over; py[i] -= over; }
                if (velocity.y < 0) velocity.y = 0;
            }

            // d. heave: spring forces against gravity
            float ay = fsum / m - g;
            if (c == contacts && Math.abs(velocity.y) < 0.01f && Math.abs(ay) < 0.05f) velocity.y = 0;
            else if (deepest >= p.bumpTravel - 0.01f && ay < 0 && velocity.y <= 0) velocity.y = 0;   // sitting on a bump stop: carried, not falling
            else {
                velocity.y += ay * h;
                if (inWater) velocity.y *= 1 - 2 * h;
                if (velocity.y < -60) velocity.y = -60;
                double ny = y + velocity.y * h;
                if (velocity.y > 0.5f && hullBlocked(x, z, ny + p.hullBottom, slopeF, slopeR, climbLimit)) velocity.y = 0; else y = ny;
            }

            // e. a full-block climb costs speed: the spring work above, and a share of m g h on top of the slope force
            if (work > 0) {
                float v2 = velocity.x * velocity.x + velocity.z * velocity.z;
                if (v2 > 1e-6f) { float k = (float) Math.sqrt(Math.max(0, v2 - 2 * work / m) / v2); velocity.x *= k; velocity.z *= k; }
            }
            if (c > 0 && c == prevCount && !Double.isNaN(prevMean)) {
                double rise = sum / c - prevMean;
                if (rise > 0 && ledge) {
                    float v2 = velocity.x * velocity.x + velocity.z * velocity.z, lost = 2 * p.climbLoss * g * (float) rise;
                    if (v2 > 1e-6f) { float k = (float) Math.sqrt(Math.max(0, v2 - lost) / v2); velocity.x *= k; velocity.z *= k; }
                }
            }
            prevMean = c > 0 ? sum / c : Double.NaN; prevCount = c;

            // f. orientation: plane through the contact points -> target quaternion -> slerp, damped angular velocity
            if (c > 0) {
                float ax = px[FR] - px[RL], ay2 = py[FR] - py[RL], az = pz[FR] - pz[RL];
                float bx = px[FL] - px[RR], by = py[FL] - py[RR], bz = pz[FL] - pz[RR];
                nrm.set(ay2 * bz - az * by, az * bx - ax * bz, ax * by - ay2 * bx);
                if (nrm.y < 0) nrm.negate();
                if (nrm.lengthSquared() < 1e-8f) nrm.set(0, 1, 0); else nrm.normalize();
                float minUp = (float) Math.cos(Math.toRadians(p.maxTilt));
                if (nrm.y < minUp) {                                  // never past the tilt limit
                    float hl = (float) Math.hypot(nrm.x, nrm.z), want = (float) Math.sqrt(1 - minUp * minUp);
                    nrm.x *= want / hl; nrm.z *= want / hl; nrm.y = minUp;
                }
                float d = fx * nrm.x + fz * nrm.z;
                fwd.set(fx - nrm.x * d, -nrm.y * d, fz - nrm.z * d).normalize();
                fwd.cross(nrm, rgt);
                basis.set(rgt.x, rgt.y, rgt.z, nrm.x, nrm.y, nrm.z, -fwd.x, -fwd.y, -fwd.z);
                qTarget.setFromNormalized(basis).rotateX(pitchLean).rotateZ(-rollLean);
                orientation.slerp(qTarget, 1 - (float) Math.exp(-p.tiltRate * h), qStep);
                orientation.conjugate(qTmp);
                qStep.mul(qTmp, qTmp);                               // the rotation of this sub-step
                float sgn = qTmp.w < 0 ? -2f / h : 2f / h;
                float blend = 1 - (float) Math.exp(-p.angularDamping * h);
                tmp.set((qTmp.x * sgn - angularVelocity.x) * blend, (qTmp.y * sgn - angularVelocity.y) * blend, (qTmp.z * sgn - angularVelocity.z) * blend);
                if (!full) { float dl = tmp.length(), mx = alphaPartial * h; if (dl > mx) tmp.mul(mx / dl); }   // an axle hangs: gravity sets the pace
                angularVelocity.add(tmp);
            } else angularVelocity.mul((float) Math.exp(-p.airAngularDamping * h));  // in the air the body keeps turning
            float wl = angularVelocity.length();
            if (wl > 1e-6f) {
                if (wl > 12f) { angularVelocity.mul(12f / wl); wl = 12f; }
                qTmp.setAngleAxis(wl * h, angularVelocity.x / wl, angularVelocity.y / wl, angularVelocity.z / wl);
                qStep.set(orientation);
                orientation.premul(qTmp).normalize();
                // never onto its side or roof: a turn that would take the body further past the tilt limit is not made
                orientation.transform(0, 1, 0, tmp);
                if (tmp.y < tiltStop && tmp.y < n3.y) { orientation.set(qStep); angularVelocity.zero(); }
                // the body turns about the wheels that carry it, not about its middle: keep their rest points where they were
                if (c > 0) {
                    xf.rotation(orientation);
                    float shift = 0;
                    for (int i = 0; i < contacts; i++) if (contact[i]) { xf.transformPosition(lx[i], p.hullBottom, lz[i], tmp); shift += tmp.y - restOff[i]; }
                    y -= shift / c;
                }
            }
        }

        // 9. inside a block with no wheel on anything (spawned into the ground, a block placed into it): back on top
        boolean anySupport = false;
        for (int i = 0; i < contacts; i++) anySupport |= !Double.isNaN(ground[i]);
        if (!anySupport && velocity.y <= 0) {
            double r = solver.support(x, z, y + p.hullBottom + p.bumpTravel + 0.05, p.hullHeight * 0.5 + 1, 0.3, 0);
            if (!Double.isNaN(r)) { y = r - p.hullBottom; velocity.y = 0; }
        }

        // 10. what the rest of the plugin reads
        wheelsOnGround = count; grounded = count > 0;
        if (grounded && thrTarget == 0 && steerTarget == 0 && velocity.x * velocity.x + velocity.z * velocity.z < 4e-4f && Math.abs(aSlope) * m <= brake + rolling) { velocity.x = 0; velocity.z = 0; }
        if (grounded && velocity.lengthSquared() < 1e-6f && angularVelocity.lengthSquared() < 1e-4f) angularVelocity.zero();
        axes();
        heading();
        float cp = grounded ? (float) Math.sqrt(Math.max(0.25f, 1 - f3.y * f3.y)) : 1f;
        speed = (velocity.x * fx + velocity.z * fz) / cp;
        lateralSpeed = velocity.x * rx + velocity.z * rz;
        pitch = (float) Math.toDegrees(Math.asin(clamp(f3.y, -1, 1)));
        roll = (float) -Math.toDegrees(Math.atan2(r3.y, n3.y));
        wheelSpin = (wheelSpin + (float) Math.toDegrees(speed * dt / Math.max(0.1f, p.wheelRadius))) % 360f;
        distance += (float) Math.hypot(velocity.x, velocity.z) * dt;
        xf.rotation(orientation);
        xf.transformPosition(p.hullRight, p.hullBottom + 0.4f, -(p.hullForward + p.hullHalfLength), frontBumper);
        xf.transformPosition(p.hullRight, p.hullBottom + 0.4f, -(p.hullForward - p.hullHalfLength), rearBumper);
    }

    /**
     * For a sleeping vehicle: has the ground under a wheel changed, or has something been put into the hull, since the
     * last step? One box query, no motion.
     */
    public boolean supportChanged(VoxelSource src) {
        heading();
        axes();
        double bottom = y + p.hullBottom;
        gatherAround(src, 0);
        if (!solver.complete()) return false;
        float climbLimit = climbing ? p.fullStep : p.freeStep, profile = Math.max(p.wheelRadius, climbLimit + 0.05f);
        float slopeF = f3.y / Math.max(0.2f, (float) Math.hypot(f3.x, f3.z)), slopeR = r3.y / Math.max(0.2f, (float) Math.hypot(r3.x, r3.z));
        if (hullBlocked(x, z, bottom, slopeF, slopeR, climbLimit)) return true;
        xf.rotation(orientation);
        for (int i = 0; i < contacts; i++) {
            xf.transformPosition(lx[i], p.hullBottom, lz[i], tmp);
            double hang = y + tmp.y - droop, ref = Double.isNaN(ground[i]) ? hang : Math.max(ground[i], hang);
            double raw = solver.support(x + tmp.x, z + tmp.z, ref, climbLimit, profile, ref - hang + 0.05);
            if (Double.isNaN(raw) != Double.isNaN(ground[i])) return true;
            if (!Double.isNaN(raw) && Math.abs(raw - ground[i]) > 0.01) return true;
        }
        return false;
    }

    /**
     * The blocks this tick can touch: the turned hull's bounding rectangle plus the wheel profile and the look-ahead,
     * from just under the hanging wheels to just over the roof of a body that climbed a full step (both widened by
     * the tilt and the vertical speed). Kept tight: reading blocks is what a tick costs most.
     */
    private void gatherAround(VoxelSource src, float hspeed) {
        double margin = 1.3 + 2 * hspeed * DT;
        double ex = Math.abs(rx) * p.hullHalfWidth + Math.abs(fx) * p.hullHalfLength + margin, ez = Math.abs(rz) * p.hullHalfWidth + Math.abs(fz) * p.hullHalfLength + margin;
        double cx = x + fx * p.hullForward + rx * p.hullRight, cz = z + fz * p.hullForward + rz * p.hullRight;
        double tilt = Math.abs(f3.y) * p.hullHalfLength + Math.abs(r3.y) * p.hullHalfWidth, bottom = y + p.hullBottom;
        solver.gather(src, cx - ex, bottom - tilt - droop - 0.6 - Math.max(0, -velocity.y) * DT, cz - ez,
            cx + ex, bottom + tilt + p.hullHeight + p.fullStep + 0.3 + Math.max(0, velocity.y) * DT, cz + ez);
    }

    private boolean hullBlocked(double ox, double oz, double plane, float slopeF, float slopeR, float climbLimit) {
        return solver.blocked(ox + fx * p.hullForward + rx * p.hullRight, oz + fz * p.hullForward + rz * p.hullRight, fx, fz, rx, rz,
            p.hullHalfWidth, p.hullHalfLength, plane, slopeF, slopeR, climbLimit, p.hullHeight, p.freeStep, p.fullStep);
    }

    /** The hull met a block face with normal (nx, 0, nz): impulse, rebound, and the angular kick r x J. */
    private void hit(int nx, int nz) {
        float closing = Math.abs(nx != 0 ? velocity.x : velocity.z);
        float e = VoxelCollisionAndStepSolver.restitution(closing, p.restitutionLow, p.restitutionHigh);
        // lever arm: contact point relative to the centre of mass
        xf.rotation(orientation).transformPosition(p.comRight, p.hullBottom + p.comHeight, -p.comForward, tmp);
        arm.set((float) (solver.hitX() - x) - tmp.x, (float) (solver.hitY() - y) - tmp.y, (float) (solver.hitZ() - z) - tmp.z);
        dOmega.zero();
        float stopped = VoxelCollisionAndStepSolver.impact(velocity, nx, 0, nz, e, p.mass, arm, invInertia, orientation, dOmega, scratch);
        if (stopped > impactSpeed) { impactSpeed = stopped; lastObstacle = solver.hitKind(); }
        if (stopped < 0.4f) return;
        yawKick = clamp(yawKick - (float) Math.toDegrees(dOmega.y), -240, 240);        // Minecraft yaw runs against +Y
        float tilt = (float) Math.hypot(dOmega.x, dOmega.z);
        if (tilt > 1e-6f) { float k = Math.min(1f, 2.5f / tilt); angularVelocity.add(dOmega.x * k, 0, dOmega.z * k); }
    }

    private void heading() {
        double rad = Math.toRadians(yaw);
        fx = (float) -Math.sin(rad); fz = (float) Math.cos(rad);
        rx = -fz; rz = fx;
    }

    private void axes() {
        orientation.transform(0, 0, -1, f3);
        orientation.transform(1, 0, 0, r3);
        orientation.transform(0, 1, 0, n3);
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
    private static float wrap(float deg) { deg %= 360; if (deg > 180) deg -= 360; if (deg < -180) deg += 360; return deg; }

    /** One line for /sbv debug physics. */
    public String debugLine() {
        return String.format(Locale.ROOT,
            "rigid | thr %.2f steer %.2f | v fwd %.2f lat %.2f m/s (%.1f km/h) vy %.2f | F drive %.0f drag %.0f roll %.0f brake %.0f N, slope %.2f m/s2 | wheels %d/%d grip %.2f%s | pitch %.1f roll %.1f yaw %.1f deg/s | %s%s%s",
            throttle, steer, speed, lateralSpeed, Units.kmh(speed), velocity.y, driveForce, dragForce, rollingForce, brakeForce, slopeAccel,
            wheelsOnGround, contacts, surfaceGrip, wheelspin ? " SPIN" : "", pitch, roll, yawRate,
            frozen ? "waiting for chunks" : grounded ? (parked ? "parked" : "driving") : "airborne", climbing ? " | climbing a block" : "",
            lastObstacle == Obstacle.NONE ? "" : String.format(Locale.ROOT, " | hit %s at %.1f m/s", lastObstacle, impactSpeed));
    }
}
