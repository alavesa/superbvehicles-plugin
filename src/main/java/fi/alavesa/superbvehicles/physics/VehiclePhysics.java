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
        double vxBefore = s.vel.x, vzBefore = s.vel.z;
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
        afterMove(Math.hypot(total.hitX ? vxBefore : 0, total.hitZ ? vzBefore : 0));   // the speed the collision took away
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

    /**
     * Ground contact for boats on land and aircraft on the ground, answered by the collider (see GroundContact): the
     * box rests on the highest block under it, is snapped down a step when it was on the ground, and the four corner
     * heights only tilt the visible body. Returns true when on the ground.
     */
    protected boolean followGround(BlockCollider col, double stepDown, double maxTilt) {
        boolean wasGround = s.onGround;
        double yBefore = v.pose.y;
        boolean on;
        if (wasGround && s.vel.y <= 0) {
            double dy = GroundContact.snapDown(col, v.hull(), Math.max(0.06, stepDown));
            on = !Double.isNaN(dy);
            if (on) v.pose.y += dy;
        } else on = s.vel.y <= 0.5 && GroundContact.contact(col, v.hull(), 0.06);
        s.onGround = on;
        if (on) s.vel.y = 0;
        double jump = v.pose.y - yBefore;
        if (wasGround && on && Math.abs(jump) > 0.04 && Math.abs(jump) <= stepDown + 0.1) s.visualYOffset -= jump;
        double[] att = GroundContact.attitude(col, v.pose, v.hull(), t.stepHeight * t.scale + 0.1, 3.0, maxTilt);
        s.bodyPitchTarget = att[0]; s.bodyRollTarget = att[1]; s.wheelContact = att[2];
        return on;
    }

    // ------------------------------------------------------------------ ramming (0.5.0)

    private final java.util.Map<java.util.UUID, Integer> ramCooldown = new java.util.HashMap<>();

    /** Closing speed (blocks/s) of a velocity toward a point: the component along the direction to it, >= 0. */
    public static double closingSpeed(Vector3d vel, Vector3d from, Vector3d to) {
        Vector3d d = new Vector3d(to).sub(from); d.y = 0;
        if (d.lengthSquared() < 1e-9) return vel.length();
        return Math.max(0, vel.dot(d.normalize()));
    }

    /**
     * Mobs, players and other vehicles in the hull's way: damage and knockback from the closing speed (scaled by the
     * vehicle's mass), the vehicle takes part of it and loses speed. Riders, our own entities, creative/spectator
     * players and anything slower than physics.ram.min-speed are left alone. Each target once per 10 ticks.
     */
    public void ram() {
        double speed = Math.hypot(s.vel.x, s.vel.z);
        if (speed < cfg("physics.ram.min-speed", 3.0) || v.wreck) return;
        int now = (int) v.age;
        ramCooldown.values().removeIf(t -> t < now - 40);
        Obb h = v.hull();
        Obb reach = new Obb(h.center, new Vector3d(h.half).add(0.25, 0.1, 0.25), h.rot);
        Vector3d c = h.center;
        double mass = Math.max(50, t.physics("mass", t.mass));
        double massFactor = Pose.clamp(mass / 1500.0, 0.3, 4.0);
        org.bukkit.entity.Player driver = v.driver();
        for (org.bukkit.entity.Entity e : v.world.getNearbyEntities(reach.aabb())) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || e.isDead()) continue;
            if (e.getScoreboardTags().contains(fi.alavesa.superbvehicles.core.Keys.TAG_ENTITY)) continue;
            if (e instanceof org.bukkit.entity.ArmorStand) continue;
            if (e instanceof org.bukkit.entity.Player pl && (v.seatOf(pl) != null || pl.getGameMode() == org.bukkit.GameMode.CREATIVE || pl.getGameMode() == org.bukkit.GameMode.SPECTATOR)) continue;
            if (!reach.intersects(e.getBoundingBox())) continue;
            Integer last = ramCooldown.get(e.getUniqueId());
            if (last != null && now - last < 10) continue;
            Vector3d at = new Vector3d(e.getLocation().getX(), e.getLocation().getY(), e.getLocation().getZ());
            double closing = closingSpeed(s.vel, c, at);
            if (closing < cfg("physics.ram.min-speed", 3.0)) continue;
            ramCooldown.put(e.getUniqueId(), now);
            double dmg = closing * cfg("physics.crash.ram-damage", 1.2) * massFactor;
            if (driver != null) le.damage(dmg, driver); else le.damage(dmg);
            Vector3d push = new Vector3d(at).sub(c); push.y = 0;
            if (push.lengthSquared() < 1e-9) push.set(v.pose.forwardFlat()); else push.normalize();
            le.setVelocity(new org.bukkit.util.Vector(push.x * closing * 0.05 + s.vel.x * 0.03, 0.35 + closing * 0.01, push.z * closing * 0.05 + s.vel.z * 0.03));
            v.world.playSound(le.getLocation(), "minecraft:entity.player.attack.knockback", 1f, 0.7f);
            double self = closing * cfg("physics.ram.self-damage", 0.25);
            if (self > 0.5) plugin.damage().crash(v, self, 0);
            double keep = Pose.clamp(1 - le.getBoundingBox().getVolume() * 40 / mass, 0.6, 0.97);   // big mobs slow you more
            s.vel.x *= keep; s.vel.z *= keep;
        }
        for (Vehicle o : plugin.vehicles().nearby(v.location(), 16)) {
            if (o == v || !o.spawned() || o.wreck) continue;
            Obb oh = o.hull();
            if (!reach.intersects(oh)) continue;
            Integer last = ramCooldown.get(o.id);
            if (last != null && now - last < 10) continue;
            Vector3d rel = new Vector3d(s.vel).sub(o.phys.vel);
            double closing = closingSpeed(rel, c, oh.center);
            if (closing < cfg("physics.ram.min-speed", 3.0)) continue;
            ramCooldown.put(o.id, now);
            double om = Math.max(50, o.type.physics("mass", o.type.mass));
            double dmg = closing * cfg("physics.crash.vehicle-damage", 2.5) * 0.6;
            plugin.damage().crash(o, dmg * Pose.clamp(mass / om, 0.25, 4), closing);
            plugin.damage().crash(v, dmg * Pose.clamp(om / mass, 0.25, 4), closing);
            Vector3d dir = new Vector3d(oh.center).sub(c); dir.y = 0;
            if (dir.lengthSquared() < 1e-9) dir.set(v.pose.forwardFlat()); else dir.normalize();
            double share = om / (mass + om);                          // momentum: the lighter one is shoved more
            o.phys.vel.add(new Vector3d(dir).mul(closing * (1 - share)));
            s.vel.sub(new Vector3d(dir).mul(closing * share));
            v.world.playSound(v.location(), "minecraft:entity.generic.explode", 0.6f, 1.6f);
            v.world.spawnParticle(org.bukkit.Particle.CRIT, (c.x + oh.center.x) / 2, (c.y + oh.center.y) / 2, (c.z + oh.center.z) / 2, 20, 0.4, 0.4, 0.4, 0.2);
        }
    }
}
