package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The default "simple" flight model: keys ask for a velocity, the heading follows the view, no keys = hover. */
class HelicopterSimpleTest {
    static final double FLOOR = 0;

    static final class Sim {
        final HelicopterModel m; final Pose pose = new Pose(); final PhysicsState s = new PhysicsState();
        final BlockCollider col = new BlockCollider(Terrain.flat(FLOOR));
        final HelicopterModel.Input in = new HelicopterModel.Input();
        HelicopterModel.Report last;
        Sim(String id, double y) {
            m = new HelicopterModel(HelicopterModel.Params.of(WheeledVehicleControllerTest.type(id)), WheeledVehicleControllerTest.box(1.0, 1.0, 2.5));
            pose.y = y; pose.modelYaw = 180;
            s.engineOn = true; s.spool = 1; s.onGround = y <= FLOOR;
            in.driver = true; in.engineOn = true;
        }
        HelicopterModel.Params p() { return m.params(); }
        double horizontal() { return Math.hypot(s.vel.x, s.vel.z); }
        void run(double sec) { for (int i = 0; i < Math.round(sec * 20); i++) last = m.step(pose, s, in, col); }
        String why() { return last == null ? "" : last.line(s); }
    }

    @Test void shippedHelicoptersUseTheSimpleModel() {
        for (String id : new String[]{"ah6", "mi28"}) assertTrue(HelicopterModel.Params.of(WheeledVehicleControllerTest.type(id)).simple, id);
    }

    @Test void noKeysHoversInPlace() {
        Sim h = new Sim("ah6", 100);
        h.in.lookYaw = 0;
        h.run(10);
        assertEquals(0, h.horizontal(), 0.05, h.why());
        assertEquals(100, h.pose.y, 0.3, "holds altitude: " + h.why());
    }

    @Test void forwardReachesTopSpeedQuicklyAndStopsWhenReleased() {
        for (String id : new String[]{"ah6", "mi28"}) {
            Sim h = new Sim(id, 100);
            h.in.forward = 1;
            int ticks = 0; double peak = 0;
            while (h.horizontal() < 0.9 * h.p().maxSpeed && ticks < 30 * 20) { h.run(0.05); ticks++; }
            assertTrue(ticks / 20.0 <= h.p().accelTime + 1, id + " took " + ticks / 20.0 + " s to 90% speed");
            for (int i = 0; i < 20 * 20; i++) { h.run(0.05); peak = Math.max(peak, h.horizontal()); }
            assertTrue(peak <= h.p().maxSpeed * 1.001, id + " over max-speed: " + peak);
            assertEquals(100, h.pose.y, 0.5, id + " level flight keeps altitude");
            Vector3d f = h.pose.forwardFlat();
            assertTrue(h.s.vel.x * f.x + h.s.vel.z * f.z > 0.9 * h.p().maxSpeed, id + " flies nose first");
            h.in.forward = 0;
            ticks = 0;
            while (h.horizontal() > 0.5 && ticks < 30 * 20) { h.run(0.05); ticks++; }
            assertTrue(ticks / 20.0 <= h.p().brakeTime + 1, id + " took " + ticks / 20.0 + " s to stop");
        }
    }

    @Test void turnsToFaceTheViewWithoutOvershooting() {
        Sim h = new Sim("ah6", 100);
        h.pose.yaw = 0; h.in.lookYaw = 90;
        double max = 0;
        for (int i = 0; i < 3 * 20; i++) { h.run(0.05); max = Math.max(max, h.pose.yaw); }
        assertEquals(90, h.pose.yaw, 2, h.why());
        assertTrue(max <= 90.5, "overshoot " + max);
        // across the +-180 seam the short way
        h.in.lookYaw = -170;   // from 90: 100 degrees the other way round
        h.run(3);
        assertEquals(-170, Pose.wrap(h.pose.yaw), 2);
    }

    @Test void strafesSidewaysAndBacksUpSlower() {
        Sim h = new Sim("ah6", 100);
        h.in.strafe = 1;
        h.run(4);
        Vector3d r = h.pose.rightFlat();
        double side = h.s.vel.x * r.x + h.s.vel.z * r.z;
        assertEquals(h.p().maxSpeed * h.p().strafeFactor, side, 1.0, "strafe right: " + h.why());
        h.in.strafe = 0; h.in.forward = -1;
        h.run(5);
        Vector3d f = h.pose.forwardFlat();
        assertEquals(-h.p().maxSpeed * h.p().reverseFactor, h.s.vel.x * f.x + h.s.vel.z * f.z, 1.0, "reverse: " + h.why());
    }

    @Test void takesOffClimbsDescendsAndLandsSoftly() {
        Sim h = new Sim("ah6", FLOOR);
        h.in.vertical = 1;
        h.run(5);
        assertFalse(h.s.onGround, "took off: " + h.why());
        assertTrue(h.pose.y > FLOOR + h.p().climbRate * 3, "climbed: y " + h.pose.y);
        assertTrue(h.s.vel.y <= h.p().climbRate + 1e-9);
        h.in.vertical = -1;
        double worst = 0;
        for (int i = 0; i < 30 * 20 && !h.s.onGround; i++) { h.run(0.05); if (h.s.landingSpeed > 0) worst = h.s.landingSpeed; }
        assertTrue(h.s.onGround, "landed: " + h.why());
        assertTrue(worst <= h.p().descentRate + 0.01, "touchdown at " + worst + " b/s");
        h.run(2);
        assertEquals(FLOOR, h.pose.y, 0.15);
        assertEquals(0, h.horizontal(), 0.05);
    }

    @Test void standsStillOnTheGroundWhileTheViewMoves() {
        Sim h = new Sim("ah6", FLOOR);
        h.pose.yaw = 0; h.in.lookYaw = 120;
        h.run(3);
        assertEquals(0, h.pose.yaw, 1e-9, "does not spin on the pad when the pilot looks around");
    }

    @Test void engineOffInTheAirSinksAtTheAutorotationRate() {
        Sim h = new Sim("ah6", 120);
        h.in.forward = 1; h.run(3);
        h.s.engineOn = false; h.in.engineOn = false; h.in.forward = 0;
        for (int i = 0; i < 6 * 20; i++) { h.run(0.05); assertTrue(h.s.vel.y >= -h.p().clampDescent - 1e-9, "vy " + h.s.vel.y); }
        assertTrue(h.s.vel.y < -1, "sinks: " + h.why());
    }
}
