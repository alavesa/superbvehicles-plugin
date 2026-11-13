package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.Particle;
import org.joml.Vector3d;

/**
 * Shared physics plumbing for the class models: velocity (blocks/s) integrated through the block collider in
 * substeps (the per-tick conversion happens only here and in {@link WheeledVehicleController}), lost speed becomes
 * crash damage, landings become fall damage, the engine key, and the chunk-loading speed limit. Each class model
 * implements {@link #tick}, {@link #animationState} and {@link #debugLine}.
 */
public abstract class VehiclePhysics {
    protected final Vehicle v;
    protected final PhysicsState s;
    protected final VehicleType t;
    protected final SuperbVehiclesPlugin plugin;
    private double chunkFactor = 1;

    protected VehiclePhysics(Vehicle v) { this.v = v; this.s = v.phys; this.t = v.type; this.plugin = v.plugin; }

    public abstract void tick(Controls c, BlockCollider col);
    public abstract String animationState();
    /** One line for /sbv debug physics. */
    public abstract String debugLine();
    /** Called when the rig changed (hitbox / wheel positions re-read). */
    public void rigBound() { }

    /** Gravity in blocks/s^2. Older config files hold 0.08 (blocks/tick^2): converted. */
    protected double gravity() { double g = plugin.cfg("physics.gravity", 32.0); return g < 1 ? g * Units.TPS * Units.TPS : g; }
    protected double cfg(String key, double def) { return plugin.cfg(key, def); }

    /** Engine key (swap hands by default); auto-start on throttle when configured. */
    protected void engineControl(Controls c, String cls, boolean autoStart) {
        String key = plugin.getConfig().getString("controls." + cls + ".engine", "swap_hands");
        if (c.present && c.pressed(key)) {
            if (s.engineOn) { s.engineOn = false; v.playSound(t.sound("stop", "minecraft:block.piston.contract"), 0.8f, 0.7f); message("engine-off"); }
            else if (v.engineAvailable()) { s.engineOn = true; v.playSound(t.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f); message("engine-on"); }
            else if (v.outOfFuel()) message("fuel-empty");
        }
        if (!s.engineOn && c.present && autoStart && v.engineAvailable() && (c.forward || c.backward)) { s.engineOn = true; v.playSound(t.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f); }
        if (s.engineOn && !v.engineAvailable()) s.engineOn = false;
        if (s.engineOn && !c.present && plugin.flag("physics.engine-off-when-empty", true)) { if (++s.idleTicks > Units.TPS * cfg("physics.idle-stop-seconds", 8)) s.engineOn = false; } else s.idleTicks = 0;
    }

    protected void message(String key) { org.bukkit.entity.Player d = v.driver(); if (d != null) plugin.messages().actionBar(d, key); }

    /**
     * 0.25..1: while the vehicle heads into chunks that are not loaded (or still generating), its top speed is cut so
     * players cannot outrun the server. Looks 2 s and 4 s ahead along the velocity.
     */
    protected double chunkSpeedFactor(BlockCollider col) {
        if (!plugin.flag("physics.speed-limit-by-chunk-loading", true)) return 1;
        double sp = s.vel.length();
        double target = 1;
        if (sp > 2) {
            for (double ahead : new double[]{2, 4}) {
                double x = v.pose.x + s.vel.x * ahead, z = v.pose.z + s.vel.z * ahead;
                if (!col.loaded(x, z)) { target = cfg("physics.unloaded-speed-factor", 0.25); break; }
            }
        }
        chunkFactor = Pose.approach(chunkFactor, target, target < chunkFactor ? 0.3 : 0.05);
        return chunkFactor;
    }

    /** Integrate s.vel (blocks/s) for one tick through the collider. Blocked axes lose their velocity. */
    protected BlockCollider.Result integrate(BlockCollider col, boolean canStep) {
        Vector3d delta = new Vector3d(Units.perTick(s.vel.x), Units.perTick(s.vel.y), Units.perTick(s.vel.z));
        double capBps = cfg("physics.max-speed-cap", 80.0);
        if (capBps < 10) capBps *= Units.TPS;                              // 0.1.0 configs held blocks/tick
        double cap = Units.perTick(capBps);                                 // absolute safety
        if (delta.length() > cap) { delta.normalize(cap); s.activeClamp = "global speed cap"; }
        int sub = Math.max(1, Math.min(8, (int) Math.ceil(delta.length() / 0.5)));
        Vector3d step = new Vector3d(delta).div(sub);
        BlockCollider.Result total = new BlockCollider.Result();
        total.hitX0 = total.hitY0 = total.hitZ0 = Double.NaN;
        for (int i = 0; i < sub; i++) {
            BlockCollider.Result r = col.sweep(v.hull(), step, canStep ? t.stepHeight * t.scale : 0, canStep);
            v.pose.x += r.moved.x; v.pose.y += r.moved.y; v.pose.z += r.moved.z;
            total.moved.add(r.moved);
            total.depenetration.add(r.depenetration);
            total.hitX |= r.hitX; total.hitY |= r.hitY; total.hitZ |= r.hitZ; total.stepped |= r.stepped;
            total.blockedSpeed += r.blockedSpeed;
            if (r.hitX) { s.vel.x = 0; step.x = 0; }
            if (r.hitZ) { s.vel.z = 0; step.z = 0; }
            if (r.hitY) { if (step.y < 0) s.onGround = true; s.vel.y = 0; step.y = 0; }
        }
        afterMove(Units.perSecond(total.blockedSpeed));
        s.distance += Math.hypot(total.moved.x, total.moved.z);
        return total;
    }

    /** Crash bookkeeping after any move: lost speed (blocks/s) above the threshold is a crash. */
    protected void afterMove(double lostBps) {
        if (lostBps >= cfg("physics.crash.min-speed", 6.0) && s.crashCooldown <= 0) crash(lostBps);
        s.lastImpact = lostBps;
        if (s.crashCooldown > 0) s.crashCooldown--;
    }

    /** Lost speed (blocks/s) in one tick against blocks: damage and effects. */
    protected void crash(double lostBps) {
        s.crashCooldown = 8;
        double vdmg = lostBps * cfg("physics.crash.vehicle-damage", 2.5) * (lostBps > 20 ? 1.5 : 1.0);
        Vector3d c = v.hull().center;
        v.world.spawnParticle(lostBps > 15 ? Particle.EXPLOSION : Particle.CRIT, c.x, c.y, c.z, lostBps > 15 ? 1 : 12, 0.5, 0.3, 0.5, 0.1);
        v.world.spawnParticle(Particle.LARGE_SMOKE, c.x, c.y, c.z, 8, 0.5, 0.3, 0.5, 0.02);
        v.playSound(t.sound("crash", lostBps > 15 ? "minecraft:entity.generic.explode" : "minecraft:block.anvil.land"), 0.9f, lostBps > 15 ? 1.3f : 0.6f);
        plugin.damage().crash(v, vdmg, lostBps);
    }

    /** Vertical impact when touching down (blocks/s): fall damage above the threshold. */
    protected void landing(double downBps) {
        double th = cfg("damage.fall-threshold", 8.0);
        if (downBps <= th) return;
        v.playSound("minecraft:block.anvil.land", 0.9f, 0.5f);
        plugin.damage().crash(v, (downBps - th) * cfg("damage.fall-damage", 3.0), downBps);
    }

    protected static double smooth(double cur, double target, double rate) { return Pose.approach(cur, target, rate); }

    protected boolean inWater(BlockCollider col) { Obb h = v.hull(); return col.isWater(h.center.x, h.center.y - h.half.y + 0.2, h.center.z); }

    /** Body pitch/roll from four corner ground heights (deg, nose up / right side down positive). */
    protected static double[] planeAngles(double fl, double fr, double bl, double br, double halfLength, double halfWidth) {
        return WheeledVehicleController.planeAngles(fl, fr, bl, br, halfLength, halfWidth);
    }

    /** Ground follow for non-wheeled classes resting on land (boats beached, aircraft on skids/wheels). */
    protected void followGround(BlockCollider col, double rate, double maxTilt) {
        Obb h = v.hull();
        double hl = h.half.z * 0.85, hw = h.half.x * 0.85;
        Vector3d f = v.pose.forwardFlat(), r = v.pose.rightFlat();
        double[][] corners = {{-hw, hl}, {hw, hl}, {-hw, -hl}, {hw, -hl}};
        double[] hts = new double[4];
        int touching = 0;
        double probeUp = t.stepHeight * t.scale + 0.3;
        for (int i = 0; i < 4; i++) {
            Vector3d p = new Vector3d(v.pose.x, v.pose.y, v.pose.z).add(new Vector3d(r).mul(corners[i][0])).add(new Vector3d(f).mul(corners[i][1]));
            double g = col.groundAt(p.x, v.pose.y, p.z, probeUp, 3.0);
            if (Double.isNaN(g)) hts[i] = v.pose.y - 3.0; else { hts[i] = g; if (v.pose.y - g <= 0.35) touching++; }
        }
        s.wheelContact = touching / 4.0;
        double[] ang = planeAngles(hts[0], hts[1], hts[2], hts[3], hl, hw);
        s.bodyPitchTarget = Pose.clamp(ang[0], -maxTilt, maxTilt);
        s.bodyRollTarget = Pose.clamp(ang[1], -maxTilt, maxTilt);
        s.onGround = touching >= 2 && s.vel.y <= 0.5;
        if (s.onGround) {
            double target = Math.max((hts[0] + hts[1] + hts[2] + hts[3]) / 4, Math.max(Math.max(hts[0], hts[1]), Math.max(hts[2], hts[3])) - 0.9);
            v.pose.y = target > v.pose.y ? Math.min(target, v.pose.y + 0.3) : Pose.approach(v.pose.y, target, rate);
            if (Math.abs(v.pose.y - target) < 0.002) v.pose.y = target;
            s.vel.y = Math.min(0, s.vel.y);
        }
        s.groundClearance = v.pose.y - (hts[0] + hts[1] + hts[2] + hts[3]) / 4;
    }
}
