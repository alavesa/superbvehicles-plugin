package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.render.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Boats: open water, running up a slab shore, land grace, stranding, getting back into the water. */
class BoatModelTest {
    static final double BED = 60, SURFACE = 63.875;

    /** Sea for z < 16 (bed 60, surface 63.875); a submerged slab ramp at z 16..17 (top 63.5); dry land from z 18 (top 64). */
    static StepTerrain coast() {
        StepTerrain t = new StepTerrain(BED);
        t.sea(-30, -40, 30, 17, SURFACE);
        t.area(-30, 16, 30, 17, 3.5);
        t.area(-30, 18, 30, 300, 4.0);
        return t;
    }

    static final class Boat {
        final BoatModel m; final Pose pose = new Pose(); final PhysicsState s = new PhysicsState();
        final BlockCollider col; final BoatModel.Input in = new BoatModel.Input();
        BoatModel.Report last;
        Boat(StepTerrain t, double z) {
            m = new BoatModel(BoatModel.Params.of(WheeledVehicleControllerTest.type("speedboat")), WheeledVehicleControllerTest.box(0.9, 0.6, 2.4));
            col = new BlockCollider(t);
            pose.x = 0.5; pose.z = z; pose.y = SURFACE - m.params().draft; pose.modelYaw = 180;
            s.engineOn = true; in.driver = true; in.engineOn = true;
        }
        void run(double sec) { for (int i = 0; i < Math.round(sec * 20); i++) last = m.step(pose, s, in, col); }
    }

    @Test void floatsAndReachesSpeedInOpenWater() {
        StepTerrain sea = new StepTerrain(BED).sea(-400, -40, 400, 40, SURFACE);
        Boat b = new Boat(sea, 0.5);
        b.pose.yaw = 90;    // toward -X, along the open sea
        b.in.throttle = 1;
        b.run(8);
        assertTrue(b.s.speed > 0.8 * b.m.params().maxSpeed, "speed " + b.s.speed + " - " + b.last.line(b.s, 1));
        assertEquals(SURFACE - b.m.params().draft, b.pose.y, 0.15, "floats at its draft");
        assertEquals(5, b.last.wet);
    }

    @Test void runsUpTheShoreSlowsAndStrandsAfterTheGrace() {
        Boat b = new Boat(coast(), 0.5);
        b.in.throttle = 1;
        int i = 0;
        while (b.pose.z < 20 && i++ < 20 * 20) b.last = b.m.step(b.pose, b.s, b.in, b.col);
        assertTrue(b.pose.z >= 20, "never got onto the shore: z " + b.pose.z + " - " + b.last.line(b.s, 1));
        assertTrue(b.pose.y >= BED + 4 - 0.01, "climbs the slab ramp onto the land: y " + b.pose.y);
        double speedOnLand = b.s.speed;
        b.run(1.0);
        assertTrue(b.s.speed < speedOnLand * 0.6, "dry land slows it: " + speedOnLand + " -> " + b.s.speed);
        b.run(b.m.params().landGrace + 1.0);
        assertTrue(b.last.stranded, "stranded after the grace: " + b.last.line(b.s, 1));
        double z = b.pose.z;
        b.run(3);
        assertEquals(z, b.pose.z, 1e-6, "a stranded boat does not drive on land");
        b.in.throttle = -1;
        b.run(3);
        assertEquals(z, b.pose.z, 1e-6, "not even backwards");
    }

    @Test void reversingInTimeGetsBackIntoTheWater() {
        Boat b = new Boat(coast(), 8);
        b.in.throttle = 0.6;
        int i = 0;
        while (b.last == null || b.last.wet > 0) { b.last = b.m.step(b.pose, b.s, b.in, b.col); if (++i > 20 * 20) fail("never reached dry land"); }
        b.in.throttle = -1;                     // straight away: back into the water within the grace
        b.run(b.m.params().landGrace + 2);
        assertTrue(b.last.wet > 0, "did not get back into the water: z " + b.pose.z + " - " + b.last.line(b.s, 1));
        assertFalse(b.last.stranded);
        b.run(2);
        assertTrue(b.s.speed < -1, "drives in reverse once afloat: " + b.s.speed);
    }
}
