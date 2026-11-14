package fi.alavesa.superbvehicles.physics;

import org.joml.Vector3d;

/**
 * Mutable physics state shared by every class model; the renderer, HUD and weapons read it.
 * Units (0.2.0): velocities in blocks/SECOND, angles in degrees; only the position integration converts to per tick
 * ({@link Units#perTick}).
 */
public final class PhysicsState {
    public final Vector3d vel = new Vector3d();   // blocks per second (world)
    public double speed;                           // signed forward speed, blocks/s
    public double yawRate;                         // degrees this tick
    public double throttle, steer, brake;          // -1..1 inputs after smoothing
    public double steerAngle;                      // current wheel angle (deg)
    public boolean engineOn;
    public double rpm, idleRpm = 800, maxRpm = 6000;
    public int gear;                               // 1-based; 0 neutral, -1 reverse
    public double spool;                           // rotor/turbine spin-up 0..1
    public boolean onGround, inWater, airborne;
    public double waterFraction;                   // boats: how much of the hull samples are in water
    public double groundClearance;                 // body origin above the average ground (blocks)
    public double collective, cyclicPitch, cyclicRoll, pedal;   // helicopter / plane control positions
    public boolean gearDown = true, flapsDown, airbrake, hover;
    public double altitude, verticalSpeed;         // blocks, blocks/s
    public double airspeed;                        // blocks/s
    public boolean stalled;
    public double wheelSpin;                       // accumulated wheel rotation (deg)
    public double trackLeft, trackRight;           // accumulated track travel (px)
    public double rotorAngle, propAngle;           // deg
    public double gearPos = 1, flapPos;            // 0..1 animation positions
    public double lastImpact;                      // blocks/s lost last tick to a collision
    public double landingSpeed;                    // blocks/s of the touchdown this tick (0 when none)
    public int crashCooldown, idleTicks;
    public double wheelContact;                    // fraction of wheels touching the ground
    public double bodyPitchTarget, bodyRollTarget;
    public double wake;                            // boats: planing factor 0..1
    public double distance;                        // odometer (blocks)
    public double flareTicks;                      // countermeasure active ticks left
    public double fallSpeed;                       // max downward speed while airborne (blocks/s)
    public String activeClamp = "";                // the hard limit that capped the motion this tick (debug)
    public double visualYOffset;                   // blocks: render-only offset that eases a step up/down over a few ticks

    public double horizontalSpeed() { return Math.hypot(vel.x, vel.z); }
    public double speedBps() { return vel.length(); }
}
