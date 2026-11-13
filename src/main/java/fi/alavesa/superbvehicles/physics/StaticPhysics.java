package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;

import java.util.Locale;

/** Emplacements: settle onto the ground once (depenetrating if placed into it), never move. */
public final class StaticPhysics extends VehiclePhysics {
    private int settleTicks = 40;
    public StaticPhysics(Vehicle v) { super(v); }

    @Override public void tick(Controls c, BlockCollider col) {
        s.engineOn = false;
        if (settleTicks > 0) {
            settleTicks--;
            double g = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.5, 4);
            if (!Double.isNaN(g)) { v.pose.y = g; s.vel.set(0, 0, 0); }
            else { s.vel.set(0, Math.max(-40, s.vel.y - gravity() * Units.DT), 0); integrate(col, false); if (s.onGround) s.vel.y = 0; }
        }
        s.onGround = true;
    }

    @Override public String debugLine() { return String.format(Locale.ROOT, "static emplacement at %.2f %.2f %.2f, settling %d", v.pose.x, v.pose.y, v.pose.z, settleTicks); }
    @Override public String animationState() { return v.wreck ? "wreck" : "idle"; }
}
