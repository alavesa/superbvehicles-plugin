package fi.alavesa.superbvehicles.damage;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Vehicle;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.joml.Vector3d;

/**
 * Health changes coming from the rest of the game: melee hits on the hull / seat boxes, vanilla projectiles that
 * reach them, explosions in range (TNT, creepers, other vehicles). Our own stands and displays are never hurt by
 * vanilla, and riders in closed seats are shielded from direct hits.
 */
public final class DamageListener implements Listener {
    private final SuperbVehiclesPlugin plugin;
    public DamageListener(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMelee(PrePlayerAttackEntityEvent e) {
        Vehicle v = plugin.vehicles().byEntity(e.getAttacked());
        if (v == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (v.seatOf(p) != null) return;
        String tool = p.getInventory().getItemInMainHand().getType().name();
        double dmg = tool.endsWith("_SWORD") ? 4 : tool.endsWith("_AXE") ? 5 : tool.endsWith("_PICKAXE") ? 3 : 1;
        Location l = e.getAttacked().getLocation();
        Vector3d dir = new Vector3d(p.getLocation().getDirection().getX(), p.getLocation().getDirection().getY(), p.getLocation().getDirection().getZ());
        plugin.damage().apply(v, dmg, DamageType.MELEE, p, new Vector3d(l.getX(), l.getY() + 0.5, l.getZ()), dir, 2);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        Vehicle v = plugin.vehicles().byEntity(hit);
        if (v == null) return;
        Projectile pr = e.getEntity();
        double dmg = switch (pr.getType()) { case ARROW, SPECTRAL_ARROW -> 3; case TRIDENT -> 6; case FIREBALL, DRAGON_FIREBALL -> 10; case SMALL_FIREBALL -> 4; default -> 1; };
        Vector3d dir = new Vector3d(pr.getVelocity().getX(), pr.getVelocity().getY(), pr.getVelocity().getZ());
        Location l = pr.getLocation();
        plugin.damage().apply(v, dmg, DamageType.KINETIC, pr.getShooter() instanceof Entity s ? s : pr, new Vector3d(l.getX(), l.getY(), l.getZ()), dir, 4);
        if (pr.getType() == org.bukkit.entity.EntityType.ARROW || pr.getType() == org.bukkit.entity.EntityType.SPECTRAL_ARROW) pr.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { explosion(e.getLocation(), e.getYield() <= 0 ? 3 : Math.max(2, e.getYield() * 4), e.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { explosion(e.getBlock().getLocation(), 4, null); }

    private void explosion(Location at, double power, Entity source) {
        Vehicle self = source != null && source.getScoreboardTags().contains(Keys.TAG_ROOT) ? plugin.vehicles().byEntity(source) : null;
        for (Vehicle v : plugin.vehicles().nearby(at, power * 2)) {
            if (v == self) continue;
            Vector3d c = v.hull().center;
            double d = c.distance(at.getX(), at.getY(), at.getZ()), radius = power * 2;
            if (d > radius) continue;
            Vector3d dir = new Vector3d(c).sub(at.getX(), at.getY(), at.getZ());
            plugin.damage().apply(v, power * 12 * (1 - d / radius), DamageType.EXPLOSIVE, source, new Vector3d(at.getX(), at.getY(), at.getZ()), dir, power * 15);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) { if (plugin.vehicles().byEntity(e.getEntity()) != null) e.setCancelled(true); return; }
        if (!plugin.flag("damage.protect-occupants", true)) return;
        Vehicle v = plugin.vehicles().byPlayer(p);
        if (v == null) return;
        Vehicle.SeatSlot s = v.seatOf(p);
        if (s == null || s.def.exposed) return;
        switch (e.getCause()) {
            case PROJECTILE, ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, FALL, FLY_INTO_WALL, CONTACT, DROWNING, HOT_FLOOR, CRAMMING -> e.setCancelled(true);
            default -> { }
        }
    }
}
