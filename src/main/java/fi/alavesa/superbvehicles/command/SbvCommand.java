package fi.alavesa.superbvehicles.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fi.alavesa.superbvehicles.SuperbVehiclesPlugin;
import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BbParser;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * /sbv give|spawn|remove|removeall|list|info|repair|refuel|ammo|skin|lock|unlock|tp|reload|pack|import|debug|seat
 * (Brigadier, tab completion). Permissions: superbvehicles.admin for everything here except seat; players place
 * vehicles with items under superbvehicles.spawn.<id>.
 */
public final class SbvCommand {
    private final SuperbVehiclesPlugin plugin;
    public SbvCommand(SuperbVehiclesPlugin plugin) { this.plugin = plugin; }

    private SuggestionProvider<CommandSourceStack> typeIds() { return (ctx, b) -> { for (String id : plugin.types().ids()) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id); return b.buildFuture(); }; }
    private SuggestionProvider<CommandSourceStack> playerNames() { return (ctx, b) -> { for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(p.getName()); return b.buildFuture(); }; }
    private SuggestionProvider<CommandSourceStack> bbFiles() { return (ctx, b) -> { File[] fs = new File(plugin.getDataFolder(), "models").listFiles((d, n) -> n.endsWith(".bbmodel")); if (fs != null) for (File f : fs) if (f.getName().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(f.getName()); return b.buildFuture(); }; }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> { e.registrar().register(build().build(), "SuperbVehicles", List.of("superbvehicles", "vehicle")); });
    }

    private LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("sbv");
        root.requires(s -> true).executes(c -> { help(c.getSource().getSender()); return 1; });
        root.then(admin(Commands.literal("give"))
            .then(Commands.argument("type", StringArgumentType.word()).suggests(typeIds()).executes(c -> give(c, null, 1))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(playerNames()).executes(c -> give(c, StringArgumentType.getString(c, "player"), 1))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes(c -> give(c, StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "amount")))))));
        root.then(admin(Commands.literal("spawn"))
            .then(Commands.argument("type", StringArgumentType.word()).suggests(typeIds()).executes(this::spawn)
                .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("y", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg()).executes(this::spawn))))));
        root.then(admin(Commands.literal("remove")).executes(c -> remove(c, 0)).then(Commands.argument("radius", DoubleArgumentType.doubleArg(0, 500)).executes(c -> remove(c, DoubleArgumentType.getDouble(c, "radius")))));
        root.then(admin(Commands.literal("removeall")).executes(c -> removeAll(c, null)).then(Commands.argument("type", StringArgumentType.word()).suggests(typeIds()).executes(c -> removeAll(c, StringArgumentType.getString(c, "type")))));
        root.then(admin(Commands.literal("list")).executes(this::list));
        root.then(admin(Commands.literal("info")).executes(this::info));
        root.then(admin(Commands.literal("repair")).executes(c -> repair(c, -1)).then(Commands.argument("amount", DoubleArgumentType.doubleArg(0)).executes(c -> repair(c, DoubleArgumentType.getDouble(c, "amount")))));
        root.then(admin(Commands.literal("refuel")).executes(c -> refuel(c, -1)).then(Commands.argument("amount", DoubleArgumentType.doubleArg(0)).executes(c -> refuel(c, DoubleArgumentType.getDouble(c, "amount")))));
        root.then(admin(Commands.literal("ammo")).executes(this::ammo));
        root.then(admin(Commands.literal("skin")).then(Commands.argument("skin", StringArgumentType.word()).executes(this::skin)));
        root.then(admin(Commands.literal("lock")).executes(c -> lock(c, true)));
        root.then(admin(Commands.literal("unlock")).executes(c -> lock(c, false)));
        root.then(admin(Commands.literal("tp")).then(Commands.argument("id", StringArgumentType.word()).executes(this::tp)));
        root.then(admin(Commands.literal("reload")).executes(this::reload));
        root.then(admin(Commands.literal("pack")).executes(c -> { packStatus(c.getSource().getSender()); return 1; })
            .then(Commands.literal("rebuild").executes(this::packRebuild))
            .then(Commands.literal("send").executes(this::packSend))
            .then(Commands.literal("url").executes(this::packUrl)));
        root.then(admin(Commands.literal("import")).then(Commands.argument("file", StringArgumentType.greedyString()).suggests(bbFiles()).executes(this::importModel)));
        root.then(admin(Commands.literal("debug"))
            .then(Commands.literal("hitboxes").executes(c -> debug(c, "hitboxes")))
            .then(Commands.literal("physics").executes(c -> debug(c, "physics")))
            .then(Commands.literal("seats").executes(c -> debug(c, "seats")))
            .then(Commands.literal("perf").executes(c -> debug(c, "perf"))));
        root.then(admin(Commands.literal("item"))
            .then(Commands.literal("fuel").executes(c -> utility(c, "fuel_can")))
            .then(Commands.literal("ammo").executes(c -> utility(c, "ammo_crate")))
            .then(Commands.literal("repair").executes(c -> utility(c, "repair_kit"))));
        root.then(Commands.literal("seat").then(Commands.argument("number", IntegerArgumentType.integer(1, 32)).executes(this::seat)));
        LiteralArgumentBuilder<CommandSourceStack> hud = Commands.literal("hud");
        hud.executes(this::hudStatus);
        for (String m : new String[]{"shader", "text", "off"}) hud.then(Commands.literal(m).executes(c -> hudMode(c, m)));
        hud.then(Commands.literal("units").then(Commands.literal("kmh").executes(c -> hudUnits(c, false))).then(Commands.literal("mph").executes(c -> hudUnits(c, true))));
        root.then(hud);
        root.then(admin(Commands.literal("seatoffset"))
            .then(Commands.argument("type", StringArgumentType.word()).suggests(typeIds()).executes(c -> seatOffsetShow(c, null))
                .then(Commands.argument("seat", StringArgumentType.word()).suggests(seatNames()).executes(c -> seatOffsetShow(c, StringArgumentType.getString(c, "seat")))
                    .then(Commands.literal("reset").executes(c -> seatOffsetSet(c, 0, 0, 0, false)))
                    .then(Commands.literal("add").then(Commands.argument("right", DoubleArgumentType.doubleArg(-8, 8)).then(Commands.argument("up", DoubleArgumentType.doubleArg(-8, 8)).then(Commands.argument("forward", DoubleArgumentType.doubleArg(-8, 8))
                        .executes(c -> seatOffsetSet(c, DoubleArgumentType.getDouble(c, "right"), DoubleArgumentType.getDouble(c, "up"), DoubleArgumentType.getDouble(c, "forward"), true))))))
                    .then(Commands.argument("right", DoubleArgumentType.doubleArg(-8, 8)).then(Commands.argument("up", DoubleArgumentType.doubleArg(-8, 8)).then(Commands.argument("forward", DoubleArgumentType.doubleArg(-8, 8))
                        .executes(c -> seatOffsetSet(c, DoubleArgumentType.getDouble(c, "right"), DoubleArgumentType.getDouble(c, "up"), DoubleArgumentType.getDouble(c, "forward"), false))))))));
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> admin(LiteralArgumentBuilder<CommandSourceStack> b) { return b.requires(s -> s.getSender().hasPermission("superbvehicles.admin")); }

    private void help(CommandSender s) {
        s.sendMessage(Component.text("SuperbVehicles " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
        s.sendMessage(Component.text("/sbv give <type> [player] [amount] | spawn <type> [x y z] | remove [radius] | removeall [type] | list | info", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/sbv repair [n] | refuel [n] | ammo | skin <name> | lock | unlock | tp <id> | seat <n> | hud [shader|text|off|units kmh|mph] | seatoffset <type> <seat> <r> <u> <f>", NamedTextColor.GRAY));
        s.sendMessage(Component.text("/sbv reload | pack [rebuild|send|url] | import <file.bbmodel> | item fuel|ammo|repair | debug hitboxes|physics|seats|perf", NamedTextColor.GRAY));
    }

    private Player player(CommandContext<CommandSourceStack> c) { return c.getSource().getExecutor() instanceof Player p ? p : c.getSource().getSender() instanceof Player p ? p : null; }
    private CommandSender sender(CommandContext<CommandSourceStack> c) { return c.getSource().getSender(); }

    private Vehicle target(CommandContext<CommandSourceStack> c) {
        Player p = player(c);
        Vehicle v = p == null ? null : plugin.vehicles().target(p);
        if (v == null) plugin.messages().send(sender(c), "no-vehicle");
        return v;
    }

    private int give(CommandContext<CommandSourceStack> c, String playerName, int amount) {
        VehicleType t = plugin.types().get(StringArgumentType.getString(c, "type"));
        if (t == null) { plugin.messages().send(sender(c), "unknown-type", Messages.str("type", StringArgumentType.getString(c, "type"))); return 0; }
        Player to = playerName == null ? player(c) : Bukkit.getPlayerExact(playerName);
        if (to == null) { sender(c).sendMessage(Component.text("Player not found.", NamedTextColor.RED)); return 0; }
        to.getInventory().addItem(plugin.items().spawnItem(t, amount));
        plugin.messages().send(sender(c), "given", Messages.num("amount", amount), Messages.str("name", t.displayName), Messages.str("player", to.getName()));
        return 1;
    }

    private int utility(CommandContext<CommandSourceStack> c, String kind) {
        Player p = player(c);
        if (p == null) return 0;
        p.getInventory().addItem(plugin.items().utility(kind, 1));
        return 1;
    }

    private int spawn(CommandContext<CommandSourceStack> c) {
        VehicleType t = plugin.types().get(StringArgumentType.getString(c, "type"));
        if (t == null) { plugin.messages().send(sender(c), "unknown-type", Messages.str("type", StringArgumentType.getString(c, "type"))); return 0; }
        Location at;
        Player p = player(c);
        try { at = new Location(c.getSource().getLocation().getWorld(), DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z")); if (p != null) at.setYaw(p.getLocation().getYaw()); }
        catch (IllegalArgumentException e) {
            if (p == null) { sender(c).sendMessage(Component.text("Give coordinates from the console.", NamedTextColor.RED)); return 0; }
            var dir = p.getLocation().getDirection().setY(0).normalize().multiply(Math.max(3, 1.5 + (t.hitboxSize != null ? t.hitboxSize[2] : 4)));
            at = p.getLocation().add(dir); at.setYaw(p.getLocation().getYaw());
            double g = plugin.vehicles().collider(at.getWorld()).groundAt(at.getX(), at.getY() + 1, at.getZ(), 1, 6);
            if (!Double.isNaN(g)) at.setY(g);
        }
        Vehicle v = plugin.vehicles().spawn(t, at, p == null ? null : p.getUniqueId());
        plugin.messages().send(sender(c), "spawned", Messages.str("name", t.displayName));
        return v == null ? 0 : 1;
    }

    private int remove(CommandContext<CommandSourceStack> c, double radius) {
        int n = 0;
        if (radius > 0 && player(c) != null) { for (Vehicle v : plugin.vehicles().nearby(player(c).getLocation(), radius)) { plugin.vehicles().remove(v, true); n++; } }
        else { Vehicle v = target(c); if (v == null) return 0; plugin.vehicles().remove(v, true); n = 1; }
        plugin.messages().send(sender(c), "removed", Messages.num("count", n));
        return 1;
    }

    private int removeAll(CommandContext<CommandSourceStack> c, String typeId) {
        VehicleType t = typeId == null ? null : plugin.types().get(typeId);
        if (typeId != null && t == null) { plugin.messages().send(sender(c), "unknown-type", Messages.str("type", typeId)); return 0; }
        plugin.messages().send(sender(c), "removed", Messages.num("count", plugin.vehicles().removeAll(t)));
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        var all = plugin.vehicles().all();
        plugin.messages().send(sender(c), "list-header", Messages.num("count", all.size()));
        for (Vehicle v : all) plugin.messages().send(sender(c), "list-line", Messages.str("id", v.id.toString().substring(0, 8)), Messages.str("name", v.type.id), Messages.num("x", Math.floor(v.pose.x)), Messages.num("y", Math.floor(v.pose.y)), Messages.num("z", Math.floor(v.pose.z)), Messages.str("world", v.world.getName()), Messages.num("health", Math.max(0, v.health)));
        return 1;
    }

    private int info(CommandContext<CommandSourceStack> c) {
        Vehicle v = target(c);
        if (v == null) return 0;
        String owner = v.owner == null ? "-" : (Bukkit.getOfflinePlayer(v.owner).getName() == null ? v.owner.toString() : Bukkit.getOfflinePlayer(v.owner).getName());
        plugin.messages().send(sender(c), "info", Messages.str("name", v.displayName()), Messages.str("type", v.type.id), Messages.str("class", v.type.vehicleClass.name().toLowerCase(Locale.ROOT)), Messages.num("health", Math.max(0, v.health)), Messages.num("max", v.maxHealth()), Messages.num("fuel", v.fuel), Messages.num("capacity", v.type.fuelCapacity), Messages.str("owner", owner), Messages.str("locked", v.locked ? "locked" : "unlocked"), Messages.num("seats", v.seats.length), Messages.str("id", v.id.toString()));
        for (MountedWeapon w : v.weapons.values()) sender(c).sendMessage(Component.text("  " + w.type.displayName + " (" + w.type.kind.name().toLowerCase(Locale.ROOT) + ") " + w.ammoText(), NamedTextColor.GRAY));
        var rig = v.rig();
        if (rig != null) sender(c).sendMessage(Component.text("  model " + v.type.model + ": " + rig.parts.size() + " parts, animatable " + rig.animatable.size() + ", locators " + rig.model.locators.keySet(), NamedTextColor.DARK_GRAY));
        return 1;
    }

    private int repair(CommandContext<CommandSourceStack> c, double amount) {
        Vehicle v = target(c); if (v == null) return 0;
        double before = v.health;
        v.health = amount < 0 ? v.maxHealth() : Math.min(v.maxHealth(), v.health + amount);
        if (v.wreck) { v.wreck = false; v.wreckTicks = 0; v.lastState = ""; }
        plugin.messages().send(sender(c), "repaired", Messages.str("name", v.displayName()), Messages.num("amount", v.health - before));
        return 1;
    }

    private int refuel(CommandContext<CommandSourceStack> c, double amount) {
        Vehicle v = target(c); if (v == null) return 0;
        v.fuel = amount < 0 ? v.type.fuelCapacity : Math.min(v.type.fuelCapacity, v.fuel + amount);
        plugin.messages().send(sender(c), "refueled", Messages.str("name", v.displayName()), Messages.num("fuel", v.fuel), Messages.num("capacity", v.type.fuelCapacity));
        return 1;
    }

    private int ammo(CommandContext<CommandSourceStack> c) {
        Vehicle v = target(c); if (v == null) return 0;
        for (MountedWeapon w : v.weapons.values()) w.restock();
        plugin.messages().send(sender(c), "ammo-restocked", Messages.str("name", v.displayName()));
        return 1;
    }

    private int skin(CommandContext<CommandSourceStack> c) {
        Vehicle v = target(c); if (v == null) return 0;
        String skin = StringArgumentType.getString(c, "skin").toLowerCase(Locale.ROOT);
        if (!v.type.skinNames().contains(skin)) { plugin.messages().send(sender(c), "skin-unknown", Messages.str("skin", skin), Messages.str("skins", String.join(", ", v.type.skinNames()))); return 0; }
        v.skin = skin; v.renderer.setSkin(skin);
        plugin.messages().send(sender(c), "skin-set", Messages.str("name", v.displayName()), Messages.str("skin", skin));
        return 1;
    }

    private int lock(CommandContext<CommandSourceStack> c, boolean lock) {
        Vehicle v = target(c); if (v == null) return 0;
        Player p = player(c);
        if (v.owner == null && p != null) v.owner = p.getUniqueId();
        v.locked = lock;
        plugin.messages().send(sender(c), "lock-set", Messages.str("name", v.displayName()), Messages.str("state", lock ? "locked" : "unlocked"));
        return 1;
    }

    private int tp(CommandContext<CommandSourceStack> c) {
        Player p = player(c); if (p == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        for (Vehicle v : plugin.vehicles().all()) if (v.id.toString().startsWith(id)) { Location l = v.location().add(0, 1.5, 0); l.setYaw(p.getLocation().getYaw()); p.teleport(l); plugin.messages().send(p, "teleported", Messages.str("name", v.displayName())); return 1; }
        plugin.messages().send(p, "no-vehicle");
        return 0;
    }

    private int hudStatus(CommandContext<CommandSourceStack> c) {
        Player p = player(c); if (p == null) return 0;
        var h = plugin.hud();
        p.sendMessage(Component.text("HUD: " + h.modeOf(p).name().toLowerCase(Locale.ROOT) + ", units " + (h.mphOf(p) ? "mph" : "km/h")
            + (h.shaderSupported() ? (plugin.host().hudAvailable() ? "; shader HUD pack available" : "; shader HUD pack not built (see console)") : "; shader HUD not supported on this server (text HUD only)")
            + ". /sbv hud shader|text|off, /sbv hud units kmh|mph", NamedTextColor.GRAY));
        return 1;
    }

    private int hudMode(CommandContext<CommandSourceStack> c, String mode) {
        Player p = player(c); if (p == null) return 0;
        plugin.hud().setMode(p, fi.alavesa.superbvehicles.hud.HudManager.Mode.valueOf(mode.toUpperCase(Locale.ROOT)));
        plugin.messages().send(p, "hud-mode", Messages.str("mode", mode));
        if (mode.equals("shader") && !plugin.hud().shaderSupported()) plugin.messages().send(p, "hud-shader-unsupported");
        return 1;
    }

    private int hudUnits(CommandContext<CommandSourceStack> c, boolean mph) {
        Player p = player(c); if (p == null) return 0;
        plugin.hud().setMph(p, mph);
        plugin.messages().send(p, "hud-units", Messages.str("units", mph ? "mph" : "km/h"));
        return 1;
    }

    private SuggestionProvider<CommandSourceStack> seatNames() {
        return (ctx, b) -> {
            VehicleType t;
            try { t = plugin.types().get(StringArgumentType.getString(ctx, "type")); } catch (IllegalArgumentException e) { t = null; }
            if (t != null) for (VehicleType.Seat st : t.seats) { if (st.name.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(st.name); }
            return b.buildFuture();
        };
    }

    private VehicleType.Seat findSeat(VehicleType t, String key) {
        for (VehicleType.Seat st : t.seats) if (st.name.equalsIgnoreCase(key)) return st;
        try { int n = Integer.parseInt(key); if (n >= 1 && n <= t.seats.size()) return t.seats.get(n - 1); } catch (NumberFormatException ignored) { }
        return null;
    }

    /** /sbv seatoffset <type> [seat]: the seats of a type, their locators and offsets. */
    private int seatOffsetShow(CommandContext<CommandSourceStack> c, String seatKey) {
        VehicleType t = plugin.types().get(StringArgumentType.getString(c, "type"));
        if (t == null) { plugin.messages().send(sender(c), "unknown-type", Messages.str("type", StringArgumentType.getString(c, "type"))); return 0; }
        var rig = plugin.types().rigOf(t);
        for (int i = 0; i < t.seats.size(); i++) {
            VehicleType.Seat st = t.seats.get(i);
            if (seatKey != null && st != findSeat(t, seatKey)) continue;
            boolean inModel = rig != null && (rig.model.locator(st.locator) != null || rig.model.bone(st.locator) != null);
            sender(c).sendMessage(Component.text(String.format(Locale.ROOT, "%d. %s (%s) locator %s%s - offset right %.2f up %.2f forward %.2f blocks",
                i + 1, st.name, st.role.name().toLowerCase(Locale.ROOT), st.locator, inModel ? "" : " (NOT in the model: placed at the hull centre)", st.offset[0], st.offset[1], st.offset[2]), inModel ? NamedTextColor.GRAY : NamedTextColor.YELLOW));
        }
        sender(c).sendMessage(Component.text("/sbv seatoffset <type> <seat> <right> <up> <forward> | add <r> <u> <f> | reset  (blocks; applies to every " + t.id + " at once)", NamedTextColor.DARK_GRAY));
        return 1;
    }

    /** Set (or add to) a seat offset for every vehicle of the type, live, and save it to the type's YAML. */
    private int seatOffsetSet(CommandContext<CommandSourceStack> c, double r, double u, double f, boolean add) {
        VehicleType t = plugin.types().get(StringArgumentType.getString(c, "type"));
        if (t == null) { plugin.messages().send(sender(c), "unknown-type", Messages.str("type", StringArgumentType.getString(c, "type"))); return 0; }
        String key = StringArgumentType.getString(c, "seat");
        VehicleType.Seat st = findSeat(t, key);
        if (st == null) { sender(c).sendMessage(Component.text("No seat '" + key + "' on " + t.id + ".", NamedTextColor.RED)); return 0; }
        double[] o = st.offset;
        if (add) { o[0] += r; o[1] += u; o[2] += f; } else { o[0] = r; o[1] = u; o[2] = f; }
        for (int i = 0; i < 3; i++) o[i] = Math.round(o[i] * 1000) / 1000.0;
        for (Vehicle v : plugin.vehicles().all()) if (v.type == t && v.spawned()) v.tickSeats();
        String saved = saveSeatOffset(t, st);
        plugin.messages().send(sender(c), "seat-offset-set", Messages.str("type", t.id), Messages.str("seat", st.name),
            Messages.str("offset", String.format(Locale.ROOT, "%.3f %.3f %.3f", o[0], o[1], o[2])), Messages.str("saved", saved));
        return 1;
    }

    private String saveSeatOffset(VehicleType t, VehicleType.Seat st) {
        if (t.source == null || !t.source.isFile()) return "live only (type registered by another plugin)";
        try {
            org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(t.source);
            String base = y.contains("seats") ? "" : t.id + ".";
            java.util.List<java.util.Map<?, ?>> seats = y.getMapList(base + "seats");
            java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
            boolean found = false;
            for (int i = 0; i < seats.size(); i++) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                for (var e : seats.get(i).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
                String name = String.valueOf(m.getOrDefault("name", i == 0 ? "driver" : "seat" + (i + 1)));
                if (i == st.index || name.equalsIgnoreCase(st.name)) {
                    if (st.offset[0] == 0 && st.offset[1] == 0 && st.offset[2] == 0) m.remove("offset");
                    else m.put("offset", java.util.List.of(st.offset[0], st.offset[1], st.offset[2]));
                    found = true;
                }
                out.add(m);
            }
            if (!found) return "live only (seat not found in " + t.source.getName() + ")";
            y.set(base + "seats", out);
            y.save(t.source);
            return "saved to vehicles/" + t.source.getName();
        } catch (Exception e) { return "live only (could not save: " + e.getMessage() + ")"; }
    }

    private int seat(CommandContext<CommandSourceStack> c) {
        Player p = player(c); if (p == null) return 0;
        Vehicle v = plugin.vehicles().byPlayer(p);
        if (v == null) { plugin.messages().send(p, "no-vehicle"); return 0; }
        int n = IntegerArgumentType.getInteger(c, "number") - 1;
        if (n >= v.seats.length) { plugin.messages().send(p, "no-free-seat"); return 0; }
        return plugin.seats().enter(p, v, v.seats[n]) ? 1 : 0;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        plugin.reloadConfig();
        plugin.messages().load();
        plugin.types().load(new File(plugin.getDataFolder(), "vehicles"));
        plugin.selfCheck();
        for (String e : plugin.types().errors) sender(c).sendMessage(Component.text(e, NamedTextColor.RED));
        plugin.messages().send(sender(c), "reloaded", Messages.num("types", plugin.types().all().size()));
        CommandSender s = sender(c);
        plugin.rebuildPack(r -> { if (r != null) packResult(s, r); });
        return 1;
    }

    private void packStatus(CommandSender s) {
        var r = plugin.lastPack();
        s.sendMessage(Component.text(r == null ? "No pack built yet." : "Pack: " + r.files() + " files, " + r.bytes() / 1024 + " KB, " + r.rigs().size() + " model(s), " + r.warnings().size() + " warning(s), sha1 " + r.sha1(), NamedTextColor.GRAY));
        s.sendMessage(Component.text(plugin.host().status(), NamedTextColor.GRAY));
    }

    private void packResult(CommandSender s, fi.alavesa.superbvehicles.pack.PackBuilder.Result r) {
        plugin.messages().send(s, "pack-rebuilt", Messages.num("files", r.files()), Messages.num("kb", r.bytes() / 1024), Messages.num("models", r.rigs().size()), Messages.num("warnings", r.warnings().size()), Messages.str("status", plugin.host().status()));
        for (String w : r.warnings()) s.sendMessage(Component.text("! " + w, NamedTextColor.YELLOW));
    }

    private int packRebuild(CommandContext<CommandSourceStack> c) {
        CommandSender s = sender(c);
        if (plugin.packBuilding()) { plugin.messages().send(s, "pack-busy"); return 0; }
        plugin.messages().send(s, "pack-rebuilding");
        plugin.rebuildPack(r -> { if (r != null) packResult(s, r); });
        return 1;
    }

    private int packSend(CommandContext<CommandSourceStack> c) {
        plugin.host().packChanged();
        plugin.messages().send(sender(c), "pack-sent", Messages.num("count", Bukkit.getOnlinePlayers().size()));
        return 1;
    }

    private int packUrl(CommandContext<CommandSourceStack> c) {
        Player p = player(c);
        String url = p == null ? "(needs a player for the address)" : String.valueOf(plugin.host().urlFor(p));
        plugin.messages().send(sender(c), "pack-url", Messages.str("url", url), Messages.str("sha1", plugin.host().sha1()));
        return 1;
    }

    private int importModel(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "file").trim();
        File models = new File(plugin.getDataFolder(), "models");
        File src = new File(name);
        if (!src.isAbsolute()) src = new File(models, name);
        if (!src.isFile()) src = new File(name.endsWith(".bbmodel") ? name : name + ".bbmodel");
        if (!src.isFile()) { plugin.messages().send(sender(c), "import-failed", Messages.str("reason", "file not found: " + name)); return 0; }
        try {
            BbModel m = BbParser.parse(src);
            File dest = new File(models, src.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
            if (!dest.getCanonicalPath().equals(src.getCanonicalPath())) Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.messages().send(sender(c), "imported", Messages.str("file", dest.getName()), Messages.num("cubes", m.cubes.size()), Messages.num("bones", m.bones.size()), Messages.num("anims", m.animations.size()), Messages.num("locators", m.locators.size()), Messages.num("warnings", m.warnings.size()));
            for (String w : m.warnings) sender(c).sendMessage(Component.text("! " + w, NamedTextColor.YELLOW));
            return 1;
        } catch (Exception e) { plugin.messages().send(sender(c), "import-failed", Messages.str("reason", e.getMessage())); return 0; }
    }

    private int debug(CommandContext<CommandSourceStack> c, String what) {
        CommandSender s = sender(c);
        switch (what) {
            case "perf" -> {
                var vm = plugin.vehicles();
                s.sendMessage(Component.text(String.format(Locale.ROOT, "%d vehicle(s), %d projectile(s); last tick %.3f ms, peak %.3f ms (%.3f ms per vehicle)", vm.all().size(), plugin.projectiles().all().size(), vm.lastTickNanos / 1e6, vm.maxTickNanos / 1e6, vm.all().isEmpty() ? 0 : vm.lastTickNanos / 1e6 / vm.all().size()), NamedTextColor.GRAY));
            }
            case "physics" -> {
                Player pl = player(c);
                Vehicle v = target(c); if (v == null) return 0;
                if (pl != null && plugin.vehicles().byPlayer(pl) == v) {
                    boolean on = plugin.vehicles().toggleDebug(pl);
                    plugin.messages().send(s, "debug-toggled", Messages.str("what", "physics (every tick while you ride)"), Messages.str("state", on ? "on" : "off"));
                    if (!on) return 1;
                }
                s.sendMessage(Component.text("[" + v.type.id + "] " + v.physics().debugLine(), NamedTextColor.GRAY));
            }
            case "hitboxes" -> {
                Vehicle v = target(c); if (v == null) return 0;
                v.debugHitbox = !v.debugHitbox;
                var h = v.hull();
                s.sendMessage(Component.text(String.format(Locale.ROOT, "hull centre %.2f %.2f %.2f half %.2f %.2f %.2f (px centre %.0f %.0f %.0f half %.0f %.0f %.0f)", h.center.x, h.center.y, h.center.z, h.half.x, h.half.y, h.half.z, v.hitboxCenterPx[0], v.hitboxCenterPx[1], v.hitboxCenterPx[2], v.hitboxHalfPx[0], v.hitboxHalfPx[1], v.hitboxHalfPx[2]), NamedTextColor.GRAY));
                for (var corner : h.corners()) v.world.spawnParticle(org.bukkit.Particle.END_ROD, corner.x, corner.y, corner.z, 1, 0, 0, 0, 0);
                plugin.messages().send(s, "debug-toggled", Messages.str("what", "hitboxes"), Messages.str("state", "corners shown once (particles)"));
            }
            case "seats" -> {
                Vehicle v = target(c); if (v == null) return 0;
                for (Vehicle.SeatSlot slot : v.seats) { var w = v.seatWorld(slot); s.sendMessage(Component.text(String.format(Locale.ROOT, "seat %d %s (%s, locator %s) at %.2f %.2f %.2f rider %s weapons %s", slot.index, slot.def.name, slot.def.role, slot.def.locator, w.x, w.y, w.z, slot.rider() == null ? "-" : slot.rider().getName(), slot.def.weapons), NamedTextColor.GRAY)); v.world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, w.x, w.y, w.z, 3, 0.05, 0.05, 0.05, 0); }
            }
            default -> { }
        }
        return 1;
    }
}
