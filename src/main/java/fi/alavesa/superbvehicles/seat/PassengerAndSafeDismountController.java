package fi.alavesa.superbvehicles.seat;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.api.VehicleExitEvent;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.physics.Controls;
import fi.alavesa.superbvehicles.physics.Obb;
import fi.alavesa.superbvehicles.physics.Units;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.BoxBuffer;
import fi.alavesa.superbvehicles.physics.VoxelCollisionAndStepSolver.VoxelSource;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The riders of a vehicle: where their seats are while the hull pitches and rolls, what the driver's keys say this
 * tick, the enter / exit prompts, and where a rider is put when they get out.
 * <p>Getting out never uses the vanilla dismount spot. {@link Scanner} tries, in order, the door on the rider's own
 * side, the other door, behind the vehicle, the roof and the air above it, and takes the first place where a standing
 * player (0.6 x 1.8 x 0.6) touches no collision box, has a solid floor or water at most three blocks below, and
 * neither stands in, on, nor falls through a hazard (lava, fire, magma, cactus, berry bushes - config
 * seats.dismount.hazards). No floor in reach (the void, a ravine) is never accepted.
 */
public final class PassengerAndSafeDismountController {
    public enum Exit { LEFT_DOOR, RIGHT_DOOR, REAR, ROOF, ABOVE }

    /** Player box: half width and height. */
    public static final double HALF = 0.3, HEIGHT = 1.8;
    /** How far below an exit the floor (or water) may be. */
    public static final double MAX_DROP = 3.0;

    /** The exit search itself: no server types, deterministic, reusable (one box buffer). */
    public static final class Scanner {
        private static final double EPS = 1e-4;
        private final BoxBuffer boxes = new BoxBuffer();
        /** The exit found by the last successful {@link #find}. */
        public double x, y, z;
        public Exit exit;

        /**
         * @param cx,cz   hull centre; {@code planeY} its bottom; (fx, fz) forward and (rx, rz) right unit vectors
         * @param hw,hl   hull half width / half length; {@code height} its height
         * @param seatRight,seatForward where the rider sits relative to the hull centre
         */
        public boolean find(VoxelSource src, double cx, double planeY, double cz, double fx, double fz, double rx, double rz,
                            double hw, double hl, double height, double seatRight, double seatForward) {
            double side = hw + HALF + 0.25, along = Math.max(-hl + HALF, Math.min(hl - HALF, seatForward));
            int near = seatRight > 0 ? 1 : -1;
            for (int k = 0; k < 2; k++) {
                int sgn = k == 0 ? near : -near;
                if (onFloor(src, cx + rx * side * sgn + fx * along, planeY, cz + rz * side * sgn + fz * along)) { exit = sgn < 0 ? Exit.LEFT_DOOR : Exit.RIGHT_DOOR; return true; }
            }
            if (onFloor(src, cx - fx * (hl + HALF + 0.25), planeY, cz - fz * (hl + HALF + 0.25))) { exit = Exit.REAR; return true; }
            double sr = Math.max(-hw + HALF, Math.min(hw - HALF, seatRight));
            if (inAir(src, cx + rx * sr + fx * along, planeY + height + 0.05, cz + rz * sr + fz * along)) { exit = Exit.ROOF; return true; }
            if (inAir(src, cx, planeY + height + 0.5, cz)) { exit = Exit.ABOVE; return true; }
            return false;
        }

        /** Stand on the highest floor between one block above and three below {@code baseY}; false when none is safe. */
        boolean onFloor(VoxelSource src, double px, double baseY, double pz) {
            if (!gather(src, px, baseY - MAX_DROP - 1, pz, baseY + 1 + HEIGHT)) return false;
            double limit = baseY + 1.0 + EPS;
            for (int tries = 0; tries < 8; tries++) {
                double top = Double.NaN;
                for (int i = 0, n = boxes.size(); i < n; i++) {
                    double t = boxes.maxY(i);
                    if (t < limit && t >= baseY - MAX_DROP && inFootprint(i, px, pz) && (Double.isNaN(top) || t > top)) top = t;
                }
                if (Double.isNaN(top)) break;
                if (bodyFree(px, top, pz, top + HEIGHT)) return safeCells(src, px, top - 0.05, pz, top + HEIGHT) && accept(px, top, pz);
                limit = top - EPS;                     // something is in the way up here: try the next floor down
            }
            // no floor: water within reach is fine, as long as nothing solid or harmful is on the way down
            double surface = waterBelow(src, px, baseY, pz);
            return !Double.isNaN(surface) && bodyFree(px, surface, pz, baseY + HEIGHT) && safeCells(src, px, surface, pz, baseY + HEIGHT) && accept(px, baseY, pz);
        }

        /** Stand at exactly this height (on the roof, above the vehicle): free space, and a floor or water within reach below. */
        boolean inAir(VoxelSource src, double px, double y, double pz) {
            if (!gather(src, px, y - MAX_DROP - 1, pz, y + HEIGHT)) return false;
            if (!bodyFree(px, y, pz, y + HEIGHT)) return false;
            double floor = Double.NaN;
            for (int i = 0, n = boxes.size(); i < n; i++) {
                double t = boxes.maxY(i);
                if (t <= y + EPS && t >= y - MAX_DROP && inFootprint(i, px, pz) && (Double.isNaN(floor) || t > floor)) floor = t;
            }
            if (Double.isNaN(floor)) floor = waterBelow(src, px, y, pz);
            return !Double.isNaN(floor) && safeCells(src, px, floor - 0.05, pz, y + HEIGHT) && accept(px, y, pz);
        }

        private boolean accept(double px, double py, double pz) { x = px; y = py; z = pz; return true; }

        private boolean gather(VoxelSource src, double px, double minY, double pz, double maxY) {
            boxes.clear();
            return src.collect(fl(px - HALF), fl(minY), fl(pz - HALF), fl(px + HALF), fl(maxY), fl(pz + HALF), boxes);
        }

        private boolean inFootprint(int i, double px, double pz) {
            return boxes.maxX(i) > px - HALF + EPS && boxes.minX(i) < px + HALF - EPS && boxes.maxZ(i) > pz - HALF + EPS && boxes.minZ(i) < pz + HALF - EPS;
        }

        /** No collision box inside the player's box between y0 and y1. */
        private boolean bodyFree(double px, double y0, double pz, double y1) {
            for (int i = 0, n = boxes.size(); i < n; i++)
                if (boxes.maxY(i) > y0 + EPS && boxes.minY(i) < y1 - EPS && inFootprint(i, px, pz)) return false;
            return true;
        }

        /** No hazard in any block the player's box touches between y0 (the block stood on included) and y1. */
        private static boolean safeCells(VoxelSource src, double px, double y0, double pz, double y1) {
            for (int bx = fl(px - HALF); bx <= fl(px + HALF); bx++) for (int bz = fl(pz - HALF); bz <= fl(pz + HALF); bz++)
                for (int by = fl(y0); by <= fl(y1 - EPS); by++) if (src.hazard(bx, by, bz)) return false;
            return true;
        }

        /** Top of the first water block at most {@link #MAX_DROP} below y, or NaN. */
        private static double waterBelow(VoxelSource src, double px, double y, double pz) {
            for (int by = fl(y); by >= fl(y - MAX_DROP); by--) if (src.water(fl(px), by, fl(pz))) return by;
            return Double.NaN;
        }

        private static int fl(double v) { int i = (int) v; return v < i ? i - 1 : i; }
    }

    private final SuperbVehiclesPlugin plugin;
    private final Scanner scanner = new Scanner();
    private final Set<UUID> leaving = new HashSet<>();      // exits waiting for their tick; cleared on quit
    private final Location seatLoc = new Location(null, 0, 0, 0), clickLoc = new Location(null, 0, 0, 0), hullLoc = new Location(null, 0, 0, 0);

    public PassengerAndSafeDismountController(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    public void forget(UUID player) { leaving.remove(player); }
    public void clear() { leaving.clear(); }

    // ------------------------------------------------------------------ input

    /** The driver's keys into the vehicle's controls (cached PlayerInputEvent state + this tick's one-shot keys). */
    public void poll(Vehicle v, Controls controls) {
        Player driver = v.driver();
        controls.present = driver != null && !v.wreck;
        if (driver == null) { controls.held(null); return; }
        controls.held(plugin.seats().inputOf(driver));
        Location l = driver.getLocation(seatLoc);
        controls.lookYaw = l.getYaw(); controls.lookPitch = l.getPitch();
        plugin.seats().drainEdges(driver, controls);
    }

    // ------------------------------------------------------------------ seats

    /** Move every seat stand and click box to where its seat is now: the seat offsets ride the hull's pitch and roll. */
    public void syncSeats(Vehicle v) {
        double riderOffset = plugin.cfg("seats.rider-offset", 0.6);
        for (Vehicle.SeatSlot s : v.seats) {
            if (s.stand == null || !s.stand.isValid()) continue;
            Vector3d w = v.seatWorld(s);
            w.y += v.phys.visualYOffset;          // riders follow the eased body, not the physics step
            seatLoc.setWorld(v.world); seatLoc.set(w.x, w.y - riderOffset, w.z); seatLoc.setYaw((float) (v.pose.yaw + s.def.yawOffset)); seatLoc.setPitch(0f);
            Location standLoc = plugin.camera().place(v, s, seatLoc);   // chase view: the stand goes behind the vehicle
            if (s.stand.getPassengers().isEmpty()) { if (distanceSq(s.stand, standLoc) > 1e-6) s.stand.teleport(standLoc); }
            else s.stand.teleport(standLoc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            if (s.click != null && s.click.isValid()) {
                clickLoc.setWorld(v.world); clickLoc.set(w.x, w.y - s.def.height / 2.0 + 0.1, w.z);
                if (distanceSq(s.click, clickLoc) > 1e-6) s.click.teleport(clickLoc);
            }
        }
        if (v.hull != null && v.hull.isValid()) {
            Obb o = v.hull();
            float w = (float) Math.min(16, Math.max(o.half.x, o.half.z) * 2), h = (float) Math.min(16, o.half.y * 2);
            if (Math.abs(v.hull.getInteractionWidth() - w) > 1e-3) v.hull.setInteractionWidth(w);
            if (Math.abs(v.hull.getInteractionHeight() - h) > 1e-3) v.hull.setInteractionHeight(h);
            hullLoc.setWorld(v.world); hullLoc.set(o.center.x, o.center.y - o.half.y, o.center.z);
            if (distanceSq(v.hull, hullLoc) > 1e-6) v.hull.teleport(hullLoc);
        }
    }

    private double distanceSq(org.bukkit.entity.Entity e, Location to) {
        Location at = e.getLocation(scratch);
        return at.getWorld() != to.getWorld() ? Double.MAX_VALUE : at.distanceSquared(to);
    }
    private final Location scratch = new Location(null, 0, 0, 0);

    // ------------------------------------------------------------------ prompts

    /** "Right-click to enter" while a player on foot looks at a vehicle; every 10 ticks, config seats.prompts. */
    public void tickPrompts(int tick) {
        if (tick % 10 != 0 || !plugin.flag("seats.prompts", true) || plugin.vehicles().all().isEmpty()) return;
        double range = plugin.cfg("seats.enter-range", 4.5);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.isInsideVehicle() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (plugin.vehicles().nearest(p.getWorld(), p.getX(), p.getY(), p.getZ(), range + 8) == null) continue;
            Location eye = p.getEyeLocation();
            Vector d = eye.getDirection();
            var hit = plugin.vehicles().rayTrace(eye, new Vector3d(d.getX(), d.getY(), d.getZ()), range, null);
            if (hit == null || hit.vehicle().wreck) continue;
            plugin.messages().actionBar(p, "prompt-enter", Messages.str("name", hit.vehicle().displayName()));
        }
    }

    /** Shown once when a rider sits down. */
    public void promptExit(Player p) { if (plugin.flag("seats.prompts", true)) plugin.messages().actionBar(p, "prompt-exit"); }

    // ------------------------------------------------------------------ getting out

    /** Is the scanner the right way out here? Not for aircraft in the air: there the rider bails out. */
    public boolean handles(Vehicle v) {
        return plugin.flag("seats.dismount.safe", true) && !v.removed && (v.phys.onGround || v.phys.inWater || !v.type.vehicleClass.aircraft());
    }

    /**
     * A rider asked to get out (the dismount itself was cancelled): next tick they are taken off the seat and put
     * straight on the exit, so they never spend a tick where the game would have dropped them.
     */
    public void requestExit(Player p, Vehicle v) {
        if (!leaving.add(p.getUniqueId())) return;
        plugin.sched().later(1, () -> { leaving.remove(p.getUniqueId()); leave(p, v); });
    }

    private void leave(Player p, Vehicle v) {
        if (!p.isOnline() || plugin.vehicles().byPlayer(p) != v) return;
        Vehicle.SeatSlot s = v.seatOf(p);
        if (s == null || s.stand == null) return;
        Location out = exitFor(p, v, s);
        plugin.seats().forced(() -> s.stand.removePassenger(p));
        plugin.getServer().getPluginManager().callEvent(new VehicleExitEvent(v, p, s.index, false));
        plugin.hud().forgetPlayer(p);
        plugin.camera().release(p);
        if (out == null) { plugin.messages().actionBar(p, "no-safe-exit"); return; }
        p.teleport(out);
        p.setFallDistance(0);
        // leave with the vehicle's motion when that is harmless, standing still otherwise
        double speed = v.phys.speedBps();
        if (speed > 0.05 && speed <= plugin.cfg("seats.dismount-speed", 6.0)) p.setVelocity(new Vector(v.phys.vel.x / Units.TPS, 0, v.phys.vel.z / Units.TPS));
        else p.setVelocity(new Vector(0, 0, 0));
    }

    /** The safe exit for this rider, or null when no place around the vehicle passes. */
    public Location exitFor(Player p, Vehicle v, Vehicle.SeatSlot s) {
        Obb o = v.hull();
        Vector3d f = v.pose.forwardFlat(), r = v.pose.rightFlat();
        // the hull box is in model axes: a model drawn sideways has its length along X
        double a = Math.toRadians(180 - v.type.modelYaw), ca = Math.abs(Math.cos(a)), sa = Math.abs(Math.sin(a));
        double hw = ca * o.half.x + sa * o.half.z, hl = sa * o.half.x + ca * o.half.z;
        Vector3d seat = v.seatWorld(s);
        double dx = seat.x - o.center.x, dz = seat.z - o.center.z;
        VoxelSource src = plugin.vehicles().voxels(v.world);
        if (!scanner.find(src, o.center.x, o.center.y - o.half.y, o.center.z, f.x, f.z, r.x, r.z, hw, hl, o.half.y * 2, dx * r.x + dz * r.z, dx * f.x + dz * f.z)) return null;
        Location at = p.getLocation();
        return new Location(v.world, scanner.x, scanner.y, scanner.z, at.getYaw(), at.getPitch());
    }

    public Exit lastExit() { return scanner.exit; }
}
