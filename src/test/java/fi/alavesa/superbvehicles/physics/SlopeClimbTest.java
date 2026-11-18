package fi.alavesa.superbvehicles.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ramps the way players build them: slab staircases (half a block up per block or per two blocks), stairs (slab +
 * block per block of run) and full-block staircases, driven straight, at an angle, from a run-up and from a
 * standstill, with a car and with a long six-wheeled truck. Nothing may get stuck.
 */
class SlopeClimbTest {
    static final double G = 64;

    /** `steps` risers of `rise` every `run` blocks starting at x = 10, as wide as the world; then a plateau. */
    static TestVoxels ramp(double rise, int run, int steps) {
        TestVoxels w = new TestVoxels(G);
        for (int i = 0; i < steps; i++) w.box(10 + i * run, G - 1, -9000, i == steps - 1 ? 9000 : 10 + (i + 1) * run, G + (i + 1) * rise, 9000);
        return w;
    }

    static VehicleRigidBodyPhysics.Params truck() {
        VehicleRigidBodyPhysics.Params p = new VehicleRigidBodyPhysics.Params();
        p.mass = 6500; p.engineForce = 17500; p.maxSpeed = 25; p.rollingResistance = 0.018f;
        p.brakeForce = 52000; p.handbrakeForce = 60000; p.parkingBrakeForce = 39000;
        p.hullHalfWidth = 1.25f; p.hullHalfLength = 4f; p.hullHeight = 2.8f; p.halfTrack = 1.05f; p.halfBase = 3.2f; p.wheelbase = 6.4f;
        p.sixContacts = true;
        return p;
    }

    /** Drive for `ticks` with full throttle from x0 at `yaw`; returns the body. Fails on NaN or absurd tilt. */
    static VehicleRigidBodyPhysics run(TestVoxels w, VehicleRigidBodyPhysics.Params p, double x0, float yaw, float startSpeed, int ticks) {
        VehicleRigidBodyPhysics r = new VehicleRigidBodyPhysics(p);
        r.reset(x0, G, 0.5, yaw);
        r.input.driver = true; r.input.engineOn = true;
        for (int i = 0; i < 20; i++) r.step(w);
        double rad = Math.toRadians(yaw);
        r.velocity.set((float) (-Math.sin(rad) * startSpeed), 0, (float) (Math.cos(rad) * startSpeed));
        r.input.throttle = 1;
        for (int i = 0; i < ticks; i++) { r.step(w); RigidBodyPhysicsTest.sane(r); }
        return r;
    }

    static void climbs(String what, TestVoxels w, VehicleRigidBodyPhysics.Params p, double x0, float yaw, float v0, int ticks, double top) {
        VehicleRigidBodyPhysics r = run(w, p, x0, yaw, v0, ticks);
        assertTrue(r.y > G + top - 0.15, what + ": reached y " + (r.y - G) + " of " + top + " (x " + r.x + ", speed " + r.speed + ", pitch " + r.pitch + ", wheels " + r.wheelsOnGround + ", last obstacle " + r.lastObstacle + ")");
    }

    @Test void slabStaircasesAreClimbedFromAStandstill() {
        for (int run = 1; run <= 3; run++) {
            climbs("car, slab per " + run, ramp(0.5, run, 12), new VehicleRigidBodyPhysics.Params(), 6.5, -90, 0, 400, 6);
            climbs("truck, slab per " + run, ramp(0.5, run, 12), truck(), 4.5, -90, 0, 500, 6);
        }
    }

    @Test void slabStaircasesAreClimbedAtAnyAngle() {
        for (float yaw : new float[]{-90, -80, -67.5f, -60, -45, -120, -135}) {
            double reach = 6 / Math.max(0.3, Math.abs(Math.sin(Math.toRadians(yaw))));
            climbs("car at yaw " + yaw, ramp(0.5, 1, 12), new VehicleRigidBodyPhysics.Params(), 5.5, yaw, 0, (int) (400 + reach * 20), 6);
            climbs("truck at yaw " + yaw, ramp(0.5, 2, 12), truck(), 2.5, yaw, 0, (int) (600 + reach * 20), 6);
        }
    }

    @Test void slabsBarelySlowAMovingCar() {
        // twelve slabs, one every two blocks: a 25% grade
        TestVoxels w = ramp(0.5, 2, 12);
        VehicleRigidBodyPhysics r = run(w, new VehicleRigidBodyPhysics.Params(), 0.5, -90, 12, 60);
        assertTrue(r.y > G + 5.8, "on top after 3 s: " + (r.y - G) + " speed " + r.speed);
        assertTrue(r.speed > 10.5f, "still about as fast as it came: " + r.speed);
    }

    /** Every heading in 7.5 degree steps, three entry speeds, three ramp pitches, both vehicles: all must arrive on top. */
    @Test void sweepOfHeadingsSpeedsAndRamps() {
        int cases = 0;
        StringBuilder stuck = new StringBuilder();
        for (int run = 1; run <= 3; run++) for (float v0 : new float[]{0, 3, 9}) for (float yaw = -150; yaw <= -30; yaw += 7.5f) for (int kind = 0; kind < 2; kind++) {
            cases++;
            VehicleRigidBodyPhysics r = run(ramp(0.5, run, 10), kind == 0 ? new VehicleRigidBodyPhysics.Params() : truck(), 3.5, yaw, v0, 900);
            if (r.y < G + 5 - 0.15) stuck.append(String.format("%n%s run %d v0 %.0f yaw %.1f: y %.2f x %.1f speed %.2f pitch %.1f roll %.1f obstacle %s", kind == 0 ? "car" : "truck", run, v0, yaw, r.y - G, r.x, r.speed, r.pitch, r.roll, r.lastObstacle));
        }
        assertTrue(stuck.length() == 0, "stuck in " + stuck.toString().lines().count() + " of " + cases + ":" + stuck);
    }

    /** Steering left and right all the way up must not wedge the hull. */
    @Test void weavingUpARamp() {
        for (int kind = 0; kind < 2; kind++) {
            VehicleRigidBodyPhysics r = new VehicleRigidBodyPhysics(kind == 0 ? new VehicleRigidBodyPhysics.Params() : truck());
            TestVoxels w = ramp(0.5, 2, 30);
            r.reset(3.5, G, 0.5, -90); r.input.driver = true; r.input.engineOn = true; r.input.throttle = 1;
            for (int i = 0; i < 1200 && r.y < G + 14.8; i++) {
                double off = Math.abs(((r.yaw + 90 + 540) % 360) - 180);                 // how far the nose is from straight up the ramp
                r.input.steer = off > 35 ? (((r.yaw + 90 + 540) % 360) - 180 > 0 ? -1 : 1) : (i / 30) % 2 == 0 ? 1 : -1;
                r.step(w); RigidBodyPhysicsTest.sane(r);
            }
            assertTrue(r.y > G + 14.8, (kind == 0 ? "car" : "truck") + " weaving: y " + (r.y - G) + " x " + r.x + " yaw " + r.yaw + " speed " + r.speed);
        }
    }

    @Test void contactPointsFromTheModelAreOrderedByCorner() {
        // six points in no particular order (right, forward): a 3-axle truck whose rear axles sit close together
        double[] right = {1.0, -1.0, 1.0, -1.0, -1.0, 1.0}, fwd = {-2.5, 3.0, 3.0, -2.5, -1.0, -1.0};
        float[][] c = WheeledPhysics.pickContacts(right, fwd);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[]{-1, 1, -1, 1, -1, 1}, c[0]);       // FL FR RL RR ML MR
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[]{3, 3, -2.5f, -2.5f, -1, -1}, c[1]);
        org.junit.jupiter.api.Assertions.assertNull(WheeledPhysics.pickContacts(new double[]{1, 1, 1, 1}, new double[]{-2, -1, 1, 2}), "all on one side: not a footprint");
        // and a body built from them drives up a ramp like any other
        VehicleRigidBodyPhysics.Params p = truck();
        p.contactRight = c[0]; p.contactForward = c[1];
        climbs("truck on its own vertices", ramp(0.5, 2, 12), p, 4.5, -75, 0, 700, 6);
    }

    static VehicleRigidBodyPhysics coast(TestVoxels w) {
        VehicleRigidBodyPhysics r = new VehicleRigidBodyPhysics(new VehicleRigidBodyPhysics.Params());
        r.reset(0.5, G, 0.5, -90); r.input.driver = true; r.input.engineOn = true;
        for (int i = 0; i < 20; i++) r.step(w);
        r.velocity.set(12, 0, 0);
        for (int i = 0; i < 200 && r.x < 28 && r.speed >= 0 && (i < 3 || r.speed > 0.05f); i++) { r.step(w); RigidBodyPhysicsTest.sane(r); }
        return r;
    }

    @Test void fullBlockStaircaseIsTheHardOne() {
        // the same 4 blocks of height over the same 16 blocks of road, coasting in at 12 m/s without throttle
        VehicleRigidBodyPhysics blocks = coast(ramp(1.0, 4, 4)), slabs = coast(ramp(0.5, 2, 8));
        assertTrue(slabs.y > G + 3.8, "slabs: on top " + (slabs.y - G));
        assertTrue(slabs.speed > 8.5f, "slabs cost little: " + slabs.speed);
        assertTrue(blocks.speed < slabs.speed - 3, "full blocks cost clearly more: " + blocks.speed + " vs " + slabs.speed);
    }

    @Test void stairsAndCreepingSpeedsNeverStick() {
        // every block of run: a slab, then a full block behind it (the shape of a stair block), twice as steep as slabs
        TestVoxels stairs = new TestVoxels(G);
        for (int i = 0; i < 8; i++) { stairs.box(10 + i, G - 1, -200, 10.5 + i, G + i + 0.5, 200); stairs.box(10.5 + i, G - 1, -200, i == 7 ? 600 : 11 + i, G + i + 1, 200); }
        climbs("car up stair blocks with a run-up", stairs, new VehicleRigidBodyPhysics.Params(), 0.5, -90, 10, 300, 8);
        // crawling: 15% throttle on a gentle slab ramp
        VehicleRigidBodyPhysics r = new VehicleRigidBodyPhysics(new VehicleRigidBodyPhysics.Params());
        TestVoxels w = ramp(0.5, 3, 6);
        r.reset(6.5, G, 0.5, -90); r.input.driver = true; r.input.engineOn = true; r.input.throttle = 0.3f;
        for (int i = 0; i < 1200; i++) { r.step(w); RigidBodyPhysicsTest.sane(r); }
        assertTrue(r.y > G + 2.85, "crawled up at 30% throttle: " + (r.y - G) + " x " + r.x);
    }
}
