package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.joml.Vector3d;

import java.util.List;

/**
 * Cars, trucks, armoured cars (and, through {@link TrackedPhysics}, tanks): the server-side adapter around the
 * pure {@link WheeledVehicleController}. It turns the driver's cached input into controller input, runs the
 * engine key, and applies the side effects the controller does not know about: crash and fall damage, RPM/gear for
 * the sound and HUD, dust and splashes. YAML keys: see {@link WheeledVehicleController.Params#of}.
 */
public class WheeledPhysics extends VehiclePhysics {
    protected final WheeledVehicleController controller;
    protected WheeledVehicleController.Report last = new WheeledVehicleController.Report();
    /** The rigid-body model (config physics.ground-model: rigid, the default); null = the classic controller drives. */
    protected final VehicleRigidBodyPhysics rigid;
    private double placedX, placedY, placedZ;
    private float placedYaw;
    private boolean placed;

    public WheeledPhysics(Vehicle v) {
        super(v);
        controller = new WheeledVehicleController(WheeledVehicleController.Params.of(t), v::hullAt);
        s.idleRpm = t.physics("idle-rpm", 800); s.maxRpm = t.physics("max-rpm", 6000);
        String model = t.physicsSection.getString("ground-model", plugin.getConfig().getString("physics.ground-model", "rigid"));
        rigid = "classic".equalsIgnoreCase(model) ? null : new VehicleRigidBodyPhysics(VehicleRigidBodyPhysics.Params.of(t));
    }

    public VehicleRigidBodyPhysics rigid() { return rigid; }

    public WheeledVehicleController controller() { return controller; }
    public WheeledVehicleController.Report lastReport() { return last; }

    /** Wheelbase and wheel radius from the model's wheel locators (front-most to rear-most), else from the hull. */
    @Override public void rigBound() {
        WheeledVehicleController.Params p = controller.params();
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (double[] w : v.wheelLocators.values()) { minZ = Math.min(minZ, w[2]); maxZ = Math.max(maxZ, w[2]); minY = Math.min(minY, w[1]); }
        if (v.wheelLocators.size() >= 2 && maxZ - minZ > 4) p.wheelbase = (maxZ - minZ) / 16.0 * t.scale;
        else p.wheelbase = Math.max(1.0, v.hitboxHalfPx[2] / 16.0 * t.scale * 1.6);
        if (!v.wheelLocators.isEmpty() && minY > 0.5) p.wheelRadius = Math.max(0.1, minY / 16.0 * t.scale);
        p.wheelbase = t.physics("wheelbase", p.wheelbase);
        p.wheelRadius = t.physics("wheel-radius", p.wheelRadius);
        if (rigid != null) rigGeometry(p);
    }

    /**
     * Hull box and wheel contact points of the rigid body, in its own frame (right, up, forward). The model frame is
     * turned by (180 - model-yaw) about Y to get there, so a model drawn facing any way gets the right footprint.
     */
    private void rigGeometry(WheeledVehicleController.Params p) {
        VehicleRigidBodyPhysics.Params r = rigid.p;
        double a = Math.toRadians(180 - t.modelYaw), ca = Math.cos(a), sa = Math.sin(a), k = t.scale / 16.0;
        double[] h = v.hitboxHalfPx, c = v.hitboxCenterPx;
        r.hullHalfWidth = (float) ((Math.abs(ca) * h[0] + Math.abs(sa) * h[2]) * k);
        r.hullHalfLength = (float) ((Math.abs(sa) * h[0] + Math.abs(ca) * h[2]) * k);
        r.hullHeight = (float) (2 * h[1] * k);
        r.hullBottom = (float) ((c[1] - h[1]) * k);
        r.hullRight = (float) ((c[0] * ca + c[2] * sa) * k);
        r.hullForward = (float) (-(-c[0] * sa + c[2] * ca) * k);
        double track = 0, base = 0;
        for (double[] w : v.wheelLocators.values()) { track = Math.max(track, Math.abs(w[0] * ca + w[2] * sa) * k); base = Math.max(base, Math.abs(-w[0] * sa + w[2] * ca) * k); }
        r.halfTrack = (float) t.physics("half-track", track > 0.2 ? Math.min(track, r.hullHalfWidth) : r.hullHalfWidth * 0.85);
        r.halfBase = (float) t.physics("half-base", base > 0.3 ? Math.min(base, r.hullHalfLength) : r.hullHalfLength * 0.8);
        r.wheelbase = (float) p.wheelbase; r.wheelRadius = (float) p.wheelRadius;
        r.sixContacts = t.physicsFlag("six-contacts", r.differential || r.halfBase > 1.8f);
        r.dragArea = (float) t.physics("drag-area", Double.NaN);
        rigid.configure();
        placed = false;
    }

    protected String controlClass() { return "wheeled"; }

    @Override public void tick(Controls c, BlockCollider col) {
        WheeledVehicleController.Params p = controller.params();
        engineControl(c, controlClass(), p.autoStartEngine);
        if (rigid != null) { tickRigid(c, col, p); return; }
        WheeledVehicleController.Input in = new WheeledVehicleController.Input();
        in.driver = c.present;
        in.throttle = c.throttle();
        in.steer = c.steer();
        in.handbrake = c.present && c.key(plugin.getConfig().getString("controls." + controlClass() + ".handbrake", "jump"));
        in.engineOn = s.engineOn;
        in.outOfFuel = v.outOfFuel();
        in.powerFactor = v.powerFactor();
        in.speedLimitFactor = chunkSpeedFactor(col);
        last = controller.step(v.pose, s, in, col);
        afterMove(last.lostSpeed);
        if (s.landingSpeed > 0) landing(s.landingSpeed);
        engineSound(p);
        double a = Math.abs(s.speed);
        if (s.onGround && a > 4 && v.age % 3 == 0) dust(col);
        if (s.inWater && a > 2 && v.age % 5 == 0) v.world.spawnParticle(Particle.SPLASH, v.pose.x, v.pose.y + 0.2, v.pose.z, 8, 0.6, 0.1, 0.6, 0);
    }

    /** One tick of the rigid-body model: input in, pose and shared state out, then the same side effects as the classic path. */
    private void tickRigid(Controls c, BlockCollider col, WheeledVehicleController.Params p) {
        VehicleRigidBodyPhysics r = rigid;
        Pose pose = v.pose;
        // somebody else moved the vehicle (spawn, restore, a command, the sanity guard): start from there
        if (!placed || pose.x != placedX || pose.y != placedY || pose.z != placedZ || (float) pose.yaw != placedYaw) { r.reset(pose.x, pose.y, pose.z, (float) pose.yaw); placed = true; }
        r.velocity.set((float) s.vel.x, (float) s.vel.y, (float) s.vel.z);       // ramming changes it between ticks
        VehicleRigidBodyPhysics.Input in = r.input;
        in.driver = c.present;
        in.throttle = (float) c.throttle();
        in.steer = (float) c.steer();
        in.handbrake = c.present && c.key(plugin.getConfig().getString("controls." + controlClass() + ".handbrake", "jump"));
        in.engineOn = s.engineOn && !v.outOfFuel();
        in.powerFactor = (float) v.powerFactor();
        in.speedLimitFactor = (float) chunkSpeedFactor(col);
        r.step(plugin.vehicles().voxels(v.world));
        pose.x = placedX = r.x; pose.y = placedY = r.y; pose.z = placedZ = r.z;
        pose.yaw = placedYaw = r.yaw; pose.pitch = r.pitch; pose.roll = r.roll;
        s.vel.set(r.velocity.x, r.velocity.y, r.velocity.z);
        s.speed = r.speed; s.throttle = r.throttle; s.steer = r.steer; s.steerAngle = r.steerAngle;
        s.yawRate = (r.yawRate + r.yawKick) * Units.DT;
        s.onGround = r.grounded; s.airborne = !r.grounded; s.inWater = r.inWater;
        s.wheelContact = r.wheelsOnGround / (double) r.contactCount();
        s.bodyPitchTarget = r.pitch; s.bodyRollTarget = r.roll; s.visualYOffset = 0;
        s.wheelSpin = r.wheelSpin; s.distance = r.distance; s.landingSpeed = r.landingSpeed;
        double turnLin = Math.toRadians(r.yawRate) * r.p.halfTrack;
        s.trackLeft += (r.speed - turnLin) * Units.DT * 16; s.trackRight += (r.speed + turnLin) * Units.DT * 16;
        afterMove(r.impactSpeed);
        if (r.landingSpeed > 0) landing(r.landingSpeed);
        engineSound(p);
        double a = Math.abs(s.speed);
        if (s.onGround && a > 4 && v.age % 3 == 0) dust(col);
        if (s.inWater && a > 2 && v.age % 5 == 0) v.world.spawnParticle(Particle.SPLASH, v.pose.x, v.pose.y + 0.2, v.pose.z, 8, 0.6, 0.1, 0.6, 0);
    }

    @Override public boolean atRest() { return rigid != null ? rigid.atRest() : super.atRest(); }
    @Override public boolean restDisturbed() { return rigid != null && placed && rigid.supportChanged(plugin.vehicles().voxels(v.world)); }

    /** Gear and RPM for the engine sound and the HUD (cosmetic: the controller's engine curve is what limits speed). */
    protected void engineSound(WheeledVehicleController.Params p) {
        List<Double> gears = t.physicsList("gears");
        double max = p.maxSpeed;
        if (gears.isEmpty()) gears = List.of(max * 0.2, max * 0.4, max * 0.65, max * 1.0);
        double a = Math.abs(s.speed);
        int gear = gears.size(); double lo = 0, hi = gears.get(gears.size() - 1);
        for (int i = 0; i < gears.size(); i++) if (a <= gears.get(i)) { gear = i + 1; hi = gears.get(i); lo = i == 0 ? 0 : gears.get(i - 1); break; }
        s.gear = s.speed < -0.2 ? -1 : a < 0.1 ? 0 : gear;
        double frac = hi > lo ? Pose.clamp((a - lo) / (hi - lo), 0, 1) : 0;
        double target = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * (a < 0.1 ? Math.abs(s.throttle) * 0.3 : 0.25 + 0.75 * frac) : 0;
        s.rpm = Pose.approach(s.rpm, target, 0.2);
    }

    protected void dust(BlockCollider col) {
        Block b = col.below(v.pose.x, v.pose.y + 0.1, v.pose.z);
        if (b == null || b.isPassable()) return;
        Vector3d back = v.pose.forwardFlat().mul(-v.hitboxHalfPx[2] / 16 * t.scale * 0.8);
        v.world.spawnParticle(Particle.BLOCK, v.pose.x + back.x, v.pose.y + 0.15, v.pose.z + back.z, 3, v.hitboxHalfPx[0] / 16 * t.scale * 0.5, 0.05, 0.3, b.getBlockData());
    }

    @Override public String debugLine() { return rigid != null ? rigid.debugLine() : last.line(v.fuel, t.fuelCapacity); }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (Math.abs(s.speed) <= 0.3) return t.vehicleClass == VehicleClass.TRACKED && Math.abs(s.steer) > 0.3 ? (s.steer < 0 ? "left" : "right") : "idle";
        boolean rev = s.speed < 0;
        if (s.steer < -0.3) return rev ? "reverse_left" : "left";
        if (s.steer > 0.3) return rev ? "reverse_right" : "right";
        return rev ? "reverse" : "drive";
    }
}
