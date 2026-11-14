package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.render.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** "Cars get stuck around slabs": driving over slabs, up and down slab roads, onto full blocks. */
class SlabDrivingTest {
    static final double BASE = 64;

    static final class Car {
        final WheeledVehicleController ctl; final Pose pose = new Pose(); final PhysicsState s = new PhysicsState();
        final BlockCollider col; final WheeledVehicleController.Input in = new WheeledVehicleController.Input();
        WheeledVehicleController.Report last;
        Car(String id, StepTerrain t, double x, double z, double yaw) {
            ctl = new WheeledVehicleController(WheeledVehicleController.Params.of(WheeledVehicleControllerTest.type(id)), WheeledVehicleControllerTest.box(1.0, 0.8, 2.2));
            col = new BlockCollider(t);
            pose.x = x; pose.z = z; pose.y = t.top((int) Math.floor(x), (int) Math.floor(z)); pose.yaw = yaw; pose.modelYaw = 180;
            in.driver = true; in.engineOn = true;
        }
        void run(double seconds) { for (int i = 0; i < Math.round(seconds * 20); i++) last = ctl.step(pose, s, in, col); }
    }

    /** Pose yaw 0 drives toward +Z. */
    @Test void drivesOverASingleSlabStraightAndAtAnAngle() {
        for (double yaw : new double[]{0, -20, -45}) {
            StepTerrain t = new StepTerrain(BASE).area(-20, 12, 20, 13, 0.5);   // a slab strip across the road
            Car car = new Car("sodayo", t, 0.5, 0.5, yaw);
            car.in.throttle = 1;
            car.run(6);
            Pose p = car.pose;
            assertTrue(p.z > 30, "yaw " + yaw + ": stuck at z " + p.z + " - " + car.last.line(1, 1));
            assertTrue(car.s.speed > 10, "yaw " + yaw + ": lost its speed: " + car.s.speed);
        }
    }

    @Test void climbsAndDescendsASlabRoad() {
        StepTerrain t = new StepTerrain(BASE);
        // a road rising half a block every 3 blocks for 8 steps (4 blocks up), a plateau, then down again
        for (int i = 0; i < 8; i++) t.area(-3, 6 + i * 3, 3, 8 + i * 3, 0.5 * (i + 1));
        t.area(-3, 30, 3, 40, 4.0);
        for (int i = 0; i < 8; i++) t.area(-3, 41 + i * 3, 3, 43 + i * 3, 4.0 - 0.5 * (i + 1));
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 0.7;
        double maxY = 0;
        int stuckTicks = 0; double lastZ = car.pose.z;
        for (int i = 0; i < 20 * 12; i++) {
            car.last = car.ctl.step(car.pose, car.s, car.in, car.col);
            maxY = Math.max(maxY, car.pose.y);
            if (car.pose.z - lastZ < 0.01) stuckTicks++; lastZ = car.pose.z;
            if (car.pose.z > 72) break;
        }
        assertTrue(maxY >= BASE + 3.99, "never reached the top: " + maxY);
        assertTrue(car.pose.z > 72, "stuck at z " + car.pose.z + " y " + car.pose.y + " - " + car.last.line(1, 1));
        assertTrue(stuckTicks < 10, "hesitated " + stuckTicks + " ticks");
        assertEquals(BASE, car.pose.y, 0.05, "back down on the floor");
    }

    @Test void climbsAFullBlock() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 12, 20, 400, 1.0);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        car.run(5);
        assertEquals(BASE + 1, car.pose.y, 0.05, "not on the block: z " + car.pose.z);
        assertTrue(car.pose.z > 20);
    }

    @Test void stopsAtATwoBlockWall() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 12, 20, 13, 2.0);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        car.run(4);
        assertTrue(car.pose.z < 12, "drove through or over a wall: z " + car.pose.z);
        assertEquals(BASE, car.pose.y, 0.05);
    }

    @Test void parkedHalfOnASlabStaysStill() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 2, 20, 10, 0.5);
        Car car = new Car("sodayo", t, 0.5, 1.6, 0);    // front on the slab, rear on the floor
        car.in.throttle = 0;
        car.run(1);
        double y = car.pose.y, z = car.pose.z;
        car.run(3);
        assertEquals(y, car.pose.y, 1e-3, "jitters or sinks");
        assertEquals(z, car.pose.z, 1e-3, "creeps");
        assertTrue(car.pose.y >= BASE + 0.49, "the box must rest on the highest block under it: y " + car.pose.y);
    }

    @Test void carpetAndPathBlocksAreDrivenOverWithoutAnyStop() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 8, 20, 9, 0.0625).area(-20, 16, 20, 17, 0.9375);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        car.run(5);
        assertTrue(car.pose.z > 30, "stuck at " + car.pose.z);
    }

    // ---------------------------------------------------------------- impacts (0.5.0)

    /** Drive at full throttle; returns {largest impact seen, speed just before it, speed just after it}. */
    static double[] maxImpact(Car car, double seconds) {
        double best = 0, before = 0, after = 0, prevSpeed = 0;
        for (int i = 0; i < Math.round(seconds * 20); i++) {
            car.last = car.ctl.step(car.pose, car.s, car.in, car.col);
            if (car.last.lostSpeed > best) { best = car.last.lostSpeed; before = prevSpeed; after = car.s.speed; }
            prevSpeed = car.s.speed;
        }
        return new double[]{best, before, after};
    }

    @Test void hittingAWallAtSpeedRegistersTheSpeedLost() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 60, 20, 61, 3.0);   // wall 60 blocks ahead
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        double[] hit = maxImpact(car, 8);
        assertTrue(hit[1] > 18, "should hit the wall fast: " + hit[1]);
        assertTrue(hit[0] >= hit[1] * 0.9, "impact " + hit[0] + " for a hit at " + hit[1] + " b/s");
    }

    @Test void slabsAtFullSpeedAreNotAnImpact() {
        StepTerrain t = new StepTerrain(BASE);
        for (int i = 0; i < 8; i++) t.area(-3, 30 + i * 3, 3, 32 + i * 3, 0.5 * (i + 1));
        t.area(-3, 54, 3, 300, 4.0);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        double[] hit = maxImpact(car, 8);
        assertTrue(hit[0] < 6, "a slab road at full speed must stay free, impact " + hit[0]);
        assertTrue(car.pose.y >= BASE + 3.99, "and still climbs it");
    }

    @Test void aFullBlockAtSpeedIsACurbStrike() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 60, 20, 400, 1.0);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 1;
        double[] hit = maxImpact(car, 8);
        assertTrue(hit[0] >= 15, "a full block at " + hit[1] + " b/s should hurt: impact " + hit[0]);
        assertTrue(hit[2] < hit[1] * 0.7, "and slow the car: " + hit[1] + " -> " + hit[2]);
        assertTrue(car.last.collision.startsWith("curb") || car.pose.y >= BASE + 0.99, car.last.collision);
    }

    @Test void aFullBlockTakenSlowlyIsFree() {
        StepTerrain t = new StepTerrain(BASE).area(-20, 6, 20, 400, 1.0);
        Car car = new Car("sodayo", t, 0.5, 0.5, 0);
        car.in.throttle = 0.25;
        double[] hit = maxImpact(car, 6);
        assertTrue(car.pose.y >= BASE + 0.99, "climbed it");
        assertTrue(hit[0] < 6, "creeping onto a block is not a crash: " + hit[0] + " at " + hit[1]);
    }

    @Test void closingSpeedIsTheComponentTowardTheTarget() {
        org.joml.Vector3d v = new org.joml.Vector3d(0, 0, 20);
        assertEquals(20, VehiclePhysics.closingSpeed(v, new org.joml.Vector3d(0, 0, 0), new org.joml.Vector3d(0, 0, 5)), 1e-9);
        assertEquals(0, VehiclePhysics.closingSpeed(v, new org.joml.Vector3d(0, 0, 0), new org.joml.Vector3d(0, 0, -5)), 1e-9, "driving away is no hit");
        assertEquals(20 / Math.sqrt(2), VehiclePhysics.closingSpeed(v, new org.joml.Vector3d(0, 0, 0), new org.joml.Vector3d(5, 3, 5)), 1e-9, "a glancing hit counts less; height ignored");
    }
}
