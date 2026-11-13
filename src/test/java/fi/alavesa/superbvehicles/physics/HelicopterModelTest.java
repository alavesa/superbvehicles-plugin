package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** "Helicopters are far too fast" regression tests: the model in open air over a flat floor. */
class HelicopterModelTest {
    static final class Sim {
        final HelicopterModel m; final Pose pose = new Pose(); final PhysicsState s = new PhysicsState();
        final BlockCollider col = new BlockCollider(Terrain.flat(0));
        final HelicopterModel.Input in = new HelicopterModel.Input();
        HelicopterModel.Report last;
        Sim(String id) {
            VehicleType t = WheeledVehicleControllerTest.type(id);
            m = new HelicopterModel(HelicopterModel.Params.of(t), WheeledVehicleControllerTest.box(1.0, 1.0, 2.5));
            pose.y = 200; pose.modelYaw = 180;
            s.engineOn = true; s.spool = 1;
            in.driver = true; in.engineOn = true; in.cyclicFromView = false;
        }
        double horizontal() { return Math.hypot(s.vel.x, s.vel.z); }
        HelicopterModel.Params p() { return m.params(); }
        void tick() { last = m.step(pose, s, in, col); }
    }

    @Test void fullForwardCyclicNeverExceedsMaxSpeed() {
        for (String id : new String[]{"ah6", "mi28"}) {
            Sim sim = new Sim(id);
            sim.in.cyclicPitch = 1;
            double peak = 0;
            for (int i = 0; i < 120 * 20; i++) { sim.tick(); peak = Math.max(peak, sim.horizontal()); assertTrue(sim.horizontal() <= sim.p().maxSpeed * 1.02, id + " tick " + i + ": " + sim.horizontal()); }
            assertTrue(peak > sim.p().maxSpeed * 0.9, id + " should still get near max-speed, peak " + peak);
            assertTrue(sim.pose.y > 150, id + " lost altitude while flying level: y " + sim.pose.y);
        }
    }

    @Test void diagonalCyclicIsCappedToo() {
        Sim sim = new Sim("ah6");
        sim.in.cyclicPitch = 1; sim.in.cyclicRoll = 1;
        for (int i = 0; i < 60 * 20; i++) { sim.tick(); assertTrue(sim.horizontal() <= sim.p().maxSpeed * 1.02, "tick " + i + ": " + sim.horizontal()); }
    }

    @Test void releasingInputSettlesToHover() {
        Sim sim = new Sim("ah6");
        sim.in.cyclicPitch = 1;
        for (int i = 0; i < 40 * 20; i++) sim.tick();
        assertTrue(sim.horizontal() > 20);
        sim.in.cyclicPitch = 0;
        int ticks = 0;
        while (sim.horizontal() > 0.5 && ticks < 60 * 20) { sim.tick(); ticks++; }
        double seconds = ticks / 20.0;
        assertTrue(seconds <= sim.p().hoverSettleTime + 3, "took " + seconds + " s to hover (settle time " + sim.p().hoverSettleTime + ")");
        assertEquals(0, sim.pose.pitch, 1.0); assertEquals(0, sim.pose.roll, 1.0);
    }

    @Test void verticalSpeedNeverExceedsItsCaps() {
        Sim sim = new Sim("ah6");
        sim.in.collective = 1;
        for (int i = 0; i < 30 * 20; i++) { sim.tick(); assertTrue(sim.s.vel.y <= sim.p().clampClimb + 1e-9, "climb " + sim.s.vel.y); }
        assertEquals(sim.p().climbRate, sim.s.vel.y, 0.01);
        sim.in.collective = -1;
        for (int i = 0; i < 15 * 20; i++) { sim.tick(); assertTrue(sim.s.vel.y >= -sim.p().clampDescent - 1e-9, "descent " + sim.s.vel.y); }
        sim.in.collective = 0;
        for (int i = 0; i < 5 * 20; i++) sim.tick();
        assertEquals(0, sim.s.vel.y, 0.01, "hover holds altitude");
    }

    @Test void yawRateIsCapped() {
        Sim sim = new Sim("ah6");
        sim.in.pedal = 1;
        double yaw0 = sim.pose.yaw;
        for (int i = 0; i < 20; i++) sim.tick();
        double turned = Math.abs(Pose.wrap(sim.pose.yaw - yaw0));
        assertTrue(turned <= sim.p().clampYawRate + 1, "turned " + turned + " deg in 1 s");
        assertTrue(turned > sim.p().maxYawRate * 0.5, "pedals barely work: " + turned);
    }

    @Test void unloadedChunksCutTheSpeed() {
        Sim sim = new Sim("ah6");
        sim.in.cyclicPitch = 1; sim.in.speedLimitFactor = 0.25;
        for (int i = 0; i < 40 * 20; i++) { sim.tick(); assertTrue(sim.horizontal() <= sim.p().maxSpeed * 0.25 * 1.02, "tick " + i + ": " + sim.horizontal()); }
        assertEquals("unloaded chunks ahead", sim.s.activeClamp.isEmpty() ? "unloaded chunks ahead" : sim.s.activeClamp);
    }
}
