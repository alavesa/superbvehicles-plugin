package fi.alavesa.superbvehicles.weapon;

import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.Locale;
import java.util.UUID;

/**
 * One weapon on one vehicle: magazine + reserve, cooldown, reload, the turret/gun aim it drives (deg relative to the
 * hull, rate-limited), lock-on state for guided weapons, countermeasures, and the CIWS automatic mode.
 * {@link #tick} is called every tick with no gunner input; a seat's gunner calls {@link #control}.
 */
public final class MountedWeapon {
    public final Vehicle vehicle;
    public final WeaponType type;
    public int magazine, reserve;
    public int cooldown, reloading;
    public double turretYaw, gunPitch;        // current aim relative to the hull (deg); yaw positive = turning right
    public double targetYaw, targetPitch;
    public int recoilTicks, recoilMax = 6;
    public int muzzleIndex;
    public UUID lockTarget;
    public double lockProgress;               // 0..1
    public int flareCooldown;
    public boolean triggerHeld;
    private boolean controlled;
    private int burstLeft;
    public Vector3d aimPoint;                 // where the gunner is aiming (world), for wire/laser guidance

    public MountedWeapon(Vehicle vehicle, WeaponType type) {
        this.vehicle = vehicle; this.type = type;
        magazine = Math.min(type.magazine, type.ammo);
        reserve = Math.max(0, type.ammo - magazine);
        recoilMax = Math.max(2, Math.min(10, type.cooldownTicks / 2));
    }

    public int totalAmmo() { return magazine + reserve; }
    public boolean empty() { return magazine <= 0 && reserve <= 0; }
    public void restock() { magazine = Math.min(type.magazine, type.ammo); reserve = Math.max(0, type.ammo - magazine); reloading = 0; }
    public String ammoText() { return type.kind.countermeasure() ? String.valueOf(totalAmmo()) : magazine + "/" + reserve; }
    public boolean ready() { return cooldown <= 0 && reloading <= 0 && magazine > 0 && !vehicle.wreck; }

    /** Gunner control for this tick: where they look, whether the trigger is held, countermeasure press. */
    public void control(Player gunner, boolean trigger, boolean countermeasure) {
        controlled = true;
        Location eye = gunner.getEyeLocation();
        aimPoint = aimPoint(gunner, type.range);
        // turret / gun targets relative to the hull: yaw from the aim direction, pitch from elevation
        Vector3d from = muzzleWorld();
        Vector3d dir = new Vector3d(aimPoint).sub(from);
        if (dir.lengthSquared() < 1e-6) dir = new Vector3d(eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ());
        Vector3d local = vehicle.pose.rotation().transpose().transform(new Vector3d(dir)).normalize();
        // model frame: forward -Z, right +X, up +Y
        targetYaw = Math.toDegrees(Math.atan2(local.x, -local.z));
        targetPitch = Math.toDegrees(Math.asin(Pose.clamp(local.y, -1, 1)));
        if (type.yawMin > -180 || type.yawMax < 180) targetYaw = Pose.clamp(targetYaw, type.yawMin, type.yawMax);
        targetPitch = Pose.clamp(targetPitch, type.pitchMin, type.pitchMax);
        triggerHeld = trigger;
        if (countermeasure && type.kind.countermeasure()) fireCountermeasure(gunner);
        else if (trigger && type.kind.countermeasure()) fireCountermeasure(gunner);
        // lock-on
        if (type.guidance == WeaponType.Guidance.LOCK || type.guidance == WeaponType.Guidance.LOCK_LASER) updateLock(gunner, eye);
    }

    private void updateLock(Player gunner, Location eye) {
        Entity best = null; double bestAng = type.lockCone;
        Vector3d look = new Vector3d(eye.getDirection().getX(), eye.getDirection().getY(), eye.getDirection().getZ());
        for (Entity e : eye.getWorld().getNearbyEntities(eye, type.lockRange, type.lockRange, type.lockRange)) {
            if (e == gunner || vehicle.owns(e)) continue;
            boolean candidate = false;
            Vehicle other = vehicle.plugin.vehicles().byEntity(e);
            if (other != null) { if (other == vehicle || e != other.root) continue; candidate = true; }
            else if (e instanceof LivingEntity le && !(le instanceof org.bukkit.entity.ArmorStand) && e != gunner) { if (e instanceof Player p && vehicle.seatOf(p) != null) continue; candidate = true; }
            if (!candidate) continue;
            if (!vehicle.plugin.damage().hostile(vehicle, gunner, e)) continue;
            Vector3d to = new Vector3d(e.getLocation().getX(), e.getLocation().getY() + e.getHeight() / 2, e.getLocation().getZ()).sub(eye.getX(), eye.getY(), eye.getZ());
            double dist = to.length();
            if (dist > type.lockRange || dist < 2) continue;
            double ang = Math.toDegrees(Math.acos(Pose.clamp(to.normalize().dot(look), -1, 1)));
            if (ang < bestAng) { bestAng = ang; best = e; }
        }
        if (best == null) { lockProgress = Math.max(0, lockProgress - 0.08); if (lockProgress <= 0) lockTarget = null; return; }
        if (lockTarget == null || !lockTarget.equals(best.getUniqueId())) { lockTarget = best.getUniqueId(); lockProgress = 0; }
        boolean was = lockProgress >= 1;
        lockProgress = Math.min(1, lockProgress + 1 / Math.max(1, type.lockTime * 20));
        if (lockProgress >= 1 && !was) {
            Vehicle target = vehicle.plugin.vehicles().byEntity(best);
            vehicle.plugin.getServer().getPluginManager().callEvent(new fi.alavesa.superbvehicles.api.VehicleMissileLockEvent(vehicle, this, gunner, best, target));
            if (target != null) target.plugin.hud().lockWarning(target);
            vehicle.playSound("minecraft:block.note_block.pling", 1f, 1.8f);
        }
    }

    public boolean locked() { return lockTarget != null && lockProgress >= 1; }
    public Entity lockedEntity() { return locked() ? vehicle.plugin.getServer().getEntity(lockTarget) : null; }

    /** Per-tick: turret motion, cooldowns, reload, firing when the trigger is held, CIWS automation. */
    public void tick(int serverTick) {
        if (cooldown > 0) cooldown--;
        if (flareCooldown > 0) flareCooldown--;
        if (recoilTicks > 0) recoilTicks--;
        if (reloading > 0) { reloading--; if (reloading == 0) { int take = Math.min(type.magazine, reserve); magazine += take; reserve -= take; vehicle.playSound(type.reloadSound, 0.7f, 1f); } }
        if (!controlled) {
            if (type.autoTarget && !vehicle.wreck) autoTarget();
            else { triggerHeld = false; lockTarget = null; lockProgress = 0; targetYaw = Pose.approach(targetYaw, 0, 0.02); targetPitch = Pose.approach(targetPitch, 0, 0.02); }
        }
        double step = type.traverseSpeed / 20.0;
        turretYaw = Pose.approachAngle(turretYaw, targetYaw, step);
        gunPitch = Pose.approachAngle(gunPitch, targetPitch, step);
        if (triggerHeld && !type.kind.countermeasure()) tryFire(controlledGunner());
        controlled = false;
    }

    private Player controlledGunner() {
        for (Vehicle.SeatSlot s : vehicle.seats) { Player p = s.rider(); if (p != null && s.def.weapons.contains(type.name) && s.weaponIndex < s.def.weapons.size() && s.def.weapons.get(s.weaponIndex).equals(type.name)) return p; }
        return null;
    }

    /** CIWS: incoming projectiles first, then hostile aircraft in range. */
    private void autoTarget() {
        Vector3d from = muzzleWorld();
        Vector3d best = null; double bestD = type.range;
        for (Projectile p : vehicle.plugin.projectiles().all()) {
            if (p.shooter == vehicle || !p.type.kind.projectile() || p.type.kind == WeaponType.Kind.MG || p.type.kind == WeaponType.Kind.AUTOCANNON || p.type.kind == WeaponType.Kind.CIWS) continue;
            if (p.shooter != null && !vehicle.plugin.damage().hostileVehicles(vehicle, p.shooter)) continue;
            double d = p.pos.distance(from);
            if (d < bestD) { bestD = d; best = new Vector3d(p.pos).add(new Vector3d(p.vel).mul(Math.min(10, d / Math.max(0.1, type.velocity)))); }
        }
        if (best == null) for (Vehicle o : vehicle.plugin.vehicles().all()) {
            if (o == vehicle || !o.spawned() || !o.type.vehicleClass.aircraft() || !o.occupied()) continue;
            if (!vehicle.plugin.damage().hostileVehicles(vehicle, o)) continue;
            double d = o.position().distance(from);
            if (d < bestD) { bestD = d; best = o.hull().center.add(new Vector3d(o.phys.vel).div(fi.alavesa.superbvehicles.physics.Units.TPS).mul(d / Math.max(0.1, type.velocity))); }
        }
        if (best == null) { triggerHeld = false; return; }
        aimPoint = best;
        Vector3d local = vehicle.pose.rotation().transpose().transform(new Vector3d(best).sub(from)).normalize();
        targetYaw = Math.toDegrees(Math.atan2(local.x, -local.z));
        targetPitch = Pose.clamp(Math.toDegrees(Math.asin(Pose.clamp(local.y, -1, 1))), type.pitchMin, type.pitchMax);
        triggerHeld = Math.abs(Pose.wrap(turretYaw - targetYaw)) < 6 && Math.abs(gunPitch - targetPitch) < 6;
    }

    private void tryFire(Player gunner) {
        if (vehicle.wreck || vehicle.health <= 0) return;
        if (cooldown > 0 || reloading > 0) return;
        if (magazine <= 0) {
            if (reserve > 0 && type.reloadTicks > 0) { reloading = type.reloadTicks; return; }
            if (reserve > 0) { int take = Math.min(type.magazine, reserve); magazine += take; reserve -= take; }
            else { if (gunner != null && vehicle.age % 20 == 0) vehicle.plugin.messages().actionBar(gunner, "out-of-ammo", fi.alavesa.superbvehicles.core.Messages.str("weapon", type.displayName)); return; }
        }
        if (type.kind.guided() && type.guidance != WeaponType.Guidance.NONE && type.guidance != WeaponType.Guidance.WIRE && type.guidance != WeaponType.Guidance.LASER && !locked()) {
            if (gunner != null && vehicle.age % 10 == 0) vehicle.plugin.messages().actionBar(gunner, "locking", fi.alavesa.superbvehicles.core.Messages.num("percent", lockProgress * 100));
            return;
        }
        if (vehicle.plugin.projectiles().countFor(vehicle) >= vehicle.plugin.cfg("weapons.max-per-vehicle", 40)) return;
        fire(gunner);
    }

    /** Fire one round now (ammo and cooldown bookkeeping included). */
    public boolean fire(Player gunner) {
        Vector3d from = muzzleWorld();
        Vector3d dir;
        if (type.kind == WeaponType.Kind.BOMB) dir = new Vector3d(0, -1, 0);
        else if (type.kind == WeaponType.Kind.MORTAR) dir = mortarDirection(from);
        else {
            Vector3d target = aimPoint != null ? aimPoint : new Vector3d(from).add(muzzleForward().mul(100));
            dir = new Vector3d(target).sub(from);
            if (dir.lengthSquared() < 1 || dir.dot(muzzleForward()) < 0.2) dir = muzzleForward();
            dir.normalize();
            double spread = Math.toRadians(type.spread);
            if (spread > 0) {
                Vector3d a = Math.abs(dir.y) < 0.9 ? new Vector3d(0, 1, 0).cross(dir, new Vector3d()).normalize() : new Vector3d(1, 0, 0);
                Vector3d b = new Vector3d(dir).cross(a).normalize();
                double r = Math.sqrt(Math.random()) * spread, th = Math.random() * Math.PI * 2;
                dir.add(a.mul(Math.tan(r) * Math.cos(th))).add(b.mul(Math.tan(r) * Math.sin(th))).normalize();
            }
        }
        var ev = new fi.alavesa.superbvehicles.api.VehicleWeaponFireEvent(vehicle, this, gunner, from, dir);
        vehicle.plugin.getServer().getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        magazine--;
        cooldown = type.cooldownTicks;
        recoilTicks = recoilMax;
        Vector3d vel = new Vector3d(dir).mul(type.velocity);
        if (type.kind == WeaponType.Kind.BOMB) vel.set(vehicle.phys.vel).div(fi.alavesa.superbvehicles.physics.Units.TPS).add(0, -0.1, 0);
        else vel.add(new Vector3d(vehicle.phys.vel).div(fi.alavesa.superbvehicles.physics.Units.TPS).mul(0.5));
        vehicle.plugin.projectiles().launch(vehicle, this, from, vel, lockedEntity(), gunner);
        // effects
        Location l = new Location(vehicle.world, from.x, from.y, from.z);
        vehicle.world.playSound(l, type.sound, org.bukkit.SoundCategory.NEUTRAL, type.kind == WeaponType.Kind.MG ? 0.8f : 1.5f, type.kind == WeaponType.Kind.MG ? 1.6f : type.kind == WeaponType.Kind.AUTOCANNON ? 1.2f : 0.8f);
        if (type.kind == WeaponType.Kind.CANNON || type.kind == WeaponType.Kind.MORTAR) { vehicle.world.spawnParticle(Particle.EXPLOSION, l, 1); vehicle.world.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.3, 0.3, 0.05); }
        else if (type.kind == WeaponType.Kind.ROCKET || type.kind == WeaponType.Kind.MISSILE) vehicle.world.spawnParticle(Particle.CLOUD, l, 8, 0.3, 0.3, 0.3, 0.05);
        else vehicle.world.spawnParticle(Particle.SMOKE, l, 3, 0.1, 0.1, 0.1, 0.02);
        if (type.recoil > 0) { Vector3d kick = new Vector3d(dir).mul(-type.recoil); vehicle.phys.vel.add(kick.x, kick.y * 0.2, kick.z); }
        muzzleIndex = type.muzzles.isEmpty() ? 0 : (muzzleIndex + 1) % type.muzzles.size();
        if (magazine <= 0 && reserve > 0 && type.reloadTicks > 0) reloading = type.reloadTicks;
        return true;
    }

    private void fireCountermeasure(Player gunner) {
        if (flareCooldown > 0 || totalAmmo() <= 0 || cooldown > 0) return;
        magazine = Math.max(0, magazine - 1); if (magazine == 0 && reserve > 0) { magazine = Math.min(type.magazine, reserve); reserve -= magazine; }
        cooldown = type.cooldownTicks; flareCooldown = 10;
        vehicle.phys.flareTicks = type.flareTicks;
        Vector3d c = vehicle.hull().center;
        Location l = new Location(vehicle.world, c.x, c.y, c.z);
        for (int i = 0; i < 6; i++) {
            Vector3d off = new Vector3d(Math.random() - 0.5, -Math.random() * 0.5, Math.random() - 0.5).mul(3);
            if (type.kind == WeaponType.Kind.SMOKE) vehicle.world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, l.clone().add(off.x, off.y + 1, off.z), 20, 1.5, 1, 1.5, 0.02);
            else vehicle.world.spawnParticle(Particle.FIREWORK, l.clone().add(off.x, off.y, off.z), 30, 0.8, 0.8, 0.8, 0.15);
        }
        if (type.kind == WeaponType.Kind.SMOKE) vehicle.phys.flareTicks = type.flareTicks * 2;
        vehicle.world.playSound(l, "minecraft:entity.firework_rocket.launch", 1f, 0.6f);
        if (gunner != null) vehicle.plugin.messages().actionBar(gunner, "flares", fi.alavesa.superbvehicles.core.Messages.num("left", totalAmmo()));
    }

    /** Current muzzle position (world). */
    public Vector3d muzzleWorld() {
        if (!type.muzzles.isEmpty()) { Vector3d m = vehicle.locatorWorld(type.muzzles.get(muzzleIndex % type.muzzles.size())); if (m != null) return m; }
        Vector3d m = vehicle.locatorWorld("muzzle_1"); if (m != null) return m;
        Vector3d gun = vehicle.locatorWorld(type.gunBone); if (gun != null) return gun.add(muzzleForward().mul(1.5));
        return vehicle.hull().center.add(0, vehicle.hull().half.y, 0);
    }

    /** Forward direction of the gun bone (falls back to the hull forward turned by the aim). */
    public Vector3d muzzleForward() {
        Vector3d f = vehicle.boneForward(type.gunBone);
        if (vehicle.rig() != null && vehicle.rig().model.bone(type.gunBone) != null) return f;
        // no gun bone: rotate the hull forward by the aim angles
        Vector3d local = new Vector3d(Math.sin(Math.toRadians(turretYaw)) * Math.cos(Math.toRadians(gunPitch)), Math.sin(Math.toRadians(gunPitch)), -Math.cos(Math.toRadians(turretYaw)) * Math.cos(Math.toRadians(gunPitch)));
        return vehicle.pose.rotation().transform(local).normalize();
    }

    private Vector3d mortarDirection(Vector3d from) {
        Vector3d target = aimPoint != null ? aimPoint : new Vector3d(from).add(vehicle.pose.forwardFlat().mul(60));
        Vector3d flat = new Vector3d(target.x - from.x, 0, target.z - from.z);
        double dist = flat.length(); if (dist < 1) flat.set(vehicle.pose.forwardFlat()); else flat.normalize();
        double ang = Math.toRadians(type.mortarAngle);
        return new Vector3d(flat.x * Math.cos(ang), Math.sin(ang), flat.z * Math.cos(ang)).normalize();
    }

    /** Ballistic launch speed (blocks/tick) for a mortar to reach the aim point at its fixed angle; capped by velocity. */
    public double mortarSpeed(Vector3d from) {
        Vector3d target = aimPoint != null ? aimPoint : new Vector3d(from).add(vehicle.pose.forwardFlat().mul(60));
        double dx = Math.hypot(target.x - from.x, target.z - from.z), dy = target.y - from.y;
        double ang = Math.toRadians(type.mortarAngle), g = type.gravity;
        double denom = 2 * Math.cos(ang) * Math.cos(ang) * (dx * Math.tan(ang) - dy);
        if (denom <= 0) return type.velocity;
        return Math.min(type.velocity * 2, Math.sqrt(g * dx * dx / denom));
    }

    /** Where the gunner looks: the first block/entity hit (not our own vehicle), else `range` ahead. */
    public Vector3d aimPoint(Player gunner, double range) {
        Location eye = gunner.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult hit = gunner.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.5, e -> e != gunner && !vehicle.owns(e) && !(e instanceof org.bukkit.entity.ArmorStand) && !(e instanceof org.bukkit.entity.Interaction) && !(e instanceof org.bukkit.entity.Display) && vehicle.seatOf(e instanceof Player p ? p : null) == null);
        Vector p = hit != null ? hit.getHitPosition() : eye.toVector().add(dir.multiply(range));
        return new Vector3d(p.getX(), p.getY(), p.getZ());
    }

    public String stateText() {
        if (reloading > 0) return "reloading " + (int) (100.0 * (type.reloadTicks - reloading) / Math.max(1, type.reloadTicks)) + "%";
        if (type.kind.guided() && (type.guidance == WeaponType.Guidance.LOCK || type.guidance == WeaponType.Guidance.LOCK_LASER)) return locked() ? "LOCKED" : lockTarget != null ? "locking " + (int) (lockProgress * 100) + "%" : "no lock";
        return type.ammoType.isEmpty() ? "" : type.ammoType.toUpperCase(Locale.ROOT);
    }
}
