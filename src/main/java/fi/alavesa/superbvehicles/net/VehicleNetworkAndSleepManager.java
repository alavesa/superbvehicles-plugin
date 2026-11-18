package fi.alavesa.superbvehicles.net;

import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Which vehicles are simulated, and how their motion reaches the clients. The server alone moves vehicles: clients
 * only send key states (PlayerInputEvent), never positions, so speed and collisions cannot be spoofed.
 * <p><b>Sleep.</b> A vehicle is ACTIVE while it is occupied, moving faster than {@link #EPSILON}, in the air, hit
 * something within the last {@link #REST_TICKS} ticks, or still has something running (engine, wreck fire, weapon
 * timers, an animation clip, the autopilot). After {@link #REST_TICKS} ticks at rest it goes to SLEEP: no physics, no
 * rendering, no seat teleports - it is not ticked at all. It wakes when somebody enters or clicks it, something
 * damages or pushes it, a block changes near it, or the periodic look at the ground under it finds a change.
 * <p><b>Bundles.</b> Everything the server sends about a vehicle's entities in one tick (root position, every part's
 * transformation, seat positions) is collected per connection and sent as one {@code ClientboundBundlePacket} when
 * the tick ends, so the client applies it in a single frame and no part is ever a tick behind the others. The parts
 * themselves are item displays with interpolation and teleport durations of {@link #interpolationTicks} /
 * {@link #teleportTicks} (2-3 ticks) and delay 0: the client slides between states every frame.
 * <p>The packet classes are server internals, reached by reflection once at start-up; when they cannot be resolved the
 * packets go out unbundled and one line in the log says so.
 */
public final class VehicleNetworkAndSleepManager implements Listener {
    public enum State { ACTIVE, SLEEP }

    /** Blocks/s below which a vehicle counts as standing still. */
    public static final double EPSILON = 0.001;
    /** Ticks at rest before sleeping; also how long a collision keeps a vehicle awake. */
    public static final int REST_TICKS = 20;
    private static final int SLEEP_CHECK = 20, GROUND_CHECK = 40;
    static final String HANDLER = "superbvehicles_bundle";

    private final SuperbVehiclesPlugin plugin;
    private Vehicle[] roster = new Vehicle[0];
    private boolean rosterDirty = true;
    private int activeCount, sleepingCount;

    private final PacketAccess access;
    private final ChannelAccess channels;
    private volatile int[] ids = new int[0];             // entity ids of every vehicle entity, sorted; swapped, never mutated
    private boolean idsDirty;
    private final Map<UUID, BundleHandler> handlers = new HashMap<>();   // removed on quit and on disable

    public VehicleNetworkAndSleepManager(SuperbVehiclesPlugin plugin) {
        this.plugin = plugin;
        PacketAccess a = null; ChannelAccess c = null;
        if (plugin.flag("network.bundle", true)) {
            try { a = new ReflectivePackets(); c = new ChannelAccess(); }
            catch (Throwable t) { plugin.getLogger().warning("Packet bundling is off (server internals not as expected: " + t + "). Vehicles still work; parts may update a frame apart."); a = null; }
        }
        access = a; channels = c;
    }

    public boolean bundling() { return access != null; }
    public int interpolationTicks() { return clampTicks((int) plugin.cfg("network.interpolation-ticks", 2)); }
    public int teleportTicks() { return clampTicks((int) plugin.cfg("network.teleport-ticks", 2)); }
    private static int clampTicks(int t) { return t < 1 ? 1 : Math.min(t, 5); }
    public int activeCount() { return activeCount; }
    public int sleepingCount() { return sleepingCount; }

    public void start() { for (Player p : plugin.getServer().getOnlinePlayers()) inject(p); }

    public void shutdown() {
        for (BundleHandler h : handlers.values()) h.remove();
        handlers.clear();
        roster = new Vehicle[0]; rosterDirty = true;
        ids = new int[0];
    }

    // ------------------------------------------------------------------ sleep

    /** The set of vehicles (or their entities) changed: the roster and the entity-id list are rebuilt before the next use. */
    public void membershipChanged() { rosterDirty = true; idsDirty = true; }

    public State state(Vehicle v) { return v.asleep ? State.SLEEP : State.ACTIVE; }

    public void wake(Vehicle v) {
        if (v == null) return;
        v.asleep = false; v.restTicks = 0;
    }

    /** One server tick: active vehicles go through {@code ticker}; sleeping ones are only looked at now and then. */
    public void tick(int tick, Consumer<Vehicle> ticker) {
        if (rosterDirty) { roster = plugin.vehicles().all().toArray(new Vehicle[0]); rosterDirty = false; }
        Vehicle[] all = roster;
        int active = 0, sleeping = 0;
        for (Vehicle v : all) {
            if (v.removed || v.dormant) continue;
            if (v.asleep) {
                if ((tick + v.sleepSlot) % SLEEP_CHECK == 0 && disturbed(v, tick)) wake(v);
                if (v.asleep) { sleeping++; continue; }
            }
            active++;
            ticker.accept(v);
            if (v.removed || v.dormant) continue;
            if (restful(v)) { if (++v.restTicks >= restTicks()) sleep(v); } else v.restTicks = 0;
        }
        activeCount = active; sleepingCount = sleeping;
    }

    private int restTicks() { return Math.max(1, (int) plugin.cfg("physics.sleep.rest-ticks", REST_TICKS)); }

    /** Nothing left to simulate or show. */
    private boolean restful(Vehicle v) { return awakeReason(v) == null; }

    /**
     * Why this vehicle has to stay awake, or null when it may sleep (shown by /sbv debug perf). A looping state clip
     * (an idle animation) does not keep a vehicle awake unless physics.sleep.keep-animations is on: asleep, it holds
     * its last frame.
     */
    public String awakeReason(Vehicle v) {
        if (!plugin.flag("physics.sleep.enabled", true)) return "sleep is disabled";
        if (!v.spawned()) return "not spawned";
        if (v.wreck) return "wreck";
        if (v.phys.engineOn) return "engine running";
        if (v.autopiloting()) return "autopilot";
        if (v.phys.lastImpact > 0) return "collided";
        if (v.phys.airborne) return "in the air";
        if (v.phys.vel.lengthSquared() > EPSILON * EPSILON) return "moving";
        if (!v.physics().atRest()) return "settling";
        if (v.maxHealth() > 0 && v.health / v.maxHealth() < plugin.cfg("damage.fire-below", 0.2)) return "burning";
        if (v.debugHitbox) return "debug hitbox";
        if (plugin.flag("physics.sleep.keep-animations", false) ? !v.anim.idle() : !v.anim.settled()) return "animation playing";
        for (MountedWeapon w : v.weapons.values()) if (w.cooldown > 0 || w.reloading > 0 || w.recoilTicks > 0 || w.triggerHeld) return "weapon busy";
        return v.occupied() ? "occupied" : null;
    }

    private void sleep(Vehicle v) {
        v.asleep = true;
        v.phys.vel.set(0, 0, 0); v.phys.speed = 0; v.phys.yawRate = 0;
    }

    /** Looked at every {@link #SLEEP_CHECK} ticks while asleep: anything that calls for simulation again? */
    private boolean disturbed(Vehicle v, int tick) {
        if (!v.spawned() || v.wreck || v.occupied() || v.phys.engineOn || v.autopiloting()) return true;
        if (v.phys.vel.lengthSquared() > EPSILON * EPSILON) return true;                       // pushed
        if (v.rigChanged()) return true;
        return (tick + v.sleepSlot) % GROUND_CHECK == 0 && v.physics().restDisturbed();        // the ground changed without an event
    }

    /** A block changed here: sleeping vehicles standing near it wake up. */
    public void wakeNear(World world, double x, double y, double z, double radius) {
        if (sleepingCount == 0) return;
        for (Vehicle v : roster) {
            if (!v.asleep || v.world != world) continue;
            double r = radius + v.reach();
            double dx = v.pose.x - x, dy = v.pose.y - y, dz = v.pose.z - z;
            if (dx * dx + dy * dy + dz * dz <= r * r) wake(v);
        }
    }
    private void wakeNear(Block b) { wakeNear(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 3); }
    private void wakeNear(Location l, double radius) { wakeNear(l.getWorld(), l.getX(), l.getY(), l.getZ(), radius); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockBreakEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockPlaceEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockBurnEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockFadeEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(LeavesDecayEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(EntityChangeBlockEvent e) { wakeNear(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockPistonExtendEvent e) { wakeNear(e.getBlock().getLocation(), 16); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockPistonRetractEvent e) { wakeNear(e.getBlock().getLocation(), 16); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(BlockExplodeEvent e) { wakeNear(e.getBlock().getLocation(), 12); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(EntityExplodeEvent e) { wakeNear(e.getLocation(), 12); }

    /** The entities of a vehicle were unloaded with their chunk: the record stays, the vehicle goes dormant. */
    @EventHandler public void on(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (!en.getScoreboardTags().contains(Keys.TAG_ROOT)) continue;
            Vehicle v = plugin.vehicles().byEntity(en);
            if (v != null) plugin.sched().later(1, () -> plugin.vehicles().parkIfUnloaded(v));
        }
    }

    // ------------------------------------------------------------------ network

    @EventHandler public void on(PlayerJoinEvent e) { inject(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR) public void on(PlayerQuitEvent e) {
        BundleHandler h = handlers.remove(e.getPlayer().getUniqueId());
        if (h != null) h.remove();
    }

    private void inject(Player p) {
        if (access == null) return;
        try {
            Channel ch = channels.of(p);
            if (ch == null || ch.pipeline().get(HANDLER) != null || ch.pipeline().get("packet_handler") == null) return;
            BundleHandler h = new BundleHandler(access, this::vehicleEntity, ch);
            ch.pipeline().addBefore("packet_handler", HANDLER, h);
            handlers.put(p.getUniqueId(), h);
        } catch (Throwable t) { plugin.getLogger().warning("Could not bundle vehicle packets for " + p.getName() + ": " + t); }
    }

    private boolean vehicleEntity(int id) { return Arrays.binarySearch(ids, id) >= 0; }

    /** End of the server tick: every connection sends what it collected about vehicles this tick as one bundle. */
    @EventHandler public void on(com.destroystokyo.paper.event.server.ServerTickEndEvent e) {
        if (access == null) return;
        if (idsDirty) {
            idsDirty = false;
            int n = 0; int[] next = new int[64];
            for (Vehicle v : plugin.vehicles().all()) {
                if (!v.spawned()) continue;
                for (Entity en : v.entities()) { if (n == next.length) next = Arrays.copyOf(next, n * 2); next[n++] = en.getEntityId(); }
            }
            next = Arrays.copyOf(next, n);
            Arrays.sort(next);
            ids = next;
        }
        if (ids.length == 0 || handlers.isEmpty()) return;
        for (BundleHandler h : handlers.values()) h.endTick();
    }

    // ------------------------------------------------------------------ packets

    /** What the bundler needs to know about the server's packet classes. */
    public interface PacketAccess {
        /** The entity a movement or metadata packet is about, or -1 for any other object. */
        int entityId(Object packet);
        /** A bundle packet around these packets (the list is not kept by the caller). */
        Object bundle(List<Object> packets);
    }

    public interface IdSet { boolean contains(int entityId); }

    /**
     * Sits in a player's pipeline in front of the packet encoder. Movement and metadata packets about vehicle entities
     * are held back; {@link #endTick} (queued on the channel's event loop after everything the tick wrote) sends them
     * as one bundle. Everything else passes straight through. Any failure sends the packets unbundled.
     */
    public static final class BundleHandler extends ChannelOutboundHandlerAdapter {
        static final int MAX_BUNDLE = 2048;                 // the client accepts up to 4096 packets in a bundle
        private final PacketAccess access;
        private final IdSet vehicles;
        private final Channel channel;
        private final ArrayList<Object> packets = new ArrayList<>(64);
        private final ArrayList<ChannelPromise> promises = new ArrayList<>(64);
        private final Runnable flushTask = this::flushBundle;
        private ChannelHandlerContext ctx;

        public BundleHandler(PacketAccess access, IdSet vehicles, Channel channel) { this.access = access; this.vehicles = vehicles; this.channel = channel; }

        @Override public void handlerAdded(ChannelHandlerContext ctx) { this.ctx = ctx; }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) { release(ctx); this.ctx = null; }

        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            int id;
            try { id = access.entityId(msg); } catch (Throwable t) { id = -1; }
            if (id < 0 || !vehicles.contains(id)) { ctx.write(msg, promise); return; }
            packets.add(msg); promises.add(promise);
            if (packets.size() >= MAX_BUNDLE) flushBundle();
        }

        /** Called from the server thread when the tick is over. */
        public void endTick() { if (channel.isActive()) channel.eventLoop().execute(flushTask); }

        /** Called from the server thread: take the handler out, sending what it still holds. */
        public void remove() {
            if (!channel.isOpen()) return;
            channel.eventLoop().execute(() -> { if (channel.pipeline().get(HANDLER) == this) channel.pipeline().remove(this); });
        }

        private void flushBundle() {
            ChannelHandlerContext c = ctx;
            if (c == null || packets.isEmpty()) return;
            if (packets.size() == 1) { release(c); c.flush(); return; }
            Object bundle;
            try { bundle = access.bundle(new ArrayList<>(packets)); } catch (Throwable t) { release(c); c.flush(); return; }
            ChannelPromise[] waiting = promises.toArray(new ChannelPromise[0]);
            packets.clear(); promises.clear();
            c.writeAndFlush(bundle).addListener(f -> {
                for (ChannelPromise p : waiting) { if (p.isVoid()) continue; if (f.isSuccess()) p.trySuccess(); else p.tryFailure(f.cause()); }
            });
        }

        /** Send what is held, one by one. */
        private void release(ChannelHandlerContext c) {
            for (int i = 0; i < packets.size(); i++) c.write(packets.get(i), promises.get(i));
            packets.clear(); promises.clear();
        }
    }

    /** The server's packet classes (Mojang names; Paper 26.x runs unobfuscated). */
    static final class ReflectivePackets implements PacketAccess {
        private final Class<?> data, teleport, sync, move;
        private final MethodHandle dataId, teleportId, syncId, moveId, newBundle;

        ReflectivePackets() throws ReflectiveOperationException {
            String pkg = "net.minecraft.network.protocol.game.";
            MethodHandles.Lookup l = MethodHandles.lookup();
            MethodType get = MethodType.methodType(int.class, Object.class);
            data = Class.forName(pkg + "ClientboundSetEntityDataPacket");
            teleport = Class.forName(pkg + "ClientboundTeleportEntityPacket");
            sync = Class.forName(pkg + "ClientboundEntityPositionSyncPacket");
            move = Class.forName(pkg + "ClientboundMoveEntityPacket");
            dataId = l.findVirtual(data, "id", MethodType.methodType(int.class)).asType(get);
            teleportId = l.findVirtual(teleport, "id", MethodType.methodType(int.class)).asType(get);
            syncId = l.findVirtual(sync, "id", MethodType.methodType(int.class)).asType(get);
            Field f = move.getDeclaredField("entityId");
            f.setAccessible(true);
            moveId = l.unreflectGetter(f).asType(get);
            Class<?> bundle = Class.forName(pkg + "ClientboundBundlePacket");
            newBundle = l.findConstructor(bundle, MethodType.methodType(void.class, Iterable.class)).asType(MethodType.methodType(Object.class, List.class));
        }

        @Override public int entityId(Object p) {
            Class<?> c = p.getClass();
            try {
                if (c == data) return (int) dataId.invokeExact(p);
                if (c == sync) return (int) syncId.invokeExact(p);
                if (c == teleport) return (int) teleportId.invokeExact(p);
                if (move.isAssignableFrom(c)) return (int) moveId.invokeExact(p);
            } catch (Throwable t) { return -1; }
            return -1;
        }

        @Override public Object bundle(List<Object> packets) {
            try { return (Object) newBundle.invokeExact(packets); } catch (Throwable t) { throw new IllegalStateException(t); }
        }
    }

    /** CraftPlayer -> ServerPlayer.connection -> Connection.channel, by reflection (public members). */
    static final class ChannelAccess {
        private java.lang.reflect.Method handle;
        private Field listener, connection, channel;

        Channel of(Player p) throws ReflectiveOperationException {
            if (handle == null) {
                handle = p.getClass().getMethod("getHandle");
                Object sp = handle.invoke(p);
                listener = sp.getClass().getField("connection");
                connection = listener.getType().getField("connection");
                channel = connection.getType().getField("channel");
            }
            Object l = listener.get(handle.invoke(p));
            return l == null ? null : (Channel) channel.get(connection.get(l));
        }
    }
}
