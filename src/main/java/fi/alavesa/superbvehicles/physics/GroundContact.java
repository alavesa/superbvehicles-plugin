package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.render.Pose;
import org.joml.Vector3d;

/**
 * Ground contact answered by the block collider itself, so the ride height always agrees with the collision box.
 * Before 0.4.0 the ride height came from the average of four corner probes: half on a slab, the average lowered the
 * flat collision box INTO the slab, and from inside a block the collider refused every move ("stuck at slabs").
 * Now the box rests on the highest block under it (any point of its footprint, not only the corners), steps are taken
 * by the collider's step-up, and going down a step the box is snapped down so it stays on the ground. The four corner
 * heights only tilt the visible body.
 */
public final class GroundContact {
    private GroundContact() { }

    /** Is there ground within `eps` below the hull (or is it resting / slightly inside something)? */
    public static boolean contact(BlockCollider col, Obb hull, double eps) {
        BlockCollider.Result r = col.sweep(hull, new Vector3d(0, -eps, 0), 0, false);
        return r.hitY || r.depenetration.y > 1e-6;
    }

    /** Vertical move (<= 0, or > 0 when pushed out of a block) that puts the hull on the ground within `max` below;
     *  NaN when there is no ground that close (the vehicle drove off an edge and falls). */
    public static double snapDown(BlockCollider col, Obb hull, double max) {
        BlockCollider.Result r = col.sweep(hull, new Vector3d(0, -max, 0), 0, false);
        return r.hitY || r.depenetration.y > 1e-6 ? r.moved.y : Double.NaN;
    }

    /**
     * Visible body attitude from the ground under the four corners of the footprint (cosmetic: never moves the box).
     * Returns {pitch, roll, contactFraction}; corners with no ground within reach count as level with the body.
     */
    public static double[] attitude(BlockCollider col, Pose pose, Obb hull, double reachUp, double reachDown, double maxTilt) {
        double hl = hull.half.z * 0.85, hw = hull.half.x * 0.85;
        Vector3d f = pose.forwardFlat(), r = pose.rightFlat();
        double[][] cs = {{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};   // FL FR BL BR (right, forward)
        double[] hts = new double[4];
        int touching = 0;
        for (int i = 0; i < 4; i++) {
            double x = pose.x + r.x * cs[i][0] + f.x * cs[i][1], z = pose.z + r.z * cs[i][0] + f.z * cs[i][1];
            double g = col.groundAt(x, pose.y, z, reachUp, reachDown);
            if (Double.isNaN(g)) hts[i] = pose.y; else { hts[i] = Math.min(g, pose.y + reachUp); if (pose.y - g <= 0.35) touching++; }
        }
        double[] ang = WheeledVehicleController.planeAngles(hts[0], hts[1], hts[2], hts[3], hl, hw);
        return new double[]{Pose.clamp(ang[0], -maxTilt, maxTilt), Pose.clamp(ang[1], -maxTilt, maxTilt), touching / 4.0};
    }
}
