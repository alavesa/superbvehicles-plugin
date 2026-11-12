package fi.alavesa.superbvehicles.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BbParser;
import fi.alavesa.superbvehicles.bbmodel.BoneRole;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.VehicleType;
import org.bukkit.configuration.ConfigurationSection;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds plugins/SuperbVehicles/pack/SuperbVehicles-pack.zip from every vehicle type's .bbmodel (missing files get
 * a generated placeholder written to models/), the projectile models, item icons, HUD glyph font, sounds.json from
 * models/sounds/*.ogg, and pack.mcmeta. Safe to run off the main thread: it only reads files and returns the rigs;
 * nothing touches Bukkit. The zip is written to a temp file and moved into place.
 */
public final class PackBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final String[] PROJECTILES = {"projectile_shell", "projectile_rocket", "projectile_missile", "projectile_bomb"};
    public static final String[] UTILITY_ITEMS = {"fuel_can", "ammo_crate", "repair_kit"};

    public record Result(File zip, String sha1, Map<String, Rig> rigs, List<String> warnings, List<String> report, int files, long bytes, long millis, boolean placeholders) { }

    private final File dataFolder;
    private final Collection<VehicleType> types;
    private final ConfigurationSection cfg;
    private final Logger log;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>(), report = new ArrayList<>();
    private final Map<String, Rig> rigs = new LinkedHashMap<>();
    private final Map<String, List<String>> soundEvents = new LinkedHashMap<>();
    private boolean placeholders;

    public PackBuilder(File dataFolder, Collection<VehicleType> types, ConfigurationSection cfg, Logger log) {
        this.dataFolder = dataFolder; this.types = types; this.cfg = cfg; this.log = log;
    }

    public File modelsDir() { return new File(dataFolder, "models"); }
    public File zipFile() { return new File(new File(dataFolder, "pack"), "SuperbVehicles-pack.zip"); }

    public Result build() throws IOException {
        long t0 = System.currentTimeMillis();
        File models = modelsDir(); models.mkdirs();
        new File(models, "skins").mkdirs(); new File(models, "sounds").mkdirs();
        Set<String> done = new LinkedHashSet<>();
        boolean keepHidden = cfg.getBoolean("pack.include-hidden-cubes", false);
        long faces = 0;
        for (VehicleType t : types) {
            if (!done.add(t.model)) continue;
            try {
                BbModel m = load(models, t.model, t);
                Map<String, Map<String, byte[]>> skins = new LinkedHashMap<>();
                for (var e : t.skins.entrySet()) {
                    Map<String, byte[]> map = new LinkedHashMap<>();
                    for (var tx : e.getValue().entrySet()) {
                        File png = new File(new File(models, "skins"), tx.getValue());
                        if (!png.isFile()) png = new File(models, tx.getValue());
                        if (png.isFile()) map.put(tx.getKey(), Files.readAllBytes(png.toPath()));
                        else warnings.add(t.id + ": skin '" + e.getKey() + "' texture file '" + tx.getValue() + "' not found in models/skins/ - the default texture is used");
                    }
                    skins.put(e.getKey(), map);
                }
                List<String> anims = new ArrayList<>(m.animations.keySet());
                CompositeBuilder.Output out = CompositeBuilder.build(m, Keys.NS, CompositeBuilder.path(t.model), t.boneRoles, t.hideGroups, skins, keepHidden, anims);
                files.putAll(out.files());
                for (String w : out.warnings()) warnings.add(t.model + ": " + w);
                rigs.put(t.model, out.rig());
                faces += out.faces();
                report.add(t.id + " (" + t.model + ".bbmodel): " + out.parts() + " part(s) for " + out.rig().animatable.size() + " animatable bone(s), " + m.cubes.size() + " cubes, " + out.faces() + " faces, " + m.animations.size() + " animation(s) " + m.animations.keySet() + ", locators " + m.locators.keySet());
                for (BbModel.Animation a : m.animations.values()) for (BbModel.SoundKey sk : a.sounds) registerSound(models, sk.effect(), t.model + " animation '" + a.name + "'");
                icon(models, t.model, t.displayName, t.id, accent(t));
            } catch (Exception ex) {
                warnings.add(t.id + ": model '" + t.model + "' could not be converted (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ") - the vehicle has no visible body");
                log.warning("[SuperbVehicles] " + t.model + ".bbmodel failed: " + ex);
            }
        }
        for (String p : PROJECTILES) {
            try {
                BbModel m = load(models, p, null);
                CompositeBuilder.Output out = CompositeBuilder.build(m, Keys.NS, p, Map.of(), List.of(), Map.of(), keepHidden, List.of());
                files.putAll(out.files());
                rigs.put(p, out.rig());
            } catch (Exception ex) { warnings.add(p + ": " + ex.getMessage()); }
        }
        for (String u : UTILITY_ITEMS) icon(models, u, u.replace('_', ' '), u, 0xE8A33D);
        // sounds: models/sounds/*.ogg -> superbvehicles:<name>
        File soundsDir = new File(models, "sounds");
        File[] oggs = soundsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ogg"));
        if (oggs != null) for (File ogg : oggs) registerSound(models, ogg.getName().substring(0, ogg.getName().length() - 4), "models/sounds");
        if (!soundEvents.isEmpty()) {
            JsonObject sj = new JsonObject();
            for (var e : soundEvents.entrySet()) {
                JsonObject ev = new JsonObject(); JsonArray arr = new JsonArray();
                for (String f : e.getValue()) arr.add(Keys.NS + ":" + f);
                ev.add("sounds", arr); sj.add(e.getKey(), ev);
            }
            put("assets/" + Keys.NS + "/sounds.json", GSON.toJson(sj));
            report.add("sounds in the pack (use as superbvehicles:<name>): " + soundEvents.keySet());
        }
        if (cfg.getBoolean("hud.glyphs", true)) hudFont();
        // pack.mcmeta: 26.x clients reject packs declaring a format below 65 without supported_formats
        int min = cfg.getInt("pack.min-format", 97), max = cfg.getInt("pack.max-format", 999);
        if (min < 65) { report.add("pack.min-format " + min + " raised to 97 (formats below 65 are rejected by 26.x clients)"); min = 97; }
        if (max < min) max = 999;
        JsonObject pack = new JsonObject(), p = new JsonObject();
        p.addProperty("description", "SuperbVehicles (generated)"); p.addProperty("pack_format", min); p.addProperty("min_format", min); p.addProperty("max_format", max);
        pack.add("pack", p);
        put("pack.mcmeta", GSON.toJson(pack));
        report.add(0, "SuperbVehicles pack report - " + new java.util.Date());
        report.add(1, "client geometry: " + faces + " faces in all (" + rigs.size() + " models)");
        report.add("");
        report.add("warnings:");
        if (warnings.isEmpty()) report.add("  none"); else for (String w : new LinkedHashSet<>(warnings)) report.add("  ! " + w);
        put("pack-report.txt", String.join("\n", report));
        File zip = zipFile();
        zip.getParentFile().mkdirs();
        File tmp = new File(zip.getPath() + ".tmp");
        long bytes = 0;
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20))) {
            for (var e : files.entrySet()) { out.putNextEntry(new ZipEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); bytes += e.getValue().length; }
        }
        Files.move(tmp.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String sha1;
        try { sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(zip.toPath()))); } catch (Exception e) { sha1 = ""; }
        Files.writeString(new File(zip.getPath() + ".sha1").toPath(), sha1);
        try { Files.writeString(new File(dataFolder, "pack-report.txt").toPath(), String.join("\n", report)); } catch (IOException ignored) { }
        List<String> dedup = new ArrayList<>(new LinkedHashSet<>(warnings));
        return new Result(zip, sha1, rigs, dedup, report, files.size(), bytes, System.currentTimeMillis() - t0, placeholders);
    }

    /** The .bbmodel for a model name; a missing one is generated as a placeholder (and saved, so it can be edited). */
    private BbModel load(File models, String model, VehicleType type) throws IOException {
        File f = new File(models, model + ".bbmodel");
        if (!f.isFile()) {
            File[] all = models.listFiles();
            if (all != null) for (File x : all) if (x.getName().equalsIgnoreCase(model + ".bbmodel")) { warnings.add("models/" + x.getName() + " differs in case from model: '" + model + "' - renamed lookup used it, but match the case to be safe"); f = x; break; }
        }
        if (!f.isFile()) {
            String json = type != null ? PlaceholderModels.forType(type) : PlaceholderModels.projectile(model);
            Files.writeString(f.toPath(), json);
            placeholders = true;
            if (type != null) warnings.add(type.id + ": no models/" + model + ".bbmodel - a placeholder " + type.vehicleClass.name().toLowerCase(Locale.ROOT) + " model was generated there (open it in Blockbench and replace the boxes, keep the group names)");
        }
        return BbParser.parse(f);
    }

    private void registerSound(File models, String effect, String where) throws IOException {
        String sname = effect.toLowerCase(Locale.ROOT).replaceAll("\\.ogg$", "").replaceAll("[^a-z0-9_]", "_");
        if (sname.isEmpty()) return;
        File ogg = new File(new File(models, "sounds"), sname + ".ogg");
        if (!ogg.isFile()) { if (!where.equals("models/sounds")) warnings.add(where + ": sound '" + effect + "' - put " + sname + ".ogg into models/sounds/"); return; }
        if (!files.containsKey("assets/" + Keys.NS + "/sounds/" + sname + ".ogg")) files.put("assets/" + Keys.NS + "/sounds/" + sname + ".ogg", Files.readAllBytes(ogg.toPath()));
        soundEvents.computeIfAbsent(sname, x -> new ArrayList<>());
        if (!soundEvents.get(sname).contains(sname)) soundEvents.get(sname).add(sname);
    }

    private void icon(File models, String model, String displayName, String id, int accent) throws IOException {
        String mp = CompositeBuilder.path(model);
        File custom = new File(models, model + "_icon.png");
        byte[] png = custom.isFile() ? Files.readAllBytes(custom.toPath()) : Icons.label(displayName, id, accent);
        files.put("assets/" + Keys.NS + "/textures/item/icons/" + mp + ".png", png);
        JsonObject iconModel = new JsonObject();
        iconModel.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject(); tex.addProperty("layer0", Keys.NS + ":item/icons/" + mp);
        iconModel.add("textures", tex);
        put("assets/" + Keys.NS + "/models/item/icons/" + mp + ".json", GSON.toJson(iconModel));
        JsonObject item = new JsonObject(), mm = new JsonObject();
        mm.addProperty("type", "minecraft:model"); mm.addProperty("model", Keys.NS + ":item/icons/" + mp);
        item.add("model", mm);
        put("assets/" + Keys.NS + "/items/" + mp + "_icon.json", GSON.toJson(item));
    }

    public static String iconModel(String model) { return Keys.NS + ":" + CompositeBuilder.path(model) + "_icon"; }

    /** HUD font superbvehicles:hud - bar glyphs: U+E000..E00A health (green), U+E010..E01A fuel (orange), U+E020..E02A ammo (blue). */
    private void hudFont() throws IOException {
        JsonObject font = new JsonObject(); JsonArray providers = new JsonArray();
        int[] colors = {0x3CC45A, 0xE8A33D, 0x4FA3E8};
        for (int set = 0; set < 3; set++) for (int i = 0; i <= 10; i++) {
            String name = "bar_" + set + "_" + i;
            files.put("assets/" + Keys.NS + "/textures/font/" + name + ".png", Icons.barGlyph(i, 10, colors[set]));
            JsonObject bmp = new JsonObject();
            bmp.addProperty("type", "bitmap"); bmp.addProperty("file", Keys.NS + ":font/" + name + ".png"); bmp.addProperty("ascent", 7); bmp.addProperty("height", 8);
            JsonArray chars = new JsonArray(); chars.add(String.valueOf((char) (0xE000 + set * 16 + i))); bmp.add("chars", chars);
            providers.add(bmp);
        }
        font.add("providers", providers);
        put("assets/" + Keys.NS + "/font/hud.json", GSON.toJson(font));
    }

    private static int accent(VehicleType t) { return switch (t.vehicleClass) { case WHEELED -> 0x8BC34A; case TRACKED -> 0x6B8E23; case BOAT -> 0x4FA3E8; case HELICOPTER -> 0xE8A33D; case PLANE -> 0x9FA8DA; case STATIC -> 0xC0392B; }; }

    private void put(String path, String text) {
        if (text.contains("NaN") || text.contains("Infinity")) { text = text.replace("-Infinity", "0").replace("Infinity", "0").replace("NaN", "0"); warnings.add(path + ": non-finite values replaced by 0"); }
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
