package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BoneRole;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.pack.Mat;
import fi.alavesa.superbvehicles.physics.BlockCollider;
import fi.alavesa.superbvehicles.physics.BoatPhysics;
import fi.alavesa.superbvehicles.physics.Controls;
import fi.alavesa.superbvehicles.physics.HelicopterPhysics;
import fi.alavesa.superbvehicles.physics.Obb;
import fi.alavesa.superbvehicles.physics.PhysicsState;
import fi.alavesa.superbvehicles.physics.PlanePhysics;
import fi.alavesa.superbvehicles.physics.StaticPhysics;
import fi.alavesa.superbvehicles.physics.TrackedPhysics;
import fi.alavesa.superbvehicles.physics.VehiclePhysics;
import fi.alavesa.superbvehicles.physics.WheeledPhysics;
import fi.alavesa.superbvehicles.render.AnimationPlayer;
import fi.alavesa.superbvehicles.render.Pose;
import fi.alavesa.superbvehicles.render.Procedural;
import fi.alavesa.superbvehicles.render.VehicleRenderer;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One live vehicle: its pose and physics state, health/fuel/ammo, seats (marker stands the riders sit on + click
 * boxes), mounted weapons, and the entities that show it. Ticked by {@link VehicleManager}. Survives chunk
 * unloads as a record ({@link Persistence}); entities are never saved by the server (no duplicates on restart).
 */
public final class Vehicle {
    public static final class SeatSlot {
        public final int index; public final VehicleType.Seat def;
        public ArmorStand stand; public Interaction click;
        public int weaponIndex;
        public int enteredTick;
        SeatSlot(int index, VehicleType.Seat def) { this.index = index; this.def = def; }
        public Player rider() { if (stand == null || !stand.isValid()) return null; for (Entity e : stand.getPassengers()) if (e instanceof Player p) return p; return null; }
        public boolean free() { return rider() == null; }
    }

    public final SuperbVehiclesPlugin plugin;
    public final UUID id;
    public final VehicleType type;
    public World world;
    public final Pose pose;
    public final PhysicsState phys = new PhysicsState();
    public final Controls controls = new Controls();
    public double health, fuel;
    public String skin = "default";
    public UUID owner;
    public boolean locked;
    public boolean wreck, removed, dormant;
    public int wreckTicks, fireTicks, burnTicks;
    public long age;
    public final SeatSlot[] seats;
    public final Map<String, MountedWeapon> weapons = new LinkedHashMap<>();
    public final Map<String, Rig.BoneDelta> deltas = new HashMap<>();
    public final VehicleRenderer renderer = new VehicleRenderer(this);
    public final AnimationPlayer anim = new AnimationPlayer();
    public ArmorStand root;
    public Interaction hull;
    private VehiclePhysics physics;
    private Rig boundRig;
    public double[] hitboxHalfPx = {16, 16, 16}, hitboxCenterPx = {0, 16, 0};
    public final Map<String, double[]> wheelLocators = new LinkedHashMap<>();   // name -> rest px
    public int soundTick, lastHudTick;
    public String lastState = "";
    public boolean debugHitbox;

    public Vehicle(SuperbVehiclesPlugin plugin, UUID id, VehicleType type, World world, Pose pose) {
        this.plugin = plugin; this.id = id; this.type = type; this.world = world; this.pose = pose;
        pose.modelYaw = type.modelYaw;
        health = type.health; fuel = type.fuelCapacity * type.initialFuel;
        seats = new SeatSlot[type.seats.size()];
        for (int i = 0; i < seats.length; i++) seats[i] = new SeatSlot(i, type.seats.get(i));
        for (var e : type.weapons.entrySet()) weapons.put(e.getKey(), new MountedWeapon(this, e.getValue()));
        physics = createPhysics();
        bindRig();
    }

    private VehiclePhysics createPhysics() {
        return switch (type.vehicleClass) {
            case WHEELED -> new WheeledPhysics(this);
            case TRACKED -> new TrackedPhysics(this);
            case BOAT -> new BoatPhysics(this);
            case HELICOPTER -> new HelicopterPhysics(this);
            case PLANE -> new PlanePhysics(this);
            case STATIC -> new StaticPhysics(this);
        };
    }

    public VehiclePhysics physics() { return physics; }
    public Rig rig() { return plugin.types().rigOf(type); }

    /** Read hitbox and wheel positions from the rig (hitbox_* bones, else the model bounds; YAML overrides win). */
    public void bindRig() {
        Rig rig = rig();
        boundRig = rig;
        wheelLocators.clear();
        if (type.hitboxSize != null) {
            hitboxHalfPx = new double[]{type.hitboxSize[0] * 8, type.hitboxSize[1] * 8, type.hitboxSize[2] * 8};
            hitboxCenterPx = new double[]{type.hitboxOffset[0] * 16, type.hitboxOffset[1] * 16 + hitboxHalfPx[1], type.hitboxOffset[2] * 16};
        } else if (rig != null) {
            double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
            boolean any = false;
            for (BbModel.Bone b : rig.model.bonesStartingWith("hitbox")) for (BbModel.Cube c : b.cubes) { any = true; for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], Math.min(c.from[i], c.to[i])); mx[i] = Math.max(mx[i], Math.max(c.from[i], c.to[i])); } }
            double[] bb = any ? new double[]{mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]} : rig.model.bounds();
            if (!any) { // without hitbox bones, leave out rotor blades / wings extremes a little
                double shrink = type.vehicleClass.aircraft() ? 0.6 : 0.9;
                double cx = (bb[0] + bb[3]) / 2, cz = (bb[2] + bb[5]) / 2;
                bb[0] = cx + (bb[0] - cx) * shrink; bb[3] = cx + (bb[3] - cx) * shrink; bb[2] = cz + (bb[2] - cz) * shrink; bb[5] = cz + (bb[5] - cz) * shrink;
            }
            hitboxHalfPx = new double[]{Math.max(4, (bb[3] - bb[0]) / 2), Math.max(4, (bb[4] - bb[1]) / 2), Math.max(4, (bb[5] - bb[2]) / 2)};
            hitboxCenterPx = new double[]{(bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, (bb[2] + bb[5]) / 2};
            Map<String, double[][]> cache = new HashMap<>();
            for (BbModel.Locator l : rig.model.locators.values()) if (BoneRole.of(l.name) == BoneRole.WHEEL) wheelLocators.put(l.name.toLowerCase(Locale.ROOT), rig.locatorPosition(l, null, cache));
            if (wheelLocators.isEmpty()) for (BbModel.Bone b : rig.model.bones.values()) if (BoneRole.of(b.name) == BoneRole.WHEEL) wheelLocators.put(b.name.toLowerCase(Locale.ROOT), rig.bonePivot(b, null, cache));
        }
        anim.bind(rig == null ? null : rig.model);
        physics.rigBound();
    }

    public boolean rigChanged() { return boundRig != rig(); }

    // ------------------------------------------------------------------ geometry

    public double scale() { return type.scale; }
    public double maxHealth() { return type.health; }
    public Location location() { return pose.location(world); }
    public Vector3d position() { return pose.position(); }

    /** The hull OBB in the world. */
    public Obb hull() { return hullAt(pose); }

    /** The hull OBB for any pose (the physics controllers evaluate it at intermediate poses). */
    public Obb hullAt(Pose at) {
        Vector3d c = at.toWorld(hitboxCenterPx, type.scale);
        Vector3d half = new Vector3d(hitboxHalfPx[0] / 16 * type.scale, hitboxHalfPx[1] / 16 * type.scale, hitboxHalfPx[2] / 16 * type.scale);
        return new Obb(c, half, at.rotation());
    }

    /** World position of a locator (or bone pivot of that name) in the CURRENT animated pose; null when absent. */
    public Vector3d locatorWorld(String name) {
        double[] px = locatorPx(name);
        return px == null ? null : pose.toWorld(px, type.scale);
    }

    public double[] locatorPx(String name) {
        Rig rig = rig();
        if (rig == null || name == null) return null;
        Map<String, double[][]> cache = new HashMap<>();
        BbModel.Locator l = rig.model.locator(name);
        if (l != null) return rig.locatorPosition(l, deltas, cache);
        BbModel.Bone b = rig.model.bone(name);
        if (b != null) return rig.bonePivot(b, deltas, cache);
        return null;
    }

    /** Forward direction (world) of a bone in the current pose, or the vehicle's forward. */
    public Vector3d boneForward(String name) {
        Rig rig = rig();
        if (rig != null && name != null) { BbModel.Bone b = rig.model.bone(name); if (b != null) return pose.dirToWorld(rig.boneForward(b, deltas, new HashMap<>())); }
        return pose.forward();
    }

    // ------------------------------------------------------------------ seats

    public Player driver() { for (SeatSlot s : seats) if (s.def.role == VehicleType.SeatRole.DRIVER) return s.rider(); return null; }
    public SeatSlot driverSeat() { for (SeatSlot s : seats) if (s.def.role == VehicleType.SeatRole.DRIVER) return s; return seats[0]; }
    public List<Player> occupants() { List<Player> out = new ArrayList<>(); for (SeatSlot s : seats) { Player p = s.rider(); if (p != null) out.add(p); } return out; }
    public SeatSlot seatOf(Player p) { for (SeatSlot s : seats) if (s.rider() == p) return s; return null; }
    public SeatSlot seatOfEntity(Entity e) { for (SeatSlot s : seats) if (s.stand == e || s.click == e) return s; return null; }
    public boolean occupied() { for (SeatSlot s : seats) if (!s.free()) return true; return false; }

    /** Seat world position (the point the rider sits at) in the current pose. */
    public Vector3d seatWorld(SeatSlot s) {
        double[] px = locatorPx(s.def.locator);
        if (px == null) px = new double[]{0, hitboxCenterPx[1] + hitboxHalfPx[1] * 0.4, 0};
        return pose.toWorld(px[0] + s.def.offset[0], px[1] + s.def.offset[1], px[2] + s.def.offset[2], type.scale);
    }

    // ------------------------------------------------------------------ entities

    public boolean spawned() { return root != null && root.isValid(); }

    public void spawnEntities() {
        if (spawned()) return;
        Location at = pose.location(world); at.setYaw(0);
        root = VehicleRenderer.spawnRoot(this, at);
        hull = at.getWorld().spawn(at, Interaction.class, c -> {
            c.setResponsive(true); c.setPersistent(false);
            c.addScoreboardTag(Keys.TAG_ENTITY); c.addScoreboardTag(Keys.TAG_CLICK);
            c.getPersistentDataContainer().set(plugin.keys().vehicleId, PersistentDataType.STRING, id.toString());
            c.getPersistentDataContainer().set(plugin.keys().seatIndex, PersistentDataType.INTEGER, -1);
        });
        for (SeatSlot s : seats) {
            s.stand = at.getWorld().spawn(at, ArmorStand.class, a -> {
                a.setInvisible(true); a.setMarker(true); a.setSmall(true); a.setGravity(false); a.setBasePlate(false); a.setInvulnerable(true); a.setPersistent(false); a.setSilent(true); a.setCollidable(false);
                a.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.values());
                a.addScoreboardTag(Keys.TAG_ENTITY); a.addScoreboardTag(Keys.TAG_SEAT);
                a.getPersistentDataContainer().set(plugin.keys().vehicleId, PersistentDataType.STRING, id.toString());
                a.getPersistentDataContainer().set(plugin.keys().seatIndex, PersistentDataType.INTEGER, s.index);
            });
            s.click = at.getWorld().spawn(at, Interaction.class, c -> {
                c.setResponsive(true); c.setPersistent(false);
                c.setInteractionWidth((float) s.def.width); c.setInteractionHeight((float) s.def.height);
                c.addScoreboardTag(Keys.TAG_ENTITY); c.addScoreboardTag(Keys.TAG_CLICK);
                c.getPersistentDataContainer().set(plugin.keys().vehicleId, PersistentDataType.STRING, id.toString());
                c.getPersistentDataContainer().set(plugin.keys().seatIndex, PersistentDataType.INTEGER, s.index);
            });
        }
        renderer.rebuild();
        updateDeltas();
        renderer.update(true);
        tickSeats();
        dormant = false;
    }

    public void despawnEntities() {
        for (SeatSlot s : seats) {
            if (s.stand != null) { for (Entity p : new ArrayList<>(s.stand.getPassengers())) plugin.seats().forced(() -> s.stand.removePassenger(p)); s.stand.remove(); s.stand = null; }
            if (s.click != null) { s.click.remove(); s.click = null; }
        }
        renderer.clear();
        if (hull != null) { hull.remove(); hull = null; }
        if (root != null) { for (Entity p : new ArrayList<>(root.getPassengers())) p.remove(); root.remove(); root = null; }
    }

    /** All entities belonging to this vehicle. */
    public List<Entity> entities() {
        List<Entity> out = new ArrayList<>();
        if (root != null) out.add(root);
        if (hull != null) out.add(hull);
        for (SeatSlot s : seats) { if (s.stand != null) out.add(s.stand); if (s.click != null) out.add(s.click); }
        out.addAll(renderer.entities());
        return out;
    }

    public boolean owns(Entity e) {
        if (e == null) return false;
        String s = e.getPersistentDataContainer().get(plugin.keys().vehicleId, PersistentDataType.STRING);
        return s != null && s.equals(id.toString());
    }

    // ------------------------------------------------------------------ tick

    public void tick(BlockCollider collider, int serverTick) {
        age++;
        if (!spawned()) return;
        if (rigChanged()) { bindRig(); renderer.rebuild(); }
        // controls from the driver
        Player driver = driver();
        controls.present = driver != null && !wreck;
        if (driver != null) {
            controls.held(plugin.seats().inputOf(driver));
            controls.lookYaw = driver.getLocation().getYaw(); controls.lookPitch = driver.getLocation().getPitch();
            plugin.seats().drainEdges(driver, controls);
        } else controls.held(null);
        // physics (guarded: a NaN or a runaway speed freezes the vehicle and logs once instead of flinging it away)
        if (!dormant) {
            Pose before = pose.copy();
            phys.activeClamp = "";
            physics.tick(controls, collider);
            sanity(before);
        }
        controls.clearEdges();
        if (plugin.vehicles().debugging(this)) plugin.vehicles().debugTick(this);
        // fuel (consumption: units per second at full load)
        if (plugin.flag("fuel.enabled", true) && phys.engineOn && type.fuelCapacity > 0) {
            fuel -= type.fuelConsumption * fi.alavesa.superbvehicles.physics.Units.DT * (0.25 + 0.75 * Math.min(1, Math.abs(phys.throttle) + Math.abs(phys.collective)));
            if (fuel <= 0) { fuel = 0; if (plugin.flag("fuel.empty-engine-off", true)) { phys.engineOn = false; Player d = driver(); if (d != null) plugin.messages().actionBar(d, "fuel-empty"); } }
        }
        // weapons
        for (MountedWeapon w : weapons.values()) w.tick(serverTick);
        // fire & wreck
        if (wreck) { wreckTicks--; if (wreckTicks <= 0) { plugin.vehicles().remove(this, false); return; } burn(); }
        else if (plugin.flag("damage.enabled", true) && health / maxHealth() < plugin.cfg("damage.fire-below", 0.2) && health > 0) { burn(); if (age % 20 == 0) plugin.damage().burn(this, plugin.cfg("damage.fire-damage-per-second", 0.5)); }
        if (removed) return;
        // animation + rendering
        updateDeltas();
        renderer.update(false);
        tickSeats();
        sounds();
    }

    private void burn() {
        if (age % 3 != 0) return;
        Vector3d c = hull().center;
        world.spawnParticle(Particle.LARGE_SMOKE, c.x, c.y + hitboxHalfPx[1] / 16 * type.scale, c.z, 2, hitboxHalfPx[0] / 32 * type.scale, 0.2, hitboxHalfPx[2] / 32 * type.scale, 0.02);
        if (wreck || health / maxHealth() < 0.1) world.spawnParticle(Particle.FLAME, c.x, c.y + 0.3, c.z, 2, hitboxHalfPx[0] / 32 * type.scale, 0.2, hitboxHalfPx[2] / 32 * type.scale, 0.02);
    }

    public void updateDeltas() {
        Rig rig = rig();
        for (Rig.BoneDelta d : deltas.values()) d.reset();
        if (rig == null) return;
        String state = physics.animationState();
        lastState = state;
        anim.state(clipFor(state), state, clipRate(state));   // same clip again: only the playback rate follows the speed
        anim.tick(rig, deltas);
        Procedural.apply(this, rig, deltas);
    }

    /**
     * Playback rate of the state clip. Driving clips (drive, reverse, left, right ...) follow the speed: 1.0 at
     * `animation-speed` blocks/s (YAML, default half the max speed), so a wheel-spin animation keyed in Blockbench
     * matches the ground; other clips play at Blockbench speed.
     */
    double clipRate(String state) {
        if (!(state.startsWith("drive") || state.startsWith("reverse") || state.equals("left") || state.equals("right"))) return 1.0;
        double ref = Math.max(0.1, type.physics("animation-speed", type.raw("animation-speed", type.maxSpeed / 2)));
        return Math.max(0.05, Math.min(4.0, Math.abs(phys.speed) / ref));
    }

    /** The clip for a state: YAML animations: {state: name}, else an animation named like the state, else a fallback chain. */
    public BbModel.Animation clipFor(String state) {
        Rig rig = rig();
        if (rig == null) return null;
        String[] chain = switch (state) {
            case "reverse", "reverse_left", "reverse_right" -> new String[]{state, "reverse", "drive", "idle"};
            case "left", "right" -> new String[]{state, "drive", "idle"};
            case "drive" -> new String[]{"drive", "driving", "move", "idle"};
            case "hover", "ascend", "descend", "fly" -> new String[]{state, "fly", "hover", "flying", "idle"};
            case "start" -> new String[]{"start", "startup", "idle"};
            case "wreck" -> new String[]{"wreck", "destroyed"};
            default -> new String[]{state, "idle"};
        };
        for (String s : chain) {
            String mapped = type.animation(s);
            if (mapped != null) { BbModel.Animation a = findClip(rig.model, mapped); if (a != null) return a; }
        }
        for (String s : chain) { BbModel.Animation a = findClip(rig.model, s); if (a != null) return a; }
        return null;
    }

    public static BbModel.Animation findClip(BbModel m, String name) {
        if (name == null) return null;
        BbModel.Animation a = m.animations.get(name);
        if (a != null) return a;
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (BbModel.Animation x : m.animations.values()) if (x.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(n)) return x;
        return null;
    }

    /** Teleport seat stands and click boxes to their current world positions. */
    public void tickSeats() {
        double riderOffset = plugin.cfg("seats.rider-offset", 0.6);
        for (SeatSlot s : seats) {
            if (s.stand == null || !s.stand.isValid()) continue;
            Vector3d w = seatWorld(s);
            Location standLoc = new Location(world, w.x, w.y - riderOffset, w.z, (float) (pose.yaw + s.def.yawOffset), 0f);
            if (s.stand.getPassengers().isEmpty()) { if (s.stand.getLocation().distanceSquared(standLoc) > 1e-6) s.stand.teleport(standLoc); }
            else s.stand.teleport(standLoc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            if (s.click != null && s.click.isValid()) {
                Location cl = new Location(world, w.x, w.y - s.def.height / 2.0 + 0.1, w.z);
                if (s.click.getLocation().distanceSquared(cl) > 1e-6) s.click.teleport(cl);
            }
        }
        if (hull != null && hull.isValid()) {
            Obb o = hull();
            float w = (float) Math.min(16, Math.max(o.half.x, o.half.z) * 2), h = (float) Math.min(16, o.half.y * 2);
            if (Math.abs(hull.getInteractionWidth() - w) > 1e-3) hull.setInteractionWidth(w);
            if (Math.abs(hull.getInteractionHeight() - h) > 1e-3) hull.setInteractionHeight(h);
            Location hl = new Location(world, o.center.x, o.center.y - o.half.y, o.center.z);
            if (hull.getLocation().distanceSquared(hl) > 1e-6) hull.teleport(hl);
        }
    }

    private void sounds() {
        int interval = (int) plugin.cfg("sounds.engine-interval-ticks", 6);
        if (!phys.engineOn || interval <= 0 || ++soundTick % interval != 0) return;
        String key = type.sound("engine", defaultEngineSound());
        if (key == null || key.isBlank() || key.equals("none")) return;
        double load = type.vehicleClass.aircraft() ? Math.max(phys.spool, 0.1) : 0.6 + 0.4 * Math.min(1.0, Math.abs(phys.rpm - phys.idleRpm) / Math.max(1, phys.maxRpm - phys.idleRpm));
        float pitch = (float) Pose.clamp(0.5 + load * (type.vehicleClass.aircraft() ? 1.0 : 1.2), 0.5, 2.0);
        float vol = (float) (plugin.cfg("sounds.volume", 1.0) * (type.vehicleClass.aircraft() ? 0.4 + 0.6 * phys.spool : 0.6));
        playSound(key, vol, pitch);
    }

    private String defaultEngineSound() {
        return switch (type.vehicleClass) { case HELICOPTER -> "minecraft:entity.ender_dragon.flap"; case PLANE -> "minecraft:entity.phantom.flap"; case BOAT -> "minecraft:entity.boat.paddle_water"; case STATIC -> "none"; default -> "minecraft:entity.minecart.riding"; };
    }

    public void playSound(String key, float volume, float pitch) {
        if (key == null || key.isBlank() || key.equals("none")) return;
        Location l = location();
        try { world.playSound(l, key, SoundCategory.NEUTRAL, volume, pitch); } catch (Exception ignored) { }
    }

    public double fuelFraction() { return type.fuelCapacity <= 0 ? 1 : Math.max(0, Math.min(1, fuel / type.fuelCapacity)); }
    /** True when this vehicle uses fuel and the tank is empty. */
    public boolean outOfFuel() { return plugin.flag("fuel.enabled", true) && type.fuelCapacity > 0 && fuel <= 0; }
    public boolean engineAvailable() { return !wreck && health > 0 && !outOfFuel(); }

    private int insaneLogTick = -10000;

    /** NaN/Infinity guard, per-tick position delta cap, and the emergency freeze above 3x the configured max speed. */
    private void sanity(Pose before) {
        boolean finite = Double.isFinite(pose.x) && Double.isFinite(pose.y) && Double.isFinite(pose.z) && Double.isFinite(pose.yaw) && Double.isFinite(pose.pitch) && Double.isFinite(pose.roll)
            && Double.isFinite(phys.vel.x) && Double.isFinite(phys.vel.y) && Double.isFinite(phys.vel.z);
        double moved = Math.sqrt((pose.x - before.x) * (pose.x - before.x) + (pose.y - before.y) * (pose.y - before.y) + (pose.z - before.z) * (pose.z - before.z));
        double maxSpeed = Math.max(1, type.maxSpeed);
        double maxDelta = fi.alavesa.superbvehicles.physics.Units.perTick(Math.max(maxSpeed * 3, 10));
        String why = !finite ? "non-finite position or velocity" : phys.vel.length() > maxSpeed * 3 ? String.format(java.util.Locale.ROOT, "speed %.1f b/s is over 3x max-speed %.1f", phys.vel.length(), maxSpeed)
            : moved > maxDelta ? String.format(java.util.Locale.ROOT, "moved %.2f blocks in one tick", moved) : null;
        if (why == null) return;
        pose.set(before);
        phys.vel.set(0, 0, 0);
        phys.speed = 0;
        if (age - insaneLogTick > 200) { insaneLogTick = (int) age; plugin.getLogger().warning("Vehicle " + this + " frozen for a tick: " + why + " - please report with /sbv debug physics output"); }
    }
    /** Engine power multiplier from damage. */
    public double powerFactor() { double hp = health / maxHealth(); return hp < plugin.cfg("damage.engine-damage-below", 0.3) ? 0.5 : 1.0; }

    public String displayName() { return type.displayName; }
    @Override public String toString() { return type.id + "@" + id.toString().substring(0, 8); }
}
