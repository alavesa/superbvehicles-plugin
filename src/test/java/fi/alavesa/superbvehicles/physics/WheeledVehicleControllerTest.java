package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import fi.alavesa.superbvehicles.seat.InputCache;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** "Cars do not move" regression tests: the controller on a flat floor, no server. */
class WheeledVehicleControllerTest {
    static final double FLOOR = 64;

    static VehicleType type(String id) {
        File f = new File("src/main/resources/vehicles/" + id + ".yml");
        return new VehicleType(id, YamlConfiguration.loadConfiguration(f), f);
    }

    /** A box hull: half extents in blocks, bottom at the pose origin. */
    static WheeledVehicleController.Hull box(double hx, double hy, double hz) {
        return p -> new Obb(p.toWorld(0, hy * 16, 0, 1.0), new Vector3d(hx, hy, hz), p.rotation());
    }

    static final class Sim {
        final WheeledVehicleController ctl; final Pose pose = new Pose(); final PhysicsState s = new PhysicsState();
        final BlockCollider col = new BlockCollider(Terrain.flat(FLOOR));
        final WheeledVehicleController.Input in = new WheeledVehicleController.Input();
        WheeledVehicleController.Report last;
        Sim(VehicleType t, double hx, double hy, double hz) {
            ctl = new WheeledVehicleController(WheeledVehicleController.Params.of(t), box(hx, hy, hz));
            pose.y = FLOOR; pose.modelYaw = 180;
            in.driver = true; in.engineOn = true;
        }
        Sim(String id) { this(type(id), 1.0, 0.8, 2.2); }
        void run(double seconds) { for (int i = 0; i < Math.round(seconds * 20); i++) last = ctl.step(pose, s, in, col); }
        double speed() { return s.speed; }
        double max() { return ctl.params().maxSpeed; }
        /** Seconds of full throttle until `fraction` of max speed (or -1). */
        double timeTo(double fraction) { for (int i = 1; i <= 20 * 60; i++) { last = ctl.step(pose, s, in, col); if (s.speed >= fraction * max()) return i / 20.0; } return -1; }
    }

    @Test void fullThrottleFiveSecondsReachesSixtyPercent() {
        for (String id : new String[]{"sodayo", "truck", "lav150", "sodayo_mg", "bmp2", "tom6", "plz05", "wheelchair"}) {
            Sim sim = new Sim(id);
            sim.in.throttle = 1;
            sim.run(id.equals("truck") || id.equals("plz05") || id.equals("tom6") || id.equals("bmp2") || id.equals("lav150") ? 8 : 5);
            assertTrue(sim.speed() > 0.6 * sim.max(), id + ": " + sim.speed() + " of max " + sim.max() + " - " + sim.last.line(1, 1));
        }
    }

    @Test void accelerationTimesMatchTheTuningTargets() {
        Sim pickup = new Sim("sodayo"); pickup.in.throttle = 1;
        double tp = pickup.timeTo(0.8);
        assertTrue(tp >= 6 && tp <= 8, "pickup reaches 80% in " + tp + " s (want 6-8)");
        Sim truck = new Sim(type("truck"), 1.3, 1.2, 3.4); truck.in.throttle = 1;
        double tt = truck.timeTo(0.8);
        assertTrue(tt >= 10 && tt <= 12, "truck reaches 80% in " + tt + " s (want 10-12)");
    }

    @Test void zeroFuelDoesNotMoveAndSaysWhy() {
        Sim sim = new Sim("sodayo");
        sim.in.throttle = 1; sim.in.outOfFuel = true;
        sim.run(3);
        assertEquals(0, sim.speed(), 1e-9);
        assertEquals(FLOOR, sim.pose.y, 0.01);
        assertTrue(sim.last.reason.contains("out of fuel"), sim.last.reason);
        assertTrue(sim.last.line(0, 90).contains("why not moving: out of fuel"));
    }

    @Test void oneInputEventKeepsTheVehicleAcceleratingForAHundredTicks() {
        InputCache cache = new InputCache();
        UUID player = UUID.randomUUID();
        cache.put(player, input(true, false, false, false, false));      // ONE event: W pressed
        Sim sim = new Sim("sodayo");
        Controls c = new Controls();
        double prev = -1;
        for (int tick = 0; tick < 100; tick++) {
            c.held(cache.get(player));                                 // what Vehicle.tick does every tick
            c.present = true;
            sim.in.throttle = c.throttle(); sim.in.steer = c.steer();
            sim.last = sim.ctl.step(sim.pose, sim.s, sim.in, sim.col);
            c.clearEdges();
            if (tick > 2) assertTrue(sim.speed() > prev, "tick " + tick + " speed " + sim.speed() + " not above " + prev);
            prev = sim.speed();
        }
        assertTrue(prev > 5, "after 100 ticks " + prev);
    }

    @Test void passengersDoNotDriveDriversDo() {
        VehicleType t = type("sodayo");
        assertEquals(VehicleType.SeatRole.DRIVER, t.driverSeat().role);
        assertFalse(t.seats.get(1).role.controls(), "the second seat is a passenger seat");
        Sim passenger = new Sim("sodayo");
        passenger.in.driver = false; passenger.in.throttle = 1;
        passenger.run(3);
        assertEquals(0, passenger.speed(), 1e-9);
        assertTrue(passenger.last.reason.contains("driver seat"), passenger.last.reason);
        Sim driver = new Sim("sodayo");
        driver.in.throttle = 1;
        driver.run(3);
        assertTrue(driver.speed() > 5, "driver: " + driver.speed());
    }

    @Test void spawnedSlightlyInsideTheGroundDepenetratesAndDrives() {
        Sim sim = new Sim("sodayo");
        sim.pose.y = FLOOR - 0.12;                                      // hitbox 12 cm into the floor
        sim.in.throttle = 1;
        sim.run(4);
        assertTrue(sim.pose.y >= FLOOR - 0.01, "still inside: y " + sim.pose.y);
        assertTrue(sim.speed() > 8, "speed " + sim.speed() + " - " + sim.last.line(1, 1));
        assertTrue(Math.hypot(sim.pose.x, sim.pose.z) > 15, "moved " + Math.hypot(sim.pose.x, sim.pose.z));
    }

    @Test void restingContactIsNotAWall() {
        BlockCollider col = new BlockCollider(Terrain.flat(FLOOR));
        Pose p = new Pose(); p.y = FLOOR;
        Obb hull = box(1, 0.8, 2.2).at(p);
        BlockCollider.Result r = col.sweep(hull, new Vector3d(0.5, 0, 0.3), 0.6, true);
        assertFalse(r.hitHorizontal());
        assertFalse(r.stepped, "a hull resting on the floor must not need the step-up path to move");
        assertEquals(0.5, r.moved.x, 1e-9); assertEquals(0.3, r.moved.z, 1e-9); assertEquals(0, r.moved.y, 1e-9);
    }

    @Test void steeringTurnsTheHeadingAndTheVelocityFollows() {
        Sim sim = new Sim("sodayo");
        sim.in.throttle = 1;
        sim.run(3);
        double yaw0 = sim.pose.yaw;
        sim.in.steer = 1;
        sim.run(2);
        assertTrue(Math.abs(Pose.wrap(sim.pose.yaw - yaw0)) > 30, "turned only " + Pose.wrap(sim.pose.yaw - yaw0));
        assertTrue(Pose.wrap(sim.pose.yaw - yaw0) > 0, "D turns right (yaw increases)");
        sim.in.steer = 0;
        sim.run(1.5);
        assertTrue(Math.abs(sim.last.lateralSpeed) < 0.1 * Math.abs(sim.last.forwardSpeed) + 0.05, "sliding sideways: lat " + sim.last.lateralSpeed + " fwd " + sim.last.forwardSpeed);
        Vector3d f = sim.pose.forwardFlat();
        double along = (sim.s.vel.x * f.x + sim.s.vel.z * f.z) / sim.s.vel.length();
        assertTrue(along > 0.99, "velocity follows the nose: cos " + along);
    }

    @Test void reverseFromStandstillIsCapped() {
        Sim sim = new Sim("sodayo");
        sim.in.throttle = -1;
        sim.run(10);
        assertTrue(sim.speed() < -1, "reverse speed " + sim.speed());
        assertTrue(-sim.speed() <= sim.ctl.params().maxReverseSpeed * 1.021, "reverse " + sim.speed() + " over cap " + sim.ctl.params().maxReverseSpeed);
    }

    @Test void afterTheDriverLeavesItStopsAndStaysPut() {
        Sim sim = new Sim("sodayo");
        sim.in.throttle = 1;
        sim.run(5);
        assertTrue(sim.speed() > 10);
        sim.in.driver = false; sim.in.throttle = 0;
        sim.run(6);
        assertEquals(0, sim.speed(), 1e-9, "still rolling: " + sim.speed());
        double x = sim.pose.x, z = sim.pose.z, y = sim.pose.y, yaw = sim.pose.yaw;
        sim.run(5);
        assertEquals(x, sim.pose.x, 1e-9); assertEquals(z, sim.pose.z, 1e-9); assertEquals(y, sim.pose.y, 1e-6); assertEquals(yaw, sim.pose.yaw, 1e-9);
        assertEquals("asleep", sim.last.collision, "a parked vehicle sleeps");
        // a sleeping vehicle wakes on the first input
        sim.in.driver = true; sim.in.throttle = 1;
        sim.run(2);
        assertTrue(sim.speed() > 3, "did not wake: " + sim.speed() + " - " + sim.last.line(1, 1));
    }

    @Test void startupSelfCheckPassesForEveryGroundVehicle() {
        for (String id : new String[]{"truck", "sodayo", "sodayo_mg", "lav150", "bmp2", "tom6", "plz05", "wheelchair"}) {
            String warn = PhysicsSelfCheck.wheeled(type(id));
            assertNull(warn, warn);
        }
    }

    static org.bukkit.Input input(boolean w, boolean s, boolean a, boolean d, boolean jump) {
        return new org.bukkit.Input() {
            public boolean isForward() { return w; } public boolean isBackward() { return s; } public boolean isLeft() { return a; }
            public boolean isRight() { return d; } public boolean isJump() { return jump; } public boolean isSneak() { return false; } public boolean isSprint() { return false; }
        };
    }
}
