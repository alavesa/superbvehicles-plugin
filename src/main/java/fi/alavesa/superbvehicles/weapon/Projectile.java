package fi.alavesa.superbvehicles.weapon;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.damage.DamageType;
import fi.alavesa.superbvehicles.physics.Obb;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * One in-flight round of a mounted weapon, simulated by the plugin: a point with velocity, gravity and drag, moved
 * once per tick with a ray from the old to the new position against blocks, vehicle hulls and living entities.
 * Guided kinds steer toward the gunner's aim point (wire / laser) or the locked target (flares can decoy them).
 * An optional item display flies along; the kind's particle trails behind. Impact = health change on what was
 * hit (with the weapon's penetration), splash in a radius, and an explosion whose block damage is a toggle.
 */
public final class Projectile {
    public final SuperbVehiclesPlugin plugin;
    public final Vehicle shooter;
    public final MountedWeapon weapon;
    public final WeaponType type;
    public final World world;
    public final Vector3d pos, vel;
    public final UUID gunner;
    public UUID target;
    public int age;
    private ItemDisplay body;
    private final int maxAge;
    private int armTicks = 2;
    private Vector3d decoy;

    public Projectile(SuperbVehiclesPlugin plugin, Vehicle shooter, MountedWeapon weapon, Vector3d from, Vector3d vel, Entity lockTarget, Player gunner) {
        this.plugin = plugin; this.shooter = shooter; this.weapon = weapon; this.type = weapon.type; this.world = shooter.world;
        this.pos = new Vector3d(from); this.vel = new Vector3d(vel); this.gunner = gunner == null ? null : gunner.getUniqueId();
        this.target = lockTarget == null ? null : lockTarget.getUniqueId();
        this.maxAge = (int) (type.lifetime * 20);
        if (type.kind == WeaponType.Kind.MORTAR) this.vel.normalize(weapon.mortarSpeed(from));
        if (type.showProjectile && plugin.flag("weapons.projectile-display", true)) spawnBody();
    }

    private void spawnBody() {
        Rig rig = plugin.types().rig(type.projectileModel);
        if (rig == null || rig.parts.isEmpty()) return;
        String key = rig.parts.get(0).itemModel.get("default");
        body = world.spawn(new Location(world, pos.x, pos.y, pos.z), ItemDisplay.class, d -> {
            d.setPersistent(false); d.setBillboard(Display.Billboard.FIXED); d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTeleportDuration(1); d.setInterpolationDuration(1); d.setViewRange(2f);
            d.addScoreboardTag(Keys.TAG_ENTITY); d.addScoreboardTag(Keys.TAG_PROJECTILE);
            ItemStack it = new ItemStack(Material.STICK); ItemMeta m = it.getItemMeta(); if (key != null) m.setItemModel(NamespacedKey.fromString(key)); it.setItemMeta(m);
            d.setItemStack(it);
        });
        orient();
    }

    private void orient() {
        if (body == null || !body.isValid() || vel.lengthSquared() < 1e-8) return;
        Vector3d d = new Vector3d(vel).normalize();
        double yaw = Math.atan2(-d.x, -d.z), pitch = Math.asin(Pose.clamp(d.y, -1, 1));   // the model points along -Z
        body.setInterpolationDelay(0);
        body.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY((float) yaw).rotateX((float) pitch), new Vector3f(1, 1, 1), new Quaternionf()));
    }

    /** One tick; false when the round is finished. */
    public boolean tick() {
        age++;
        if (age > maxAge) { impact(pos, null, null); return false; }
        if (armTicks > 0) armTicks--;
        guide();
        vel.y -= type.gravity;
        if (type.drag > 0) vel.mul(1 - type.drag);
        if ((type.kind == WeaponType.Kind.MISSILE || type.kind == WeaponType.Kind.ROCKET) && age < maxAge * 0.7) { double sp = vel.length(); if (sp < type.velocity) vel.normalize(Math.min(type.velocity, sp + type.velocity * 0.08)); }
        double len = vel.length();
        Vector3d dirN = len > 1e-6 ? new Vector3d(vel).normalize() : new Vector3d(0, -1, 0);
        // blocks
        if (len > 1e-6) {
            RayTraceResult hit = world.rayTraceBlocks(new Location(world, pos.x, pos.y, pos.z), new Vector(dirN.x, dirN.y, dirN.z), len, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlock() != null) {
                Vector hp = hit.getHitPosition();
                Vector3d n = hit.getHitBlockFace() == null ? null : new Vector3d(hit.getHitBlockFace().getModX(), hit.getHitBlockFace().getModY(), hit.getHitBlockFace().getModZ());
                impact(new Vector3d(hp.getX(), hp.getY(), hp.getZ()), null, n);
                return false;
            }
        }
        if (armTicks == 0) {
            // vehicle hulls along the segment
            Vehicle hitV = null; double best = len + 0.5;
            for (Vehicle v : plugin.vehicles().nearby(new Location(world, pos.x, pos.y, pos.z), len + 12)) {
                if (!v.spawned() || (v == shooter && age < 10)) continue;
                double[] r = v.hull().rayHit(pos, dirN, best);
                if (r != null && r[0] < best) { best = r[0]; hitV = v; }
            }
            if (hitV != null) { Vector3d hp = new Vector3d(pos).add(new Vector3d(dirN).mul(best)); plugin.damage().apply(hitV, type.damage, type.kind == WeaponType.Kind.CANNON || type.splash > 0 ? DamageType.EXPLOSIVE : DamageType.KINETIC, shooterEntity(), hp, dirN, type.penetration); impact(hp, hitV, null); return false; }
            // living entities along the segment
            Vector3d next = new Vector3d(pos).add(vel);
            BoundingBox seg = BoundingBox.of(new Vector(pos.x, pos.y, pos.z), new Vector(next.x, next.y, next.z)).expand(0.5);
            for (Entity e : world.getNearbyEntities(seg, en -> en instanceof LivingEntity && !(en instanceof org.bukkit.entity.ArmorStand) && !en.getScoreboardTags().contains(Keys.TAG_ENTITY))) {
                if (e instanceof Player p) {
                    if (shooter.seatOf(p) != null || (gunner != null && gunner.equals(p.getUniqueId()))) continue;
                    if (plugin.vehicles().byPlayer(p) != null && plugin.flag("damage.protect-occupants", true)) continue;
                }
                RayTraceResult hr = e.getBoundingBox().expand(0.2).rayTrace(new Vector(pos.x, pos.y, pos.z), new Vector(dirN.x, dirN.y, dirN.z), len + 0.5);
                if (hr == null) continue;
                Vector hp = hr.getHitPosition();
                LivingEntity le = (LivingEntity) e;
                Entity src = shooterEntity();
                if (src != null) le.damage(type.damage, src); else le.damage(type.damage);
                le.setVelocity(le.getVelocity().add(new Vector(dirN.x, dirN.y, dirN.z).multiply(0.3)));
                impact(new Vector3d(hp.getX(), hp.getY(), hp.getZ()), null, null);
                return false;
            }
        }
        pos.add(vel);
        if (body != null && body.isValid()) { body.teleport(new Location(world, pos.x, pos.y, pos.z)); orient(); }
        trail();
        return true;
    }

    private Entity shooterEntity() { return gunner == null ? shooter.root : plugin.getServer().getPlayer(gunner); }

    /** Steering for guided kinds: toward the aim point (wire, laser) or the locked target with flare decoys. */
    private void guide() {
        if (!type.kind.guided() || type.turnRate <= 0 || age < 3) return;
        Vector3d goal = null;
        switch (type.guidance) {
            case WIRE, LASER -> goal = weapon.aimPoint;
            case LOCK, LOCK_LASER -> {
                Entity t = target == null ? null : plugin.getServer().getEntity(target);
                Vehicle tv = t == null ? null : plugin.vehicles().byEntity(t);
                if (tv != null && tv.phys.flareTicks > 0 && decoy == null && Math.random() < 0.7) decoy = tv.hull().center.add(new Vector3d(Math.random() - 0.5, -2 - Math.random() * 3, Math.random() - 0.5).mul(4));
                if (decoy != null) { goal = decoy; decoy.y -= 0.05; }
                else if (tv != null) goal = tv.hull().center.add(new Vector3d(tv.phys.vel).div(fi.alavesa.superbvehicles.physics.Units.TPS).mul(Math.min(20, pos.distance(tv.hull().center) / Math.max(0.1, vel.length()))));
                else if (t != null && t.isValid()) goal = new Vector3d(t.getLocation().getX(), t.getLocation().getY() + t.getHeight() / 2, t.getLocation().getZ());
                else if (type.guidance == WeaponType.Guidance.LOCK_LASER) goal = weapon.aimPoint;
            }
            default -> { }
        }
        if (goal == null) return;
        Vector3d want = new Vector3d(goal).sub(pos);
        if (want.lengthSquared() < 0.25) return;
        want.normalize();
        Vector3d cur = new Vector3d(vel).normalize();
        double maxTurn = Math.toRadians(type.turnRate), ang = Math.acos(Pose.clamp(cur.dot(want), -1, 1));
        Vector3d dir = ang <= maxTurn ? want : cur.lerp(want, maxTurn / ang).normalize();
        vel.set(dir.mul(vel.length()));
    }

    private void trail() {
        if (type.trail == null || type.trail.isEmpty()) return;
        try { world.spawnParticle(Particle.valueOf(type.trail), pos.x, pos.y, pos.z, 1, 0.02, 0.02, 0.02, 0); } catch (IllegalArgumentException ignored) { }
    }

    /** Impact at a point: splash to vehicles and living entities around, bang, block damage if the weapon allows. */
    private void impact(Vector3d at, Vehicle direct, Vector3d normal) {
        Location l = new Location(world, at.x, at.y, at.z);
        if (type.splash > 0) {
            for (Vehicle v : plugin.vehicles().nearby(l, type.splash + 8)) {
                if (v == direct) continue;
                Obb h = v.hull();
                double d = Math.max(0, h.center.distance(at) - Math.min(h.half.x, Math.min(h.half.y, h.half.z)));
                if (d > type.splash) continue;
                plugin.damage().apply(v, type.splashDamage * (1 - d / type.splash), DamageType.EXPLOSIVE, shooterEntity(), at, new Vector3d(h.center).sub(at), type.penetration * 0.5);
            }
            for (Entity e : world.getNearbyEntities(l, type.splash, type.splash, type.splash, en -> en instanceof LivingEntity && !(en instanceof org.bukkit.entity.ArmorStand) && !en.getScoreboardTags().contains(Keys.TAG_ENTITY))) {
                if (e instanceof Player p && plugin.vehicles().byPlayer(p) != null && plugin.flag("damage.protect-occupants", true)) continue;
                double d = e.getLocation().distance(l);
                double dmg = type.splashDamage * Math.max(0, 1 - d / type.splash);
                if (dmg <= 0) continue;
                Entity src = shooterEntity();
                if (src != null) ((LivingEntity) e).damage(dmg, src); else ((LivingEntity) e).damage(dmg);
            }
            boolean blocks = type.blockDamage && plugin.flag("weapons.block-damage", true);
            if (type.explosionPower > 0) world.createExplosion(l, (float) type.explosionPower, type.fire, blocks, shooter.root);
            else { world.spawnParticle(Particle.EXPLOSION, l, 1); world.playSound(l, type.hitSound, 1.2f, 0.9f); }
            world.spawnParticle(Particle.LARGE_SMOKE, l, (int) (6 * type.splash), type.splash * 0.4, type.splash * 0.3, type.splash * 0.4, 0.03);
        } else {
            try { world.spawnParticle(Particle.valueOf(type.particle), l, 6, 0.15, 0.15, 0.15, 0.05); } catch (IllegalArgumentException ignored) { }
            world.playSound(l, type.hitSound, 0.6f, 1.3f);
            if (type.fire && normal != null) { Location f = l.clone().add(normal.x * 0.6, normal.y * 0.6, normal.z * 0.6); if (f.getBlock().getType().isAir()) f.getBlock().setType(Material.FIRE); }
        }
    }

    /** Intercepted by a CIWS: a small puff where it was. */
    public void pop() { Location l = new Location(world, pos.x, pos.y, pos.z); world.spawnParticle(Particle.EXPLOSION, l, 1); world.playSound(l, "minecraft:entity.firework_rocket.blast", 1f, 1.2f); }

    public void dispose() { if (body != null && body.isValid()) body.remove(); body = null; }
}
