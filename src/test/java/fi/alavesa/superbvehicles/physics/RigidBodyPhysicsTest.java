package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.Obstacle;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.Surface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rigid-body ground model against synthetic terrain. The car faces +X (yaw -90). */
class RigidBodyPhysicsTest {
    static final double G = 64;

    static VehicleRigidBodyPhysics car() { return car(new VehicleRigidBodyPhysics.Params()); }
    static VehicleRigidBodyPhysics car(VehicleRigidBodyPhysics.Params p) {
        VehicleRigidBodyPhysics r = new VehicleRigidBodyPhysics(p);
        r.reset(0.5, G, 0.5, -90);
        return r;
    }
    static void drive(VehicleRigidBodyPhysics r, TestVoxels w, int ticks, float throttle) {
        r.input.driver = true; r.input.engineOn = true; r.input.throttle = throttle;
        for (int i = 0; i < ticks; i++) { r.step(w); sane(r); }
    }
    static void sane(VehicleRigidBodyPhysics r) {
        assertTrue(Double.isFinite(r.x) && Double.isFinite(r.y) && Double.isFinite(r.z), "position");
        assertTrue(Float.isFinite(r.velocity.x) && Float.isFinite(r.velocity.y) && Float.isFinite(r.velocity.z), "velocity");
        assertTrue(Float.isFinite(r.pitch) && Float.isFinite(r.roll) && Float.isFinite(r.yaw), "attitude");
        assertTrue(Math.abs(r.pitch) < 60 && Math.abs(r.roll) < 60, "tilt " + r.pitch + " / " + r.roll);
    }
    /** Put the car on the ground rolling at v along +X. */
    static VehicleRigidBodyPhysics rolling(TestVoxels w, float v) {
        VehicleRigidBodyPhysics r = car();
        drive(r, w, 20, 0);
        r.velocity.set(v, 0, 0);
        return r;
    }

    @Test void settlesOnTheGroundAndComesToRest() {
        TestVoxels w = new TestVoxels(G);
        VehicleRigidBodyPhysics r = car();
        r.y = G + 0.4;                                    // dropped from 40 cm
        r.input.driver = false;
        int rest = -1;
        for (int i = 0; i < 100; i++) { r.step(w); sane(r); if (rest < 0 && r.atRest()) rest = i; }
        assertTrue(rest >= 0 && rest < 80, "at rest after " + rest + " ticks");
        assertEquals(G, r.y, 0.01, "rides at the ground height");
        assertEquals(4, r.wheelsOnGround);
        assertEquals(0, r.pitch, 0.2); assertEquals(0, r.roll, 0.2);
    }

    @Test void comesToRestAstrideALedgeToo() {
        TestVoxels w = new TestVoxels(G).box(0.6, G, 0.4, 30, G + 1, 50);       // a block under the front right wheel only
        VehicleRigidBodyPhysics r = car();
        r.input.driver = false;
        int rest = -1;
        for (int i = 0; i < 200; i++) { r.step(w); sane(r); if (rest < 0 && r.atRest()) rest = i; }
        assertTrue(rest >= 0, "a vehicle hung up on one corner still comes to rest (pitch " + r.pitch + " roll " + r.roll + ")");
        assertTrue(r.atRest(), "and stays at rest");
    }

    @Test void acceleratesAndTapersToTopSpeed() {
        TestVoxels w = new TestVoxels(G);
        VehicleRigidBodyPhysics r = car();
        drive(r, w, 20 * 4, 1);
        float after4s = r.speed;
        assertTrue(after4s > 0.4f * r.p.maxSpeed, "4 s of full throttle: " + after4s);
        float prev = r.speed;
        for (int i = 0; i < 20 * 60; i++) { r.step(w); assertTrue(r.speed >= prev - 0.02f, "speed never drops under power"); prev = r.speed; }
        assertEquals(r.p.maxSpeed, r.speed, r.p.maxSpeed * 0.04, "terminal speed is max-speed");
        float accelLate = r.speed - prev;
        assertTrue(Math.abs(accelLate) < 0.01f, "no acceleration left at the top");
    }

    @Test void coastsToAHaltSmoothly() {
        TestVoxels w = new TestVoxels(G);
        VehicleRigidBodyPhysics r = rolling(w, 15);
        float prev = 15.5f; int ticks = 0;
        while (Math.abs(r.speed) > 0.001f || ticks < 5) {
            drive(r, w, 1, 0);
            assertTrue(r.speed <= prev + 1e-3f && r.speed >= 0, "monotone, never backwards: " + r.speed);
            assertTrue(prev - r.speed < 1.0f, "no instant stop: " + prev + " -> " + r.speed);
            prev = r.speed;
            if (++ticks > 20 * 120) break;
        }
        assertTrue(ticks > 20 * 5, "coasting from 15 m/s takes more than 5 s (" + ticks + " ticks)");
        assertEquals(0, r.speed, 0.001);
    }

    static double stopDistance(TestVoxels w) {
        VehicleRigidBodyPhysics r = rolling(w, 15);
        double x0 = r.x;
        for (int i = 0; i < 20 * 60 && (i == 0 || r.speed > 0.01f); i++) drive(r, w, 1, -1);
        return r.x - x0;
    }

    @Test void brakingIsLimitedBySurfaceFriction() {
        double stone = stopDistance(new TestVoxels(G));
        TestVoxels ice = new TestVoxels(G); ice.floor = new Surface("ice", 0.05f, 1f);
        TestVoxels sand = new TestVoxels(G); sand.floor = new Surface("sand", 0.6f, 4f);
        double onIce = stopDistance(ice), onSand = stopDistance(sand);
        assertTrue(stone > 8 && stone < 40, "15 m/s on stone stops in " + stone + " m");
        assertTrue(onIce > stone * 5, "ice: " + onIce + " m vs " + stone + " m");
        assertTrue(onSand > stone, "sand grips less: " + onSand + " m vs " + stone + " m");
    }

    /** A staircase of slabs: every `run` blocks the road is half a block lower (down = true) or higher. */
    static TestVoxels slabHill(boolean down, int run, int steps) {
        TestVoxels w = new TestVoxels(G - (down ? steps * 0.5 : 0));
        for (int i = 0; i < steps; i++) {
            double top = down ? (steps - i) * 0.5 : (i + 1) * 0.5;
            double x0 = down ? (i == 0 ? -40 : 10 + (i - 1) * run) : 10 + i * run, x1 = down ? 10 + i * run : (i == steps - 1 ? 400 : 10 + (i + 1) * run);
            w.step(x0, x1, top);
        }
        return w;
    }

    @Test void gravityGivesAndTakesMomentumOnSlopes() {
        VehicleRigidBodyPhysics flat = rolling(new TestVoxels(G), 8);
        drive(flat, new TestVoxels(G), 60, 0);
        TestVoxels hillDown = slabHill(true, 3, 40);
        VehicleRigidBodyPhysics down = rolling(hillDown, 8);
        double y0 = down.y;
        drive(down, hillDown, 60, 0);
        assertTrue(down.y < y0 - 2, "went downhill: " + (y0 - down.y));
        assertTrue(down.speed > 8.5f, "coasting downhill gains speed: " + down.speed);
        assertTrue(down.speed > flat.speed + 1, "and beats flat-ground coasting (" + flat.speed + ")");

        TestVoxels hillUp = slabHill(false, 3, 40);
        VehicleRigidBodyPhysics up = rolling(hillUp, 8);
        drive(up, hillUp, 60, 0);
        assertTrue(up.speed < flat.speed - 1, "coasting uphill bleeds speed: " + up.speed + " vs flat " + flat.speed);
    }

    @Test void slabIsRolledOverWithoutLosingSpeed() {
        TestVoxels w = new TestVoxels(G).step(12, 400, 0.5);
        VehicleRigidBodyPhysics r = rolling(w, 10);
        float maxPitch = 0, minSpeed = 99;
        for (int i = 0; i < 50; i++) { drive(r, w, 1, 0.35f); maxPitch = Math.max(maxPitch, r.pitch); minSpeed = Math.min(minSpeed, r.speed); }
        assertEquals(Obstacle.NONE, r.lastObstacle);
        assertTrue(r.x > 20, "past the slab edge: " + r.x);
        assertEquals(G + 0.5, r.y, 0.06, "on the slab");
        assertTrue(maxPitch > 3, "the nose came up while the front wheels climbed: " + maxPitch);
        assertTrue(minSpeed > 9.3f, "a slab costs (almost) no speed: " + minSpeed);
    }

    @Test void fullBlockIsClimbedAtSpeedAndCostsSpeed() {
        TestVoxels w = new TestVoxels(G).step(12, 400, 1.0);
        VehicleRigidBodyPhysics r = rolling(w, 9);
        float maxPitch = 0;
        for (int i = 0; i < 60; i++) { drive(r, w, 1, 0); maxPitch = Math.max(maxPitch, r.pitch); }
        assertTrue(r.x > 16, "over the ledge: x " + r.x + " obstacle " + r.lastObstacle);
        assertEquals(G + 1, r.y, 0.08, "on top");
        assertTrue(maxPitch > 12, "pitched up sharply: " + maxPitch);
        // energy: at least the potential energy m g h is gone (flat coasting over the same time loses far less)
        float v2Lost = 81 - r.speed * r.speed;
        assertTrue(v2Lost > 2 * 9.81f * 1.0f, "v^2 lost " + v2Lost + " >= 2 g h");
        assertTrue(r.speed > 3, "still rolling: " + r.speed);
    }

    @Test void fullBlockIsRefusedWithoutSpeedOrTorque() {
        TestVoxels w = new TestVoxels(G).step(12, 400, 1.0);
        VehicleRigidBodyPhysics.Params p = new VehicleRigidBodyPhysics.Params();
        p.engineForce = 3000;                             // 0.15 g: too weak to pull itself up a block
        VehicleRigidBodyPhysics r = car(p);
        r.x = 6.5;
        boolean hit = false;
        for (int i = 0; i < 160; i++) { drive(r, w, 1, 0.25f); hit |= r.lastObstacle == Obstacle.FULL_BLOCK; assertTrue(r.speed < 2.6f, "stays slow: " + r.speed); }
        assertTrue(hit, "the ledge was a full-block obstacle");
        assertTrue(r.x + r.p.hullHalfLength <= 12.02, "nose stays in front of the ledge: " + (r.x + r.p.hullHalfLength));
        assertEquals(G, r.y, 0.05);
    }

    @Test void fullBlockIsClimbedFromStandstillWithEnoughTorque() {
        TestVoxels w = new TestVoxels(G).step(12, 400, 1.0);
        VehicleRigidBodyPhysics.Params p = new VehicleRigidBodyPhysics.Params();
        p.engineForce = 2000 * 9.81f * 0.9f;              // 0.9 g at the wheels
        VehicleRigidBodyPhysics r = car(p);
        r.x = 9.4;                                        // nose 0.6 from the ledge
        drive(r, w, 120, 1);
        assertTrue(r.x > 16 && Math.abs(r.y - (G + 1)) < 0.1, "climbed: x " + r.x + " y " + r.y);
    }

    @Test void wallIsAHardCollisionWithReboundAndYawKick() {
        TestVoxels w = new TestVoxels(G).box(12, G, 0.2, 13, G + 3, 50);     // covers only the right half of the lane (+Z is right when facing +X)
        VehicleRigidBodyPhysics r = rolling(w, 15);
        float impact = 0, kick = 0; Obstacle kind = Obstacle.NONE; float minVx = 99;
        for (int i = 0; i < 20; i++) {
            drive(r, w, 1, 0);
            if (r.impactSpeed > impact) { impact = r.impactSpeed; kind = r.lastObstacle; }
            kick = Math.max(kick, Math.abs(r.yawKick)); minVx = Math.min(minVx, r.velocity.x);
            assertTrue(r.x + r.p.hullHalfLength * 0.5 < 12.3, "never through the wall: " + r.x);
        }
        assertEquals(Obstacle.WALL, kind);
        assertTrue(impact > 13, "the whole closing speed was stopped: " + impact);
        assertTrue(minVx < 0, "rebound: " + minVx);
        assertTrue(kick > 5, "an off-centre hit turns the car: " + kick);
    }

    @Test void ledgeUnderALowCeilingIsNotClimbed() {
        // a full block ahead, and a roof 2.2 above the road: the 1.6 m body lifted by 1 would be crushed
        TestVoxels w = new TestVoxels(G).step(12, 400, 1.0).box(8, G + 2.2, -50, 400, G + 3.2, 50);
        VehicleRigidBodyPhysics r = rolling(w, 9);
        boolean hit = false;
        for (int i = 0; i < 40; i++) { drive(r, w, 1, 0); hit |= r.impactSpeed > 5; }
        assertTrue(hit, "hard hit instead of a climb");
        assertTrue(r.y < G + 0.3, "not lifted into the ceiling: " + r.y);
    }

    @Test void drivesOffALedgeFallsWithGravityAndLands() {
        TestVoxels w = new TestVoxels(G - 5).step(-40, 12, 5);                // a cliff edge at x = 12, 5 blocks down
        VehicleRigidBodyPhysics r = rolling(w, 12);
        int air = 0; float landing = 0, minPitch = 0;
        for (int i = 0; i < 80; i++) { drive(r, w, 1, 0); if (!r.grounded) air++; landing = Math.max(landing, r.landingSpeed); minPitch = Math.min(minPitch, r.pitch); }
        assertTrue(air >= 15 && air <= 26, "5 m at 9.81 m/s^2 is about one second in the air: " + air + " ticks");
        assertEquals(Math.sqrt(2 * 9.81 * 5), landing, 2.0, "touchdown speed");
        assertTrue(minPitch < -5, "the nose dropped over the edge: " + minPitch);
        assertTrue(r.grounded && Math.abs(r.y - (G - 5)) < 0.1, "landed on the lower ground: " + r.y);
    }

    @Test void nothingMovesWhileChunksAreMissing() {
        TestVoxels w = new TestVoxels(G);
        VehicleRigidBodyPhysics r = rolling(w, 10);
        w.loaded = false;
        double x = r.x, y = r.y;
        drive(r, w, 5, 1);
        assertTrue(r.frozen);
        assertEquals(x, r.x, 0); assertEquals(y, r.y, 0);
    }

    @Test void stepAllocatesNothing() {
        TestVoxels w = new TestVoxels(G);
        for (int i = 0; i < 40; i++) w.step(20 + i * 6, 23 + i * 6, i % 2 == 0 ? 0.5 : 1.0);   // slabs and blocks to climb
        VehicleRigidBodyPhysics r = car();
        r.input.driver = true; r.input.engineOn = true; r.input.throttle = 1; r.input.steer = 0.01f;
        for (int i = 0; i < 3000; i++) { if (r.x > 230) r.reset(0.5, G, 0.5, -90); r.step(w); }   // warm up: JIT, buffers at size
        r.reset(0.5, G, 0.5, -90);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 2000; i++) { if (r.x > 230) r.reset(0.5, G, 0.5, -90); r.step(w); }
        long bytes = mx.getThreadAllocatedBytes(id) - before;
        assertTrue(bytes < 2048, "2000 ticks of driving over steps allocated " + bytes + " bytes");
        assertFalse(r.frozen);
    }
}
