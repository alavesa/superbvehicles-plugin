package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.render.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** "Vehicles get stuck at slabs and blocks": every ground type, three hull sizes, slab and full block, three angles,
 *  two throttles, from right at the step or with a run-up - all must end up on top. */
class StepClimbSweepTest {
    @Test void everyGroundVehicleClimbsSlabsAndBlocksFromAnyAngle() {
        String[] ids = System.getProperty("ids", "sodayo,truck,lav150,bmp2,plz05,tom6,wheelchair,sodayo_mg").split(",");
        double[][] hulls = {{1.0, 0.8, 2.2}, {1.4, 1.4, 3.6}, {0.4, 0.6, 0.5}};
        int fails = 0, total = 0;
        for (String id : ids) for (double[] h : hulls) for (double step : new double[]{0.5, 1.0}) for (double thr : new double[]{0.4, 1}) for (double yaw : new double[]{0, -30, -60}) for (double gap : new double[]{0.05, 3}) {
            StepTerrain t = new StepTerrain(64).area(-60, 10, 60, 400, step);
            WheeledVehicleController ctl;
            try { ctl = new WheeledVehicleController(WheeledVehicleController.Params.of(WheeledVehicleControllerTest.type(id)), WheeledVehicleControllerTest.box(h[0], h[1], h[2])); }
            catch (Exception e) { System.out.println("skip " + id + " " + e); break; }
            Pose p = new Pose(); PhysicsState s = new PhysicsState(); BlockCollider col = new BlockCollider(t);
            WheeledVehicleController.Input in = new WheeledVehicleController.Input();
            p.modelYaw = 180; p.yaw = yaw; p.y = 64;
            // place the front corner `gap` blocks before the step
            double fwdReach = Math.abs(Math.cos(Math.toRadians(yaw))) * h[2] + Math.abs(Math.sin(Math.toRadians(yaw))) * h[0];
            p.x = 0.5; p.z = 10 - gap - fwdReach;
            in.driver = true; in.engineOn = true; in.throttle = thr;
            WheeledVehicleController.Report r = null;
            double maxY = 64; int ticks = 0;
            for (; ticks < 20 * 8; ticks++) { r = ctl.step(p, s, in, col); maxY = Math.max(maxY, p.y); if (p.y >= 64 + step - 0.01 && p.z > 10 + 0.5) break; }
            total++;
            boolean ok = p.y >= 64 + step - 0.01;
            if (!ok) { fails++; System.out.printf("STUCK %s hull %.1fx%.1fx%.1f step %.1f thr %.2f yaw %.0f gap %.2f -> z %.2f y %.2f v %.2f | %s%n", id, h[0], h[1], h[2], step, thr, yaw, gap, p.z, p.y, s.speed, r == null ? "" : r.line(1, 1)); }
        }
        assertEquals(0, fails, fails + " of " + total + " runs stuck (see the STUCK lines)");
    }
}
