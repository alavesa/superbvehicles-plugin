package fi.alavesa.superbvehicles.damage;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.api.VehicleDamageEvent;
import fi.alavesa.superbvehicles.api.VehicleDestroyEvent;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.physics.Obb;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.joml.Vector3d;

/**
 * Vehicle health bookkeeping: which hull face a hit came through (front / side / rear / top / bottom), the zone's
 * armour against the round's penetration, the VehicleDamageEvent, fire at low health, and the wreck state when
 * health reaches zero. Team rules (scoreboard teams by default) decide friendly fire.
 */
public final class DamageModel {
    private final SuperbVehiclesPlugin plugin;
    public DamageModel(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    /** Hull face a hit travelling along `dir` arrives through. */
    public String zone(Vehicle v, Vector3d hitPoint, Vector3d dir) {
        if (dir == null || dir.lengthSquared() < 1e-9) return "side";
        Vector3d local = v.pose.rotation().transpose().transform(new Vector3d(dir).normalize());
        double elev = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -local.y))));
        if (elev > v.type.topAngle) return "top";
        if (elev < -v.type.topAngle) return "bottom";
        double a = Math.abs(Math.toDegrees(Math.atan2(local.x, local.z)));   // travelling +Z (model) = arriving at the front
        return a < 45 ? "front" : a > 135 ? "rear" : "side";
    }

    /** Lower health by `amount` (before armour). Returns what was taken. */
    public double apply(Vehicle v, double amount, DamageType type, Entity source, Vector3d hitPoint, Vector3d hitDir, double penetration) {
        if (v == null || v.removed || v.wreck || amount <= 0 || v.type.invulnerable || !plugin.flag("damage.enabled", true)) return 0;
        String zone = type.armourApplies() ? zone(v, hitPoint, hitDir) : "none";
        double dmg = amount;
        double armor = type.armourApplies() ? v.type.armor(zone) : 0;
        boolean bounced = armor > 0 && penetration < armor;
        if (armor > 0) dmg *= bounced ? 0.15 : Math.max(0.4, 1 - armor / (armor + penetration) * 0.5);
        VehicleDamageEvent ev = new VehicleDamageEvent(v, dmg, type, source, zone);
        plugin.getServer().getPluginManager().callEvent(ev);
        if (ev.isCancelled() || ev.getAmount() <= 0) return 0;
        dmg = ev.getAmount();
        v.health -= dmg;
        if (hitPoint != null && type != DamageType.FIRE) {
            Location l = new Location(v.world, hitPoint.x, hitPoint.y, hitPoint.z);
            v.world.spawnParticle(bounced ? Particle.CRIT : Particle.LAVA, l, bounced ? 8 : 4, 0.2, 0.2, 0.2, 0.05);
            if (type == DamageType.KINETIC) v.world.playSound(l, bounced ? "minecraft:entity.arrow.hit_player" : "minecraft:block.anvil.place", 0.8f, 1.4f);
        }
        if (v.health <= 0) destroy(v, source);
        return dmg;
    }

    /** Collision damage (no armour) plus rider damage scaled by the impact speed. */
    public void crash(Vehicle v, double amount, double speedBps) {
        if (v.removed) return;
        apply(v, amount, DamageType.COLLISION, null, v.hull().center, null, 0);
        double rdmg = Math.max(0, speedBps - plugin.cfg("physics.crash.min-speed", 6.0)) * plugin.cfg("physics.crash.rider-damage", 0.5);
        for (Player p : v.occupants()) {
            if (rdmg <= 0 || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            p.damage(rdmg);
            p.sendActionBar(plugin.messages().parse("<red>Impact! " + Math.round(speedBps) + " blocks/s"));
        }
    }

    public void burn(Vehicle v, double amount) { apply(v, amount, DamageType.FIRE, null, null, null, 0); }

    /** Health reached zero: event, bang, riders out, burning wreck for the type's wreck-seconds (or removal). */
    public void destroy(Vehicle v, Entity source) {
        if (v.removed || v.wreck) return;
        v.health = 0;
        plugin.getServer().getPluginManager().callEvent(new VehicleDestroyEvent(v, source));
        Obb h = v.hull();
        Location c = new Location(v.world, h.center.x, h.center.y, h.center.z);
        for (Player p : v.occupants()) { plugin.messages().send(p, "destroyed", Messages.str("name", v.displayName())); if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) p.damage(Math.min(12, v.type.explosionPower * 3)); }
        plugin.seats().ejectAll(v, true);
        v.phys.engineOn = false; v.phys.throttle = 0; v.phys.vel.mul(0.3);
        for (var w : v.weapons.values()) { w.triggerHeld = false; w.lockTarget = null; }
        if (plugin.flag("damage.explosion-on-destroy", true) && v.type.explosionPower > 0) {
            boolean blocks = plugin.flag("weapons.block-damage", true) && plugin.flag("damage.destroy-breaks-blocks", false);
            v.world.createExplosion(c, (float) v.type.explosionPower, false, blocks, v.root);
        }
        v.world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        v.world.spawnParticle(Particle.LARGE_SMOKE, c, 40, h.half.x, h.half.y, h.half.z, 0.05);
        v.world.playSound(c, "minecraft:entity.generic.explode", 1.5f, 0.7f);
        if (plugin.flag("damage.wreck", true) && v.type.wreckSeconds > 0) { v.wreck = true; v.wreckTicks = (int) (v.type.wreckSeconds * 20); v.lastState = ""; }
        else plugin.vehicles().remove(v, false);
    }

    /** May this gunner's weapon affect that entity? (friendly fire / teams) */
    public boolean hostile(Vehicle shooter, Player gunner, Entity target) {
        if (plugin.flag("weapons.friendly-fire", true)) return true;
        Vehicle tv = plugin.vehicles().byEntity(target);
        if (tv != null) return hostileVehicles(shooter, tv);
        if (target instanceof Player p && gunner != null) return !sameTeam(gunner, p);
        return true;
    }

    public boolean hostileVehicles(Vehicle a, Vehicle b) {
        if (a == null || b == null || a == b) return false;
        if (plugin.flag("weapons.friendly-fire", true)) return true;
        Player pa = a.occupants().isEmpty() ? null : a.occupants().get(0), pb = b.occupants().isEmpty() ? null : b.occupants().get(0);
        if (pa != null && pb != null) return !sameTeam(pa, pb);
        if (a.owner != null && b.owner != null) { Player oa = plugin.getServer().getPlayer(a.owner), ob = plugin.getServer().getPlayer(b.owner); return oa != null && ob != null ? !sameTeam(oa, ob) : !a.owner.equals(b.owner); }
        return true;
    }

    public boolean sameTeam(Player a, Player b) {
        if (a == b) return true;
        if (!plugin.getConfig().getString("weapons.team-provider", "scoreboard").equalsIgnoreCase("scoreboard")) return false;
        Team ta = a.getScoreboard().getEntryTeam(a.getName()), tb = b.getScoreboard().getEntryTeam(b.getName());
        return ta != null && tb != null && ta.getName().equals(tb.getName());
    }
}
