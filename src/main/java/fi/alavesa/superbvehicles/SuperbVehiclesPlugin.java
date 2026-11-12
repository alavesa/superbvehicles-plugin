package fi.alavesa.superbvehicles;

import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Messages;
import fi.alavesa.superbvehicles.core.Sched;
import fi.alavesa.superbvehicles.core.TypeRegistry;
import fi.alavesa.superbvehicles.pack.PackBuilder;
import fi.alavesa.superbvehicles.pack.PackHost;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** SuperbVehicles: data-driven Blockbench vehicles. Boot order: config -> types -> pack build (async) -> host -> managers. */
public final class SuperbVehiclesPlugin extends JavaPlugin {
    private static SuperbVehiclesPlugin instance;
    private Keys keys;
    private Sched sched;
    private Messages messages;
    private TypeRegistry types;
    private PackHost host;
    private volatile boolean packBuilding;
    private PackBuilder.Result lastPack;
    private fi.alavesa.superbvehicles.core.VehicleManager vehicles;
    private fi.alavesa.superbvehicles.seat.SeatManager seats;
    private fi.alavesa.superbvehicles.weapon.ProjectileManager projectiles;
    private fi.alavesa.superbvehicles.hud.HudManager hud;
    private fi.alavesa.superbvehicles.damage.DamageModel damage;
    private fi.alavesa.superbvehicles.core.Persistence persistence;
    private fi.alavesa.superbvehicles.core.Items items;

    public static SuperbVehiclesPlugin get() { return instance; }

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        keys = new Keys(this);
        sched = new Sched(this);
        messages = new Messages(new Messages.SuperbVehiclesPluginRef() {
            public File dataFolder() { return getDataFolder(); }
            public InputStream resource(String name) { return getResource(name); }
            public Logger logger() { return getLogger(); }
        });
        messages.load();
        extractDefaults();
        types = new TypeRegistry();
        types.load(new File(getDataFolder(), "vehicles"));
        for (String e : types.errors) getLogger().warning("vehicles/: " + e);
        getLogger().info(types.all().size() + " vehicle type(s): " + types.ids());
        items = new fi.alavesa.superbvehicles.core.Items(this);
        damage = new fi.alavesa.superbvehicles.damage.DamageModel(this);
        projectiles = new fi.alavesa.superbvehicles.weapon.ProjectileManager(this);
        vehicles = new fi.alavesa.superbvehicles.core.VehicleManager(this);
        seats = new fi.alavesa.superbvehicles.seat.SeatManager(this);
        hud = new fi.alavesa.superbvehicles.hud.HudManager(this);
        persistence = new fi.alavesa.superbvehicles.core.Persistence(this);
        host = new PackHost(this);
        host.start();
        getServer().getPluginManager().registerEvents(seats, this);
        getServer().getPluginManager().registerEvents(new fi.alavesa.superbvehicles.seat.InputListener(this), this);
        getServer().getPluginManager().registerEvents(new fi.alavesa.superbvehicles.damage.DamageListener(this), this);
        getServer().getPluginManager().registerEvents(vehicles, this);
        new fi.alavesa.superbvehicles.command.SbvCommand(this).register();
        fi.alavesa.superbvehicles.api.SuperbVehiclesAPI.init(this);
        vehicles.start();
        rebuildPack(null);
        persistence.load();
        getLogger().info("SuperbVehicles " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override public void onDisable() {
        try { if (persistence != null) persistence.save(); } catch (Exception e) { getLogger().warning("save failed: " + e); }
        if (vehicles != null) vehicles.shutdown();
        if (projectiles != null) projectiles.clear();
        if (host != null) host.stop();
        instance = null;
    }

    /** Copy the shipped vehicles/*.yml and models/*.bbmodel once (never overwriting edited files). */
    private void extractDefaults() {
        File vehicles = new File(getDataFolder(), "vehicles"), models = new File(getDataFolder(), "models");
        vehicles.mkdirs(); models.mkdirs(); new File(models, "skins").mkdirs(); new File(models, "sounds").mkdirs();
        File marker = new File(getDataFolder(), ".defaults-extracted");
        boolean first = !marker.exists();
        try (InputStream in = getResource("defaults-index.txt")) {
            if (in == null) return;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                File out = new File(getDataFolder(), line);
                if (out.exists() || (!first && !getConfig().getBoolean("extract-new-defaults", true))) continue;
                try (InputStream r = getResource(line)) { if (r != null) { out.getParentFile().mkdirs(); Files.copy(r, out.toPath()); } }
            }
            Files.writeString(marker.toPath(), String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) { getLogger().warning("could not extract defaults: " + e); }
    }

    /** Build the pack off the main thread, then swap rigs in, refresh live vehicles and re-send. */
    public void rebuildPack(Consumer<PackBuilder.Result> then) {
        if (packBuilding) { if (then != null) then.accept(null); return; }
        packBuilding = true;
        PackBuilder builder = new PackBuilder(getDataFolder(), List.copyOf(types.all()), getConfig(), getLogger());
        final PackBuilder.Result[] box = new PackBuilder.Result[1];
        sched.asyncThen(() -> { try { box[0] = builder.build(); } catch (Exception e) { throw new RuntimeException(e); } }, err -> {
            packBuilding = false;
            if (err != null) { getLogger().severe("Pack build failed: " + err.getCause()); if (then != null) then.accept(null); return; }
            PackBuilder.Result r = box[0];
            lastPack = r;
            types.setRigs(r.rigs());
            getLogger().info("Pack built: " + r.files() + " files, " + (r.bytes() / 1024) + " KB in " + r.millis() + " ms, " + r.rigs().size() + " model(s)" + (r.warnings().isEmpty() ? "" : ", " + r.warnings().size() + " warning(s) - see pack-report.txt"));
            for (String w : r.warnings()) getLogger().warning("pack: " + w);
            if (vehicles != null) vehicles.rigsChanged();
            host.packChanged();
            if (then != null) then.accept(r);
        });
    }

    public Keys keys() { return keys; }
    public Sched sched() { return sched; }
    public Messages messages() { return messages; }
    public TypeRegistry types() { return types; }
    public PackHost host() { return host; }
    public PackBuilder.Result lastPack() { return lastPack; }
    public boolean packBuilding() { return packBuilding; }
    public fi.alavesa.superbvehicles.core.VehicleManager vehicles() { return vehicles; }
    public fi.alavesa.superbvehicles.seat.SeatManager seats() { return seats; }
    public fi.alavesa.superbvehicles.weapon.ProjectileManager projectiles() { return projectiles; }
    public fi.alavesa.superbvehicles.hud.HudManager hud() { return hud; }
    public fi.alavesa.superbvehicles.damage.DamageModel damage() { return damage; }
    public fi.alavesa.superbvehicles.core.Persistence persistence() { return persistence; }
    public fi.alavesa.superbvehicles.core.Items items() { return items; }
    public double cfg(String key, double def) { return getConfig().getDouble(key, def); }
    public boolean flag(String key, boolean def) { return getConfig().getBoolean(key, def); }
}
