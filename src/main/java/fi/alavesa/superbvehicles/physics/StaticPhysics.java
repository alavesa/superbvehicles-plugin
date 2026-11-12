package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.joml.Vector3d;

/** Emplacements: settle onto the ground once, never move. */
public final class StaticPhysics extends VehiclePhysics {
    private int settleTicks = 40;
    public StaticPhysics(Vehicle v) { super(v); }

    @Override public void tick(Controls c, BlockCollider col) {
        s.engineOn = false;
        if (settleTicks > 0) {
            settleTicks--;
            double g = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.5, 4);
            if (!Double.isNaN(g) && Math.abs(g - v.pose.y) > 0.01) { v.pose.y = g; }
            else if (Double.isNaN(g)) { s.vel.y = Math.max(-2, s.vel.y - gravity()); move(col, new Vector3d(0, s.vel.y, 0), false); if (s.onGround) s.vel.y = 0; }
        }
        s.onGround = true;
    }

    @Override public String animationState() { return v.wreck ? "wreck" : "idle"; }
}
