package fi.alavesa.superbvehicles.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.alavesa.superbvehicles.hud.HudEncoding;
import fi.alavesa.superbvehicles.hud.HudLayout;
import fi.alavesa.superbvehicles.hud.HudShaders;
import fi.alavesa.superbvehicles.hud.HudTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The SECOND resource pack: the shader HUD (core text shader overrides, one font per HUD skin, the gauge textures).
 * It is sent separately from the vehicle models pack so the models keep working on clients that cannot use it.
 * <p>
 * Pack-build checks (a failing check means NO shader pack is written and players get the text HUD):
 * required files present, pack.mcmeta valid, every font references an existing texture with a sane height, no
 * colour the plugin itself sends (messages.yml, the 16 named colours, the text HUD colours) falls in the marker range.
 */
public final class HudPackBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public static final String[] SKINS = {"military", "civilian", "aircraft"};
    /** Pack format of Minecraft 26.3, the only version these shaders are written for. */
    public static final int PACK_FORMAT = 97;
    /** GUI height every HUD glyph has in the font (only matters when the shader is not active). */
    public static final int GLYPH_HEIGHT = 8;

    /** glyph name -> character in the hud_<skin> fonts. */
    public static final Map<String, Character> CHARS = new LinkedHashMap<>();
    static {
        String[] names = {"dial", "needle", "vsi_face", "horizon", "pitch_bar", "fuel_bar_face", "fuel_bar_fill", "fuel_arc_face", "fuel_arc_fill", "warn", "engine_off", "unit_kmh", "unit_mph"};
        for (int i = 0; i < names.length; i++) CHARS.put(names[i], (char) (0xE100 + i));
        for (int d = 0; d <= 9; d++) CHARS.put("digit" + d, (char) (0xE110 + d));
    }
    /** Negative space characters in every hud font: SPACE[i] advances -(2^i). */
    public static final char SPACE_BASE = 0xE200;
    public static final int SPACE_BITS = 10;

    public record Result(File zip, String sha1, List<String> problems, Map<String, Integer> advances, long bytes) { }

    private final File out;
    private final HudLayout layout;
    private final Collection<Integer> pluginColors;

    public HudPackBuilder(File out, HudLayout layout, Collection<Integer> pluginColors) { this.out = out; this.layout = layout; this.pluginColors = pluginColors; }

    public Result build() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, Integer> advances = new LinkedHashMap<>();
        JsonObject meta = new JsonObject(), pack = new JsonObject();
        pack.addProperty("description", "SuperbVehicles shader HUD (Minecraft 26.3)");
        pack.addProperty("pack_format", PACK_FORMAT); pack.addProperty("min_format", PACK_FORMAT); pack.addProperty("max_format", PACK_FORMAT);
        meta.add("pack", pack);
        files.put("pack.mcmeta", GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        files.put("assets/minecraft/shaders/core/text.vsh", HudShaders.vertex(layout).getBytes(StandardCharsets.UTF_8));
        files.put("assets/minecraft/shaders/core/text.fsh", HudShaders.fragment(layout).getBytes(StandardCharsets.UTF_8));
        for (String skin : SKINS) {
            Map<String, byte[]> art = HudTextures.all(HudTextures.skin(skin));
            JsonArray providers = new JsonArray();
            for (var e : CHARS.entrySet()) {
                byte[] png = art.get(e.getKey());
                if (png == null) { problems.add("skin " + skin + ": no texture for glyph " + e.getKey()); continue; }
                String path = "assets/superbvehicles/textures/font/hud/" + skin + "/" + e.getKey() + ".png";
                files.put(path, png);
                JsonObject b = new JsonObject();
                b.addProperty("type", "bitmap");
                b.addProperty("file", "superbvehicles:font/hud/" + skin + "/" + e.getKey() + ".png");
                b.addProperty("height", GLYPH_HEIGHT);
                b.addProperty("ascent", GLYPH_HEIGHT - 1);
                JsonArray chars = new JsonArray(); chars.add(String.valueOf(e.getValue())); b.add("chars", chars);
                providers.add(b);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                advances.put(e.getKey(), (int) (0.5 + img.getWidth() * ((double) GLYPH_HEIGHT / img.getHeight())) + 1);
            }
            JsonObject space = new JsonObject(); space.addProperty("type", "space");
            JsonObject adv = new JsonObject();
            for (int i = 0; i < SPACE_BITS; i++) adv.addProperty(String.valueOf((char) (SPACE_BASE + i)), -(1 << i));
            space.add("advances", adv);
            providers.add(space);
            JsonObject font = new JsonObject(); font.add("providers", providers);
            files.put("assets/superbvehicles/font/hud_" + skin + ".json", GSON.toJson(font).getBytes(StandardCharsets.UTF_8));
        }
        problems.addAll(check(files, pluginColors));
        out.getParentFile().mkdirs();
        if (!problems.isEmpty()) { Files.deleteIfExists(out.toPath()); return new Result(null, "", problems, advances, 0); }
        File tmp = new File(out.getPath() + ".tmp");
        long bytes = 0;
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); bytes += e.getValue().length; }
        }
        Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String sha1;
        try { sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(out.toPath()))); } catch (Exception e) { sha1 = ""; }
        return new Result(out, sha1, problems, advances, bytes);
    }

    /** The automated pack checks; empty = fine. */
    public static List<String> check(Map<String, byte[]> files, Collection<Integer> pluginColors) {
        List<String> p = new ArrayList<>();
        for (String req : new String[]{"pack.mcmeta", "assets/minecraft/shaders/core/text.vsh", "assets/minecraft/shaders/core/text.fsh"})
            if (!files.containsKey(req)) p.add("required file missing: " + req);
        try {
            JsonObject m = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
            if (m.get("min_format").getAsInt() < 65) p.add("pack.mcmeta min_format below 65 is rejected by 26.x clients");
        } catch (Exception e) { p.add("pack.mcmeta is not valid: " + e.getMessage()); }
        for (var e : files.entrySet()) {
            if (!e.getKey().endsWith(".json")) continue;
            try {
                JsonObject font = JsonParser.parseString(new String(e.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!e.getKey().contains("/font/")) continue;
                for (var pe : font.getAsJsonArray("providers")) {
                    JsonObject pr = pe.getAsJsonObject();
                    if (!"bitmap".equals(pr.get("type").getAsString())) continue;
                    String file = pr.get("file").getAsString();
                    String path = "assets/" + file.substring(0, file.indexOf(':')) + "/textures/" + file.substring(file.indexOf(':') + 1);
                    if (!files.containsKey(path)) p.add(e.getKey() + ": texture " + path + " missing");
                    if (pr.get("ascent").getAsInt() > pr.get("height").getAsInt()) p.add(e.getKey() + ": ascent above height");
                }
            } catch (Exception ex) { p.add(e.getKey() + " is not valid JSON: " + ex.getMessage()); }
        }
        for (String sh : new String[]{"assets/minecraft/shaders/core/text.vsh", "assets/minecraft/shaders/core/text.fsh"}) {
            String src = files.containsKey(sh) ? new String(files.get(sh), StandardCharsets.UTF_8) : "";
            if (!src.startsWith("#version 330")) p.add(sh + ": must start with #version 330");
            if (src.contains("#moj_import")) p.add(sh + ": #moj_import is the pre-26.3 syntax");
            if (!src.contains("#ifdef IS_GUI")) p.add(sh + ": HUD code must be guarded by IS_GUI");
        }
        for (int rgb : pluginColors) if (HudEncoding.isMarker(rgb)) p.add(String.format("colour #%06X used by the plugin collides with the HUD marker range #FDA000-#FDBFFF", rgb));
        return p;
    }

    /** Every #RRGGBB / <#RRGGBB> / <color:#...> in a MiniMessage text. */
    public static List<Integer> colorsIn(String text) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("#([0-9a-fA-F]{6})").matcher(text == null ? "" : text);
        while (m.find()) out.add(Integer.parseInt(m.group(1), 16));
        return out;
    }
}
