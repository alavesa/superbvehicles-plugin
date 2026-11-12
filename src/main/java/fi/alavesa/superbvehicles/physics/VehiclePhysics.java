package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.joml.Vector3d;

import java.util.List;

/**
 * Shared physics plumbing: the hull is moved through the block collider (substepped and speed-capped), lost speed
 * becomes crash damage, gravity, ground probing, soft-block breaking and the common controls (engine toggle,
 * throttle smoothing). Each class model implements {@link #tick} and {@link #animationState}.
 */
public abstract class VehiclePhysics {
    protected final Vehicle v;
    protected final PhysicsState s;
    protected final VehicleType t;
    protected final SuperbVehiclesPlugin plugin;

    protected VehiclePhysics(Vehicle v) { this.v = v; this.s = v.phys; this.t = v.type; this.plugin = v.plugin; }

    public abstract void tick(Controls c, BlockCollider col);
    public abstract String animationState();
    /** Called when the rig changed (hitbox / wheel positions re-read). */
    public void rigBound() { }

    protected double gravity() { return plugin.cfg("physics.gravity", 0.08); }
    protected double cfg(String key, double def) { return plugin.cfg(key, def); }

    /** Engine on/off key (swap hands by default); auto-start on throttle when configured. */
    protected void engineControl(Controls c, String cls) {
        String key = plugin.getConfig().getString("controls." + cls + ".engine", "swap_hands");
        if (c.present && c.pressed(key)) {
            if (s.engineOn) { s.engineOn = false; v.playSound(t.sound("stop", "minecraft:block.piston.contract"), 0.8f, 0.7f); message("engine-off"); }
            else if (v.engineAvailable()) { s.engineOn = true; v.playSound(t.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f); message("engine-on"); }
        }
        if (!s.engineOn && c.present && v.engineAvailable() && plugin.flag("physics.auto-start", true) && (c.forward || c.backward)) { s.engineOn = true; v.playSound(t.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f); }
        if (s.engineOn && !v.engineAvailable()) s.engineOn = false;
        if (s.engineOn && !c.present && plugin.flag("physics.engine-off-when-empty", true)) { if (++s.idleTicks > 20 * cfg("physics.idle-stop-seconds", 8)) s.engineOn = false; } else s.idleTicks = 0;
    }

    protected void message(String key) { org.bukkit.entity.Player d = v.driver(); if (d != null) plugin.messages().actionBar(d, key); }

    /** Move the hull by delta (blocks), handling block collisions and crashes. Returns the collider result. */
    protected BlockCollider.Result move(BlockCollider col, Vector3d delta, boolean canStep) {
        double cap = cfg("physics.max-speed-cap", 4.0);
        if (delta.length() > cap) delta.normalize(cap);
        int sub = Math.max(1, (int) cfg("physics.substeps", 2));
        if (delta.length() < 0.6) sub = 1;
        Vector3d step = new Vector3d(delta).div(sub);
        BlockCollider.Result total = new BlockCollider.Result();
        for (int i = 0; i < sub; i++) {
            BlockCollider.Result r = col.sweep(v.hull(), step, t.stepHeight * t.scale, canStep);
            v.pose.x += r.moved.x; v.pose.y += r.moved.y; v.pose.z += r.moved.z;
            total.moved.add(r.moved);
            total.hitX |= r.hitX; total.hitY |= r.hitY; total.hitZ |= r.hitZ; total.stepped |= r.stepped;
            total.blockedSpeed += r.blockedSpeed;
            if (r.lastHit != null) total.lastHit = r.lastHit;
            if (r.hitHorizontal()) { Vector3d rest = new Vector3d(step).mul(sub - i - 1); if (r.hitX) { s.vel.x = 0; rest.x = 0; } if (r.hitZ) { s.vel.z = 0; rest.z = 0; } if (rest.lengthSquared() > 0 && i + 1 < sub) { step.set(rest).div(sub - i - 1); } else break; }
            if (r.hitY) { if (step.y < 0) s.onGround = true; s.vel.y = 0; step.y = 0; }
        }
        if (total.blockedSpeed * 20 >= cfg("physics.crash.min-speed", 6.0) && s.crashCooldown <= 0) crash(total.blockedSpeed * 20, total.lastHit);
        s.lastImpact = total.blockedSpeed * 20;
        if (s.crashCooldown > 0) s.crashCooldown--;
        s.distance += Math.hypot(total.moved.x, total.moved.z);
        return total;
    }

    /** Lost speed (blocks/s) in one tick against blocks: damage, effects, soft blocks broken. */
    protected void crash(double lostBps, Block block) {
        s.crashCooldown = 8;
        if (block != null && plugin.flag("physics.block-breaking", false) && lostBps > 8) {
            List<String> soft = plugin.getConfig().getStringList("physics.breakable-blocks");
            if (BlockCollider.soft(block.getType(), soft)) { block.breakNaturally(); s.crashCooldown = 0; return; }
        }
        double vdmg = lostBps * cfg("physics.crash.vehicle-damage", 2.5) * (lostBps > 20 ? 1.5 : 1.0);
        Vector3d c = v.hull().center;
        v.world.spawnParticle(lostBps > 15 ? Particle.EXPLOSION : Particle.CRIT, c.x, c.y, c.z, lostBps > 15 ? 1 : 12, 0.5, 0.3, 0.5, 0.1);
        v.world.spawnParticle(Particle.LARGE_SMOKE, c.x, c.y, c.z, 8, 0.5, 0.3, 0.5, 0.02);
        v.playSound(t.sound("crash", lostBps > 15 ? "minecraft:entity.generic.explode" : "minecraft:block.anvil.land"), 0.9f, lostBps > 15 ? 1.3f : 0.6f);
        plugin.damage().crash(v, vdmg, lostBps);
    }

    /** Vertical impact when touching down: fall damage above the threshold. */
    protected void landing(double downBps) {
        double th = cfg("damage.fall-threshold", 8.0);
        if (downBps <= th) return;
        double dmg = (downBps - th) * cfg("damage.fall-damage", 3.0);
        v.playSound("minecraft:block.anvil.land", 0.9f, 0.5f);
        plugin.damage().crash(v, dmg, downBps);
    }

    /** Smooth an input toward target at `rate` per tick. */
    protected static double smooth(double cur, double target, double rate) { return Pose.approach(cur, target, rate); }

    /** Height of the ground under a world point (searching up to `up` above and `down` below), NaN = none. */
    protected double ground(BlockCollider col, Vector3d p, double up, double down) { return col.groundAt(p.x, p.y, p.z, up, down); }

    /** Water check at the hull centre bottom. */
    protected boolean inWater(BlockCollider col) { Obb h = v.hull(); return col.isWater(h.center.x, h.center.y - h.half.y + 0.2, h.center.z); }

    /** Body pitch/roll from four corner ground heights (deg, nose up / right down positive). */
    protected static double[] planeAngles(double fl, double fr, double bl, double br, double halfLength, double halfWidth) {
        double pitch = Math.toDegrees(Math.atan2((fl + fr) / 2 - (bl + br) / 2, 2 * halfLength));
        double roll = Math.toDegrees(Math.atan2((fl + bl) / 2 - (fr + br) / 2, 2 * halfWidth));
        return new double[]{pitch, roll};
    }

    /** Applies ground follow: probes under the four hull corners (or wheel locators) and settles height/pitch/roll. */
    protected void followGround(BlockCollider col, double rate, double maxTilt) {
        Obb h = v.hull();
        double hl = h.half.z * 0.85, hw = h.half.x * 0.85;
        Vector3d f = v.pose.forwardFlat(), r = v.pose.rightFlat();
        double[][] corners = {{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};   // FL FR BL BR (right, forward)
        double[] hts = new double[4];
        int touching = 0;
        double bottom = v.pose.y;   // model origin = ground contact line
        double probeUp = t.stepHeight * t.scale + 0.6, probeDown = 3.0;
        for (int i = 0; i < 4; i++) {
            Vector3d p = new Vector3d(v.pose.x, bottom, v.pose.z).add(new Vector3d(r).mul(corners[i][0])).add(new Vector3d(f).mul(corners[i][1]));
            double g = col.groundAt(p.x, bottom, p.z, probeUp, probeDown);
            if (Double.isNaN(g)) hts[i] = bottom - probeDown; else { hts[i] = g; if (g >= bottom - 0.35) touching++; }
        }
        s.wheelContact = touching / 4.0;
        double avg = (hts[0] + hts[1] + hts[2] + hts[3]) / 4;
        double[] ang = planeAngles(hts[0], hts[1], hts[2], hts[3], hl, hw);
        s.bodyPitchTarget = Pose.clamp(ang[0], -maxTilt, maxTilt);
        s.bodyRollTarget = Pose.clamp(ang[1], -maxTilt, maxTilt);
        s.onGround = touching >= 2;
        if (s.onGround) {
            // the body sits on the average contact height; a sudden drop is handled by gravity
            double target = Math.max(avg, Math.max(Math.max(hts[0], hts[1]), Math.max(hts[2], hts[3])) - 0.9);
            if (target > v.pose.y) v.pose.y = Pose.approach(v.pose.y, target, 0.5);
            else if (v.pose.y - target < 0.35) v.pose.y = Pose.approach(v.pose.y, target, rate);
            else s.onGround = false;
        }
        s.groundClearance = v.pose.y - avg;
    }
}
