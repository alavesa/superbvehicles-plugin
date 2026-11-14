package fi.alavesa.superbvehicles.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a .bbmodel (Blockbench 4.x and 5.x) into a {@link BbModel}. Never throws on a merely odd file: missing or
 * malformed pieces become warnings with the element's name and what to fix; only a file that is not JSON or holds no
 * geometry at all is rejected with a {@link BbParseException} that says why.
 */
public final class BbParser {
    public static final class BbParseException extends IOException {
        public BbParseException(String msg) { super(msg); }
    }

    private static final String[] FACE = {"north", "south", "east", "west", "up", "down"};

    private BbParser() { }

    public static BbModel parse(File file) throws IOException {
        String text;
        try { text = Files.readString(file.toPath()); } catch (IOException e) { throw new BbParseException(file.getName() + ": cannot read the file (" + e.getMessage() + ")"); }
        String name = file.getName().replaceAll("\\.bbmodel$", "");
        return parse(text, name, file.getParentFile());
    }

    public static BbModel parse(String json, String name) throws BbParseException { return parse(json, name, null); }

    /** @param dir the models folder: textures that are not embedded are looked up there (null = embedded only). */
    public static BbModel parse(String json, String name, File dir) throws BbParseException {
        JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (Exception e) { throw new BbParseException(name + ".bbmodel is not valid JSON (" + e.getMessage() + ") - re-save it from Blockbench"); }
        BbModel m = new BbModel();
        m.name = name;
        if (root.has("meta") && root.get("meta").isJsonObject() && root.getAsJsonObject("meta").has("format_version")) {
            try { m.formatMajor = Integer.parseInt(str(root.getAsJsonObject("meta").get("format_version"), "4").split("\\.")[0].trim()); } catch (NumberFormatException ignored) { }
        }
        boolean metaBoxUv = root.has("meta") && root.get("meta").isJsonObject() && root.getAsJsonObject("meta").has("box_uv") && bool(root.getAsJsonObject("meta").get("box_uv"));
        if (m.formatMajor >= 5) { m.animRotSign = new double[]{1, 1, 1}; m.animPosSign = new double[]{1, 1, 1}; }
        else { m.animRotSign = new double[]{-1, -1, 1}; m.animPosSign = new double[]{-1, 1, 1}; }
        if (root.has("resolution") && root.get("resolution").isJsonObject()) {
            JsonObject r = root.getAsJsonObject("resolution");
            m.resW = num(r.get("width"), 16); m.resH = num(r.get("height"), 16);
        }
        // textures
        Map<String, Integer> texIndex = new HashMap<>();
        if (root.has("textures") && root.get("textures").isJsonArray()) {
            int ti = 0;
            for (JsonElement te : root.getAsJsonArray("textures")) {
                if (!te.isJsonObject()) continue;
                JsonObject t = te.getAsJsonObject();
                BbModel.Texture tex = new BbModel.Texture();
                tex.name = str(t.get("name"), "tex" + ti).replaceAll("\\.png$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                if (tex.name.isEmpty()) tex.name = "tex" + ti;
                String src = str(t.get("source"), "");
                if (src.contains("base64,")) {
                    try {
                        tex.png = Base64.getDecoder().decode(src.substring(src.indexOf("base64,") + 7).trim());
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(tex.png));
                        if (img != null) { tex.width = img.getWidth(); tex.height = img.getHeight(); }
                    } catch (Exception e) { m.warnings.add("texture '" + tex.name + "' could not be decoded (" + e.getMessage() + ") - re-import the image in Blockbench"); tex.png = null; }
                } else {
                    File ext = externalTexture(dir, name, tex.name, str(t.get("path"), ""), str(t.get("relative_path"), ""));
                    if (ext != null) {
                        try {
                            tex.png = java.nio.file.Files.readAllBytes(ext.toPath());
                            BufferedImage img = ImageIO.read(new ByteArrayInputStream(tex.png));
                            if (img != null) { tex.width = img.getWidth(); tex.height = img.getHeight(); } else { tex.png = null; m.warnings.add("texture file " + ext.getName() + " is not a readable PNG"); }
                        } catch (IOException e) { m.warnings.add("texture file " + ext.getName() + " could not be read: " + e.getMessage()); }
                    }
                    if (tex.png == null) m.warnings.add("texture '" + tex.name + "' is not embedded in the .bbmodel and no " + tex.name + ".png was found next to it (models/, models/" + name + "/ or models/textures/) - save the model with the texture loaded in Blockbench, or copy the PNG there");
                }
                if (dir != null) {
                    // an animated texture needs an explicit <texture>.png.mcmeta sidecar: tall vehicle atlases are NOT animation strips
                    for (File side : new File[]{new File(dir, name + "/" + tex.name + ".png.mcmeta"), new File(dir, tex.name + ".png.mcmeta"), new File(dir, "textures/" + tex.name + ".png.mcmeta")})
                        if (side.isFile()) { try { tex.mcmeta = java.nio.file.Files.readAllBytes(side.toPath()); } catch (IOException ignored) { } break; }
                }
                tex.uvWidth = num(t.get("uv_width"), m.resW); tex.uvHeight = num(t.get("uv_height"), m.resH);
                if (tex.uvWidth <= 0) tex.uvWidth = m.resW; if (tex.uvHeight <= 0) tex.uvHeight = m.resH;
                tex.frameTime = Math.max(1, (int) num(t.get("frame_time"), 1));
                tex.interpolate = t.has("frame_interpolate") && bool(t.get("frame_interpolate"));
                texIndex.put(str(t.get("uuid"), "u" + ti), ti);
                texIndex.put(str(t.get("id"), String.valueOf(ti)), ti);
                m.textures.add(tex);
                ti++;
            }
        }
        if (m.textures.isEmpty()) m.warnings.add("no textures in the file - parts are drawn with a plain placeholder texture");
        // elements
        Map<String, BbModel.Locator> locByUuid = new HashMap<>();
        if (root.has("elements") && root.get("elements").isJsonArray()) {
            int idx = 0;
            for (JsonElement ee : root.getAsJsonArray("elements")) {
                idx++;
                if (!ee.isJsonObject()) continue;
                JsonObject e = ee.getAsJsonObject();
                String type = str(e.get("type"), "cube");
                String ename = str(e.get("name"), type + idx);
                String uuid = str(e.get("uuid"), "e" + idx);
                if (type.equals("locator") || type.equals("null_object")) {
                    BbModel.Locator l = new BbModel.Locator();
                    l.uuid = uuid; l.name = ename;
                    l.position = vec(e.get("position"), ename, "position", m);
                    l.rotation = vec(e.get("rotation"), ename, "rotation", m);
                    locByUuid.put(uuid, l);
                    String key = ename.toLowerCase(Locale.ROOT);
                    if (m.locators.containsKey(key)) m.warnings.add("two locators are named '" + ename + "' - only the first is used; rename one");
                    else m.locators.put(key, l);
                    continue;
                }
                if (type.equals("mesh")) {
                    BbModel.Cube c = meshBox(e, uuid, ename, m);
                    if (c != null) { m.cubes.put(uuid, c); m.meshCount++; }
                    continue;
                }
                if (!type.equals("cube")) { m.warnings.add("element '" + ename + "' of type '" + type + "' is not supported and was skipped"); continue; }
                BbModel.Cube c = new BbModel.Cube();
                c.uuid = uuid; c.name = ename;
                c.hidden = e.has("visibility") && !bool(e.get("visibility"));
                if (c.hidden) m.hiddenCubes++;
                c.from = vec(e.get("from"), ename, "from", m); c.to = vec(e.get("to"), ename, "to", m);
                c.origin = e.has("origin") ? vec(e.get("origin"), ename, "origin", m) : new double[]{0, 0, 0};
                c.rotation = e.has("rotation") ? vec(e.get("rotation"), ename, "rotation", m) : new double[]{0, 0, 0};
                c.inflate = num(e.get("inflate"), 0);
                c.mirror = e.has("mirror_uv") && bool(e.get("mirror_uv"));
                c.boxUv = e.has("box_uv") ? bool(e.get("box_uv")) : metaBoxUv;
                if (e.has("uv_offset") && e.get("uv_offset").isJsonArray() && e.getAsJsonArray("uv_offset").size() >= 2) { JsonArray u = e.getAsJsonArray("uv_offset"); c.uvOffset = new double[]{num(u.get(0), 0), num(u.get(1), 0)}; }
                for (int i = 0; i < 3; i++) if (c.to[i] < c.from[i]) { double t = c.to[i]; c.to[i] = c.from[i]; c.from[i] = t; }
                if (e.has("faces") && e.get("faces").isJsonObject()) {
                    JsonObject fo = e.getAsJsonObject("faces");
                    for (String f : FACE) {
                        if (!fo.has(f) || !fo.get(f).isJsonObject()) continue;
                        JsonObject fj = fo.getAsJsonObject(f);
                        BbModel.Face face = new BbModel.Face();
                        JsonElement tex = fj.get("texture");
                        if (tex == null || tex.isJsonNull() || (tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isBoolean())) { m.texturelessFaces++; continue; }   // no texture in Blockbench = no face
                        else if (tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isNumber()) face.texture = tex.getAsInt();
                        else face.texture = texIndex.getOrDefault(tex.getAsString(), -1);
                        if (face.texture >= m.textures.size()) face.texture = m.textures.isEmpty() ? -1 : 0;
                        if (fj.has("uv") && fj.get("uv").isJsonArray() && fj.getAsJsonArray("uv").size() >= 4) { JsonArray a = fj.getAsJsonArray("uv"); face.uv = new double[]{num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0), num(a.get(3), 0)}; }
                        face.rotation = (int) num(fj.get("rotation"), 0);
                        c.faces.put(f, face);
                    }
                }
                m.cubes.put(uuid, c);
            }
        }
        if (m.cubes.isEmpty()) throw new BbParseException(name + ".bbmodel holds no cube (or mesh) elements - a vehicle model needs geometry");
        if (m.meshCount > 0) m.warnings.add(m.meshCount + " mesh element(s) were replaced by their bounding boxes - Java item models only hold cubes; rebuild round parts from cubes");
        // bones
        Map<String, JsonObject> groups = new HashMap<>();
        if (root.has("groups") && root.get("groups").isJsonArray()) for (JsonElement ge : root.getAsJsonArray("groups")) if (ge.isJsonObject() && ge.getAsJsonObject().has("uuid")) groups.put(str(ge.getAsJsonObject().get("uuid"), ""), ge.getAsJsonObject());
        if (root.has("outliner") && root.get("outliner").isJsonArray()) for (JsonElement oe : root.getAsJsonArray("outliner")) outliner(oe, null, m, groups, locByUuid);
        for (BbModel.Cube c : m.cubes.values()) if (c.bone == null) { /* loose cube at top level: belongs to the virtual root */ }
        // animations
        if (root.has("animations") && root.get("animations").isJsonArray()) {
            for (JsonElement ae : root.getAsJsonArray("animations")) {
                if (!ae.isJsonObject()) continue;
                BbModel.Animation a = animation(ae.getAsJsonObject(), m);
                if (a == null) continue;
                if (m.animations.containsKey(a.name)) { m.warnings.add("two animations are named '" + a.name + "' - only the first is used"); continue; }
                m.animations.put(a.name, a);
            }
        }
        validate(m);
        return m;
    }

    /** A texture PNG for a texture that is not embedded: models/<model>/<name>.png, models/<name>.png, models/textures/<name>.png, or the file name of its Blockbench path. */
    static File externalTexture(File dir, String model, String texName, String path, String relPath) {
        if (dir == null) return null;
        java.util.List<File> c = new java.util.ArrayList<>();
        c.add(new File(dir, model + "/" + texName + ".png")); c.add(new File(dir, texName + ".png")); c.add(new File(dir, "textures/" + texName + ".png"));
        for (String p : new String[]{path, relPath}) {
            if (p == null || p.isBlank()) continue;
            String base = new File(p.replace('\\', '/')).getName();
            c.add(new File(dir, model + "/" + base)); c.add(new File(dir, base)); c.add(new File(dir, "textures/" + base));
        }
        for (File f : c) if (f.isFile()) return f;
        return null;
    }

    private static void outliner(JsonElement oe, BbModel.Bone parent, BbModel m, Map<String, JsonObject> groups, Map<String, BbModel.Locator> locByUuid) {
        if (oe.isJsonPrimitive()) {
            String id = oe.getAsString();
            BbModel.Cube c = m.cubes.get(id);
            if (c != null) { c.bone = parent; if (parent != null) parent.cubes.add(c); return; }
            BbModel.Locator l = locByUuid.get(id);
            if (l != null) { l.bone = parent; if (parent != null) parent.locators.add(l); }
            return;
        }
        if (!oe.isJsonObject()) return;
        JsonObject o = oe.getAsJsonObject();
        BbModel.Bone b = new BbModel.Bone();
        b.uuid = str(o.get("uuid"), "b" + m.bones.size());
        JsonObject g = groups.get(b.uuid);
        JsonObject src = o.has("name") || g == null ? o : g;   // Blockbench 5 keeps name/origin/rotation in the groups table
        b.name = str(src.get("name"), b.uuid);
        if (src.has("origin")) b.origin = vec(src.get("origin"), b.name, "origin", m);
        if (src.has("rotation")) b.rotation = vec(src.get("rotation"), b.name, "rotation", m);
        b.hidden = src.has("visibility") && !bool(src.get("visibility"));
        b.parent = parent;
        if (m.bones.containsKey(b.uuid)) b.uuid = b.uuid + "_" + m.bones.size();
        m.bones.put(b.uuid, b);
        if (parent == null) m.roots.add(b); else parent.children.add(b);
        if (o.has("children") && o.get("children").isJsonArray()) for (JsonElement ce : o.getAsJsonArray("children")) outliner(ce, b, m, groups, locByUuid);
    }

    private static BbModel.Animation animation(JsonObject a, BbModel m) {
        BbModel.Animation an = new BbModel.Animation();
        an.name = str(a.get("name"), "anim");
        an.length = Math.max(0, num(a.get("length"), 0));
        String loop = str(a.get("loop"), "once").toLowerCase(Locale.ROOT);
        an.loop = loop.equals("loop"); an.hold = loop.equals("hold");
        if (a.has("animators") && a.get("animators").isJsonObject()) for (var en : a.getAsJsonObject("animators").entrySet()) {
            if (!en.getValue().isJsonObject()) continue;
            JsonObject anim = en.getValue().getAsJsonObject();
            if (!anim.has("keyframes") || !anim.get("keyframes").isJsonArray()) continue;
            List<Track.Key> rot = new ArrayList<>(), pos = new ArrayList<>(), sc = new ArrayList<>();
            for (JsonElement ke : anim.getAsJsonArray("keyframes")) {
                if (!ke.isJsonObject()) continue;
                JsonObject k = ke.getAsJsonObject();
                String ch = str(k.get("channel"), "");
                switch (ch) {
                    case "rotation" -> rot.add(key(k, an.name, m));
                    case "position" -> pos.add(key(k, an.name, m));
                    case "scale" -> sc.add(key(k, an.name, m));
                    case "sound" -> {
                        if (k.has("data_points") && k.get("data_points").isJsonArray()) for (JsonElement de : k.getAsJsonArray("data_points")) {
                            if (!de.isJsonObject()) continue;
                            JsonObject d = de.getAsJsonObject();
                            String ev = str(d.get("effect"), "");
                            String file = str(d.get("file"), "");
                            if (ev.isEmpty() && !file.isEmpty()) ev = new File(file).getName().replaceAll("\\.ogg$", "");
                            if (!ev.isEmpty()) an.sounds.add(new BbModel.SoundKey(num(k.get("time"), 0), ev));
                        }
                    }
                    default -> { }
                }
                if (an.length <= 0) an.length = Math.max(an.length, num(k.get("time"), 0));
            }
            if (rot.isEmpty() && pos.isEmpty() && sc.isEmpty()) continue;
            BbModel.Channels c = new BbModel.Channels();
            if (!rot.isEmpty()) c.rot = new Track(rot, 0);
            if (!pos.isEmpty()) c.pos = new Track(pos, 0);
            if (!sc.isEmpty()) c.scale = new Track(sc, 1);
            an.channels.put(en.getKey(), c);
        }
        if (an.length <= 0) { double last = 0; for (BbModel.Channels c : an.channels.values()) last = Math.max(last, Math.max(c.rot.lastTime(), Math.max(c.pos.lastTime(), c.scale.lastTime()))); an.length = last; }
        if (an.length <= 0) an.length = 0.05;
        return an;
    }

    private static Track.Key key(JsonObject k, String anim, BbModel m) {
        double[] v = new double[3];
        if (k.has("data_points") && k.get("data_points").isJsonArray() && !k.getAsJsonArray("data_points").isEmpty() && k.getAsJsonArray("data_points").get(0).isJsonObject()) {
            JsonObject p = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
            v[0] = numExpr(p.get("x"), anim, m); v[1] = numExpr(p.get("y"), anim, m); v[2] = numExpr(p.get("z"), anim, m);
        }
        Track.Key key = new Track.Key(num(k.get("time"), 0), v, Track.mode(str(k.get("interpolation"), "linear")));
        if (key.mode == Track.Mode.BEZIER) {
            key.leftTime = vecOrNull(k.get("bezier_left_time")); key.leftValue = vecOrNull(k.get("bezier_left_value"));
            key.rightTime = vecOrNull(k.get("bezier_right_time")); key.rightValue = vecOrNull(k.get("bezier_right_value"));
        }
        return key;
    }

    /** Keyframe values may be Molang expressions; only numbers are supported (the spec says numeric only). */
    private static double numExpr(JsonElement e, String anim, BbModel m) {
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        String s = e.getAsString().trim();
        if (s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException ex) {
            String w = "animation '" + anim + "' uses a Molang expression '" + s + "' - only numeric keyframes are supported, it was read as 0";
            if (!m.warnings.contains(w)) m.warnings.add(w);
            return 0;
        }
    }

    private static BbModel.Cube meshBox(JsonObject e, String uuid, String name, BbModel m) {
        if (!e.has("vertices") || !e.get("vertices").isJsonObject()) return null;
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (var v : e.getAsJsonObject("vertices").entrySet()) {
            if (!v.getValue().isJsonArray()) continue;
            double[] p = vec(v.getValue(), name, "vertex", m);
            for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
        }
        if (mn[0] > mx[0]) return null;
        BbModel.Cube c = new BbModel.Cube();
        c.uuid = uuid; c.name = name; c.mesh = true;
        c.origin = e.has("origin") ? vec(e.get("origin"), name, "origin", m) : new double[]{0, 0, 0};
        c.rotation = e.has("rotation") ? vec(e.get("rotation"), name, "rotation", m) : new double[]{0, 0, 0};
        c.from = new double[]{c.origin[0] + mn[0], c.origin[1] + mn[1], c.origin[2] + mn[2]};
        c.to = new double[]{c.origin[0] + mx[0], c.origin[1] + mx[1], c.origin[2] + mx[2]};
        for (int i = 0; i < 3; i++) if (c.to[i] - c.from[i] < 0.05) c.to[i] = c.from[i] + 0.05;
        int tex = -1;
        if (e.has("faces") && e.get("faces").isJsonObject()) for (var f : e.getAsJsonObject("faces").entrySet()) {
            if (!f.getValue().isJsonObject()) continue;
            JsonElement t = f.getValue().getAsJsonObject().get("texture");
            if (t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isNumber()) { tex = t.getAsInt(); break; }
        }
        for (String f : FACE) { BbModel.Face face = new BbModel.Face(); face.texture = tex < 0 || tex >= m.textures.size() ? (m.textures.isEmpty() ? -1 : 0) : tex; c.faces.put(f, face); }
        return c;
    }

    private static void validate(BbModel m) {
        int noFace = 0, badTex = 0;
        for (BbModel.Cube c : m.cubes.values()) {
            if (c.faces.isEmpty()) noFace++;
            for (BbModel.Face f : c.faces.values()) if (f.texture < 0 && !m.textures.isEmpty()) badTex++;
            for (int i = 0; i < 3; i++) if (Double.isNaN(c.from[i]) || Double.isNaN(c.to[i]) || Double.isInfinite(c.from[i]) || Double.isInfinite(c.to[i])) { c.from = new double[]{0, 0, 0}; c.to = new double[]{1, 1, 1}; m.warnings.add("cube '" + c.name + "' has a non-finite coordinate and was reset to a unit cube"); break; }
        }
        if (noFace > 0) m.warnings.add(noFace + " cube(s) have no faces at all - they are invisible");
        if (badTex > 0) m.warnings.add(badTex + " face(s) point at no texture - they render with the first texture");
        double[] b = m.bounds();
        double extent = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        if (extent > 160 * 16) m.warnings.add("the model is " + Math.round(extent / 16) + " blocks across - parts more than 80 px from their pivot are clamped by the client; split big groups");
        for (BbModel.Bone bone : m.bones.values()) {
            if (bone.cubes.isEmpty() && bone.children.isEmpty() && bone.locators.isEmpty()) m.warnings.add("group '" + bone.name + "' is empty");
            for (BbModel.Bone other : m.bones.values()) if (other != bone && other.name.equalsIgnoreCase(bone.name) && BoneRole.of(bone.name) != BoneRole.NONE && other.uuid.compareTo(bone.uuid) < 0) { m.warnings.add("two groups are named '" + bone.name + "' - roles are matched by name, rename one"); break; }
        }
        if (m.locators.isEmpty()) m.warnings.add("no locators: seats, muzzles and wheel centres fall back to bone pivots / the model bounds - add Blockbench locators named seat_driver, muzzle_1 ... for precise placement");
    }

    // ---- json helpers
    static double num(JsonElement e, double def) {
        if (e == null || e.isJsonNull()) return def;
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return Double.parseDouble(e.getAsString().trim());
            double v = e.getAsDouble();
            return Double.isFinite(v) ? v : def;
        } catch (Exception ex) { return def; }
    }
    static boolean bool(JsonElement e) { try { return e != null && !e.isJsonNull() && e.getAsBoolean(); } catch (Exception ex) { return false; } }
    static String str(JsonElement e, String def) { try { return e == null || e.isJsonNull() ? def : e.getAsString(); } catch (Exception ex) { return def; } }
    private static double[] vec(JsonElement e, String owner, String what, BbModel m) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) { if (e != null && !e.isJsonNull()) m.warnings.add("'" + owner + "' has a malformed " + what + " - read as 0,0,0"); return new double[]{0, 0, 0}; }
        JsonArray a = e.getAsJsonArray();
        return new double[]{num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0)};
    }
    private static double[] vecOrNull(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) return null;
        JsonArray a = e.getAsJsonArray();
        return new double[]{num(a.get(0), 0), num(a.get(1), 0), num(a.get(2), 0)};
    }
}
