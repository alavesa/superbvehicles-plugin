package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

/**
 * Tanks and IFVs: differential steering (A/D turn the tracks against each other - on the spot when stopped, a
 * neutral turn), heavy momentum, no handbrake drift, the tracks creep with their side's speed.
 * Extra YAML keys: neutral-turn-rate (deg/s), track-width.
 */
public final class TrackedPhysics extends WheeledPhysics {
    public TrackedPhysics(Vehicle v) { super(v); }

    @Override public void tick(Controls c, BlockCollider col) {
        engineControl(c, "tracked");
        double max = t.maxSpeed, revMax = t.physics("reverse-speed", t.maxSpeed * 0.5);
        double accel = t.enginePower / Math.max(100, t.mass) * 20 * v.powerFactor();
        double brake = t.physics("brake", 10), friction = t.physics("rolling-friction", 2.0), drag = t.physics("drag", 0.01);
        double throttle = c.present && s.engineOn ? c.throttle() : 0;
        s.throttle = smooth(s.throttle, throttle, 0.15);
        s.steer = smooth(s.steer, c.present && s.engineOn ? c.steer() : 0, 0.2);
        s.inWater = inWater(col);
        double speed = s.speed, dt = 1 / 20.0;
        if (s.onGround || s.inWater) {
            if (s.throttle > 0) speed += (speed < -0.3 ? brake : accel * torque(speed, max) * s.throttle) * dt;
            else if (s.throttle < 0) speed += (speed > 0.3 ? brake * s.throttle : accel * 0.8 * s.throttle * torque(-speed, revMax)) * dt;
            speed -= Math.signum(speed) * Math.min(Math.abs(speed), (friction + drag * speed * speed) * dt);
            if (s.inWater) speed *= 0.97;
        }
        speed = Pose.clamp(speed, -revMax, max);
        if (Math.abs(speed) < 0.02) speed = 0;
        // differential steering: full neutral turn when stopped, turn rate shrinks with speed
        double neutral = t.physics("neutral-turn-rate", 40) / 20.0;
        double yawRate = 0;
        if (s.onGround && s.steer != 0) {
            double k = Math.abs(speed) < 0.5 ? 1.0 : Pose.clamp(1.2 - Math.abs(speed) / Math.max(1, max), 0.35, 1.0);
            yawRate = s.steer * neutral * k * t.turnRate / 2.0 * (speed < -0.5 ? -1 : 1);
            if (Math.abs(speed) > 0.5) speed *= 0.995;   // steering eats a little speed
        }
        s.yawRate = yawRate;
        v.pose.yaw = Pose.wrap(v.pose.yaw + yawRate);
        s.steerAngle = 0;
        double grip = s.onGround ? t.physics("grip", 0.5) : 0.02;
        Vector3d desired = v.pose.forwardFlat().mul(speed * dt);
        Vector3d flat = new Vector3d(s.vel.x, 0, s.vel.z).mul(1 - grip).add(desired.mul(grip));
        s.vel.x = flat.x; s.vel.z = flat.z;
        if (!s.onGround) s.vel.y = Math.max(-3.5, s.vel.y - gravity()); else s.vel.y = Math.min(0, s.vel.y);
        if (s.inWater) s.vel.y = Math.max(s.vel.y, -0.08);
        boolean wasGround = s.onGround;
        double fallBps = -s.vel.y * 20;
        BlockCollider.Result r = move(col, new Vector3d(s.vel), true);
        if (r.hitHorizontal()) speed *= 0.2;
        s.speed = speed;
        s.vel.x = r.moved.x; s.vel.z = r.moved.z;
        followGround(col, t.physics("suspension-rate", 0.25), t.physics("max-tilt", 40));
        if (s.onGround && !wasGround) landing(fallBps);
        double lon = s.throttle * t.physics("body-pitch", 1.0) * Math.min(1, Math.abs(speed) / Math.max(1, max));
        bodyPitch = Pose.approach(bodyPitch, s.onGround ? s.bodyPitchTarget - lon : airPitch(), s.onGround ? 0.2 : 0.06);
        bodyRoll = Pose.approach(bodyRoll, s.onGround ? s.bodyRollTarget : bodyRoll * 0.98, 0.2);
        v.pose.pitch = bodyPitch; v.pose.roll = bodyRoll;
        double halfW = v.hitboxHalfPx[0] / 16 * t.scale;
        double turnLin = Math.toRadians(yawRate) * halfW * 20;   // blocks/s difference between the tracks
        s.trackLeft += (speed - turnLin) * dt * 16; s.trackRight += (speed + turnLin) * dt * 16;
        s.wheelSpin = (s.wheelSpin + Math.toDegrees(speed * dt / 0.4)) % 360;
        double rpmTarget = s.engineOn ? s.idleRpm + (s.maxRpm - s.idleRpm) * Pose.clamp(0.2 + 0.6 * Math.abs(speed) / Math.max(1, max) + 0.3 * Math.abs(s.steer), 0, 1) : 0;
        s.rpm = Pose.approach(s.rpm, rpmTarget, 0.15);
        s.gear = speed < -0.2 ? -1 : Math.abs(speed) < 0.1 ? 0 : 1 + (int) (Math.abs(speed) / Math.max(1, max) * 3.99);
        s.airborne = !s.onGround;
        if (s.onGround && (Math.abs(speed) > 2 || Math.abs(yawRate) > 0.5) && v.age % 3 == 0) dust(col);
    }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn) return "idle";
        if (Math.abs(s.speed) <= 0.3) return Math.abs(s.steer) > 0.3 ? (s.steer < 0 ? "left" : "right") : "idle";
        return super.animationState();
    }
}
