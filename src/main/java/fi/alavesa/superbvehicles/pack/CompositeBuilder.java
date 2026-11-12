package fi.alavesa.superbvehicles.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BoneRole;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.VehicleType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Blockbench model -> one Java item model per ANIMATABLE bone group (a bone with a role, one an animation moves, or
 * a damage-state group); every cube under a static group is folded into the nearest animatable ancestor, so a model
 * with a hundred groups and four wheels becomes five parts. The geometry of a part is written once in bone-local
 * coordinates (pivot at the model centre) and the runtime ItemDisplay transformation carries the bone's exact
 * world matrix - no 22.5-degree snapping anywhere. A cube whose rotation relative to its owner is not one Java axis
 * at a 22.5 step gets a "display part" of its own (cubes sharing that rotation share the part) whose rotation lives
 * in the transformation. Textures are written per skin; a skin may replace any of the model's textures by name.
 */
public final class CompositeBuilder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String[] FACE = {"north", "south", "east", "west", "up", "down"};

    public record Output(Rig rig, Map<String, byte[]> files, List<String> warnings, long faces, int parts) { }

    private CompositeBuilder() { }

    /**
     * @param skinFiles skin name -> (texture name -> png bytes) replacing embedded textures; "*" replaces every texture.
     * @param hideGroups glob patterns of groups to leave out
     */
    public static Output build(BbModel m, String ns, String modelPath, Map<String, BoneRole> roleOverrides, List<String> hideGroups,
                               Map<String, Map<String, byte[]>> skinFiles, boolean keepHidden, List<String> animationNames) {
        List<String> warnings = new ArrayList<>(m.warnings);
        Map<String, byte[]> files = new LinkedHashMap<>();
        Rig rig = new Rig(m);
        rig.sealRest();

        // ---- which bones move on their own
        Map<BbModel.Bone, BoneRole> roles = new HashMap<>();
        for (BbModel.Bone b : m.bones.values()) {
            BoneRole r = roleOverrides.getOrDefault(b.name.toLowerCase(Locale.ROOT), BoneRole.of(b.name));
            roles.put(b, r);
            boolean animated = false;
            for (BbModel.Animation a : m.animations.values()) if ((animationNames == null || animationNames.contains(a.name)) && a.animates(b.uuid)) { animated = true; break; }
            if (r.animatable() || animated || isDamageState(r)) rig.animatable.add(b.uuid);
        }
        List<Pattern> hide = new ArrayList<>();
        for (String h : hideGroups) if (h != null && !h.isBlank()) hide.add(glob(h));

        // ---- partition cubes into parts
        Map<BbModel.Bone, Rig.Part> boneParts = new LinkedHashMap<>();   // owner -> bone part (null key = root)
        Map<String, Rig.Part> displayParts = new LinkedHashMap<>();      // owner|rotation key -> display part
        Map<Rig.Part, List<CubeOut>> cubesOf = new LinkedHashMap<>();
        Rig.Part rootPart = null;
        int hiddenBb = 0, hiddenByGroup = 0, folded = 0, scaledAway = 0;
        for (BbModel.Cube c : m.cubes.values()) {
            boolean hitboxBone = false;
            for (BbModel.Bone w = c.bone; w != null; w = w.parent) if (roles.getOrDefault(w, BoneRole.NONE) == BoneRole.HITBOX) hitboxBone = true;
            if (hitboxBone) continue;
            if (c.hidden && !keepHidden) { hiddenBb++; continue; }
            boolean skip = false;
            for (BbModel.Bone w = c.bone; w != null && !skip; w = w.parent) {
                if (w.hidden && !keepHidden) { hiddenBb++; skip = true; break; }
                String ln = w.name.toLowerCase(Locale.ROOT);
                for (Pattern p : hide) if (p.matcher(ln).matches()) { hiddenByGroup++; skip = true; break; }
            }
            if (skip) continue;
            BbModel.Bone owner = c.bone;
            while (owner != null && !rig.animatable.contains(owner.uuid)) owner = owner.parent;
            if (owner != c.bone) folded++;
            // the cube's static transform below its owner, at rest: v -> B3 v + t  (B = A * Rc about the cube origin)
            double[][] A = Mat.mul(Mat.invertAffine(rig.restWorld(owner)), rig.restWorld(c.bone));
            double[][] Rc = Mat.rotZYX(c.rotation);
            double[][] Bm = Mat.mul(A, Rc);
            double[] o = c.origin;
            double[] Ao = Mat.apply(A, o);
            double[][] B3 = Mat.upper3(Bm);
            double[] Bo = Mat.apply3(B3, o);
            double[] t = {Ao[0] - Bo[0], Ao[1] - Bo[1], Ao[2] - Bo[2]};
            double[] sc = Mat.columnScale(B3);
            if (sc[0] < 0.01 || sc[1] < 0.01 || sc[2] < 0.01) { scaledAway++; continue; }
            boolean unit = Math.abs(sc[0] - 1) < 1e-6 && Math.abs(sc[1] - 1) < 1e-6 && Math.abs(sc[2] - 1) < 1e-6;
            double[] ea = unit ? Mat.plainAxisAngle(B3) : null;
            CubeOut out = new CubeOut(c);
            if (ea != null) {
                Rig.Part part = boneParts.get(owner);
                if (part == null) { part = new Rig.Part(); part.owner = owner; part.pivot = owner == null ? new double[]{8, 8, 8} : owner.origin.clone(); boneParts.put(owner, part); if (owner == null) rootPart = part; }
                double[] d = {t[0] - o[0], t[1] - o[1], t[2] - o[2]};
                double[] rd = Mat.apply3(Mat.transpose3(B3), d);
                out.shift = new double[]{rd[0] + o[0], rd[1] + o[1], rd[2] + o[2]};
                out.elemOrigin = o.clone();
                out.elemRot = ea[1] != 0 ? ea : null;
                cubesOf.computeIfAbsent(part, x -> new ArrayList<>()).add(out);
            } else {
                StringBuilder rk = new StringBuilder(owner == null ? "root" : owner.uuid);
                for (double[] row : B3) for (double v : row) rk.append(',').append(Math.round(v * 10000));
                Rig.Part part = displayParts.get(rk.toString());
                if (part == null) {
                    part = new Rig.Part(); part.owner = owner; part.pivot = Ao.clone();
                    double[][] B4 = {{B3[0][0], B3[0][1], B3[0][2], 0}, {B3[1][0], B3[1][1], B3[1][2], 0}, {B3[2][0], B3[2][1], B3[2][2], 0}, {0, 0, 0, 1}};
                    part.extra = Mat.mul(Mat.mul(Mat.translate(Ao[0], Ao[1], Ao[2]), B4), Mat.translate(-Ao[0], -Ao[1], -Ao[2]));
                    displayParts.put(rk.toString(), part);
                }
                double[] P = part.pivot;
                double[] bd = Mat.solve3(B3, new double[]{t[0] - P[0], t[1] - P[1], t[2] - P[2]});
                out.shift = new double[]{bd[0] + P[0], bd[1] + P[1], bd[2] + P[2]};
                cubesOf.computeIfAbsent(part, x -> new ArrayList<>()).add(out);
            }
        }
        for (Rig.Part p : boneParts.values()) addPart(rig, p, roles);
        for (Rig.Part p : displayParts.values()) addPart(rig, p, roles);
        if (rig.parts.isEmpty()) warnings.add("no visible cubes - everything is hidden (Blockbench eye icon) or listed in hide-groups");
        if (hiddenBb > 0) warnings.add(hiddenBb + " cube(s) hidden in Blockbench were left out (pack.include-hidden-cubes: true keeps them)");
        if (hiddenByGroup > 0) warnings.add(hiddenByGroup + " cube(s) left out by hide-groups");
        if (scaledAway > 0) warnings.add(scaledAway + " cube(s) have a zero scale and are never visible");

        // ---- textures per skin
        Set<String> skins = new LinkedHashSet<>(); skins.add("default"); skins.addAll(skinFiles.keySet());
        Map<String, JsonObject> textureJsonBySkin = new LinkedHashMap<>();
        for (String skin : skins) {
            JsonObject textures = new JsonObject();
            Map<String, byte[]> overrides = skinFiles.getOrDefault(skin, Map.of());
            for (int ti = 0; ti < m.textures.size(); ti++) {
                BbModel.Texture tex = m.textures.get(ti);
                byte[] png = overrides.containsKey(tex.name) ? overrides.get(tex.name) : overrides.containsKey("*") ? overrides.get("*") : tex.png;
                if (png == null) png = placeholderPng(ti);
                String path = "item/" + modelPath + "/" + skin + "/" + tex.name;
                files.put("assets/" + ns + "/textures/" + path + ".png", png);
                animatedTexture(files, "assets/" + ns + "/textures/" + path + ".png", png, tex, warnings);
                textures.addProperty(String.valueOf(ti), ns + ":" + path);
                if (ti == 0) textures.addProperty("particle", ns + ":" + path);
            }
            if (m.textures.isEmpty()) { String path = "item/" + modelPath + "/" + skin + "/placeholder"; files.put("assets/" + ns + "/textures/" + path + ".png", placeholderPng(0)); textures.addProperty("0", ns + ":" + path); textures.addProperty("particle", ns + ":" + path); }
            textureJsonBySkin.put(skin, textures);
        }

        // ---- geometry per part
        long faces = 0;
        Set<String> clampNoted = new java.util.HashSet<>();
        for (Rig.Part part : rig.parts) {
            List<CubeOut> cubes = cubesOf.getOrDefault(part, List.of());
            double[] shift = {8 - part.pivot[0], 8 - part.pivot[1], 8 - part.pivot[2]};
            double far = 0;
            for (CubeOut co : cubes) for (int i = 0; i < 3; i++) {
                far = Math.max(far, Math.abs(Math.min(co.c.from[i], co.c.to[i]) - co.c.inflate + co.shift[i] + shift[i] - 8));
                far = Math.max(far, Math.abs(Math.max(co.c.from[i], co.c.to[i]) + co.c.inflate + co.shift[i] + shift[i] - 8));
            }
            part.k = far > 23.9 ? 23.9 / far : 1.0;
            if (part.k < 0.3) warnings.add("part '" + part.ownerName() + "' spans " + Math.round(far * 2) + " px and is written at " + Math.round(part.k * 100) + "% size to fit the Java model range (compensated in the display scale; split the group if its textures look blurry)");
            JsonArray elements = new JsonArray();
            Map<CubeOut, double[][]> boxes = new HashMap<>();
            for (CubeOut co : cubes) {
                double[] lo = new double[3], hi = new double[3];
                for (int i = 0; i < 3; i++) {
                    lo[i] = 8 + (Math.min(co.c.from[i], co.c.to[i]) - co.c.inflate + co.shift[i] + shift[i] - 8) * part.k;
                    hi[i] = 8 + (Math.max(co.c.from[i], co.c.to[i]) + co.c.inflate + co.shift[i] + shift[i] - 8) * part.k;
                }
                if (co.elemRot == null) boxes.put(co, new double[][]{lo, hi});
            }
            int partFaces = 0;
            for (CubeOut co : cubes) {
                BbModel.Cube c = co.c;
                double[] lo = new double[3], hi = new double[3];
                for (int i = 0; i < 3; i++) {
                    double a = 8 + (Math.min(c.from[i], c.to[i]) - c.inflate + co.shift[i] + shift[i] - 8) * part.k;
                    double b = 8 + (Math.max(c.from[i], c.to[i]) + c.inflate + co.shift[i] + shift[i] - 8) * part.k;
                    if ((a < -16.01 || b > 32.01) && clampNoted.add(c.name)) warnings.add("cube '" + c.name + "' still exceeds the Java model range after fitting and is cut - move the group pivot nearer to it or split the group");
                    lo[i] = Math.max(-16, Math.min(32, a)); hi[i] = Math.max(-16, Math.min(32, b));
                }
                JsonObject e = new JsonObject();
                e.add("from", arr(lo)); e.add("to", arr(hi));
                if (co.elemRot != null) {
                    JsonObject rotation = new JsonObject();
                    double[] oe = co.elemOrigin;
                    rotation.add("origin", arr(8 + (oe[0] + shift[0] - 8) * part.k, 8 + (oe[1] + shift[1] - 8) * part.k, 8 + (oe[2] + shift[2] - 8) * part.k));
                    rotation.addProperty("axis", co.elemRot[0] == 0 ? "x" : co.elemRot[0] == 1 ? "y" : "z");
                    rotation.addProperty("angle", co.elemRot[1]);
                    e.add("rotation", rotation);
                }
                Map<String, BbModel.Face> src = c.faces;
                boolean needBox = c.boxUv || c.mesh;
                if (!needBox) for (BbModel.Face f : src.values()) if (f.uv == null) needBox = true;
                if (needBox) src = boxUvFaces(c, src);
                JsonObject fj = new JsonObject();
                for (int fi = 0; fi < FACE.length; fi++) {
                    BbModel.Face bf = src.get(FACE[fi]);
                    if (bf == null) continue;
                    double[] uv = bf.uv;
                    if (uv != null && (Math.abs(uv[0] - uv[2]) < 1e-9 || Math.abs(uv[1] - uv[3]) < 1e-9)) continue;   // zero-area uv = hidden face
                    double[][] myBox = boxes.get(co);
                    if (myBox != null && faceBuried(myBox, fi, co, boxes)) continue;
                    int idx = bf.texture < 0 || bf.texture >= Math.max(1, m.textures.size()) ? 0 : bf.texture;
                    double rw = idx < m.textures.size() ? m.textures.get(idx).uvWidth : m.resW, rh = idx < m.textures.size() ? m.textures.get(idx).uvHeight : m.resH;
                    if (uv == null) uv = new double[]{0, 0, rw, rh};
                    JsonObject face = new JsonObject();
                    face.add("uv", arr(uv[0] * 16 / rw, uv[1] * 16 / rh, uv[2] * 16 / rw, uv[3] * 16 / rh));
                    face.addProperty("texture", "#" + idx);
                    if (bf.rotation != 0) face.addProperty("rotation", ((bf.rotation % 360) + 360) % 360);
                    fj.add(FACE[fi], face);
                    partFaces++;
                }
                if (fj.size() == 0) continue;
                e.add("faces", fj);
                elements.add(e);
                part.cubes++;
            }
            part.faces = partFaces;
            faces += partFaces;
            for (String skin : skins) {
                String path = modelPath + "/" + skin + "/p" + part.index;
                JsonObject model = new JsonObject();
                model.add("textures", textureJsonBySkin.get(skin).deepCopy());
                model.add("elements", elements);
                putJson(files, "assets/" + ns + "/models/item/" + path + ".json", model, warnings);
                JsonObject item = new JsonObject();
                JsonObject mm = new JsonObject(); mm.addProperty("type", "minecraft:model"); mm.addProperty("model", ns + ":item/" + path);
                item.add("model", mm);
                putJson(files, "assets/" + ns + "/items/" + path + ".json", item, warnings);
                part.itemModel.put(skin, ns + ":" + path);
            }
        }
        if (folded > 0) rig.warnings.add(folded + " cube(s) of static groups folded into the animated bone above them");
        rig.warnings.addAll(warnings);
        return new Output(rig, files, warnings, faces, rig.parts.size());
    }

    private static void addPart(Rig rig, Rig.Part p, Map<BbModel.Bone, BoneRole> roles) {
        p.index = rig.parts.size();
        p.role = p.owner == null ? BoneRole.NONE : roles.getOrDefault(p.owner, BoneRole.NONE);
        for (BbModel.Bone w = p.owner; w != null; w = w.parent) if (isDamageState(roles.getOrDefault(w, BoneRole.NONE))) p.damageState = true;
        rig.parts.add(p);
        if (p.owner != null && !rig.partsByBone.containsKey(p.owner.uuid)) rig.partsByBone.put(p.owner.uuid, p);
    }

    public static boolean isDamageState(BoneRole r) { return r == BoneRole.DAMAGE1 || r == BoneRole.DAMAGE2 || r == BoneRole.WRECK || r == BoneRole.INTACT; }

    private static final class CubeOut {
        final BbModel.Cube c; double[] shift = {0, 0, 0}; double[] elemOrigin; double[] elemRot;
        CubeOut(BbModel.Cube c) { this.c = c; this.elemOrigin = c.origin.clone(); }
    }

    private static Map<String, BbModel.Face> boxUvFaces(BbModel.Cube c, Map<String, BbModel.Face> faces) {
        double dx = Math.abs(c.to[0] - c.from[0]), dy = Math.abs(c.to[1] - c.from[1]), dz = Math.abs(c.to[2] - c.from[2]);
        double u = c.uvOffset[0], v = c.uvOffset[1];
        Map<String, double[]> uv = new LinkedHashMap<>();
        uv.put("up",    new double[]{u + dz, v, u + dz + dx, v + dz});
        uv.put("down",  new double[]{u + dz + dx, v + dz, u + dz + 2 * dx, v});
        uv.put("west",  new double[]{u, v + dz, u + dz, v + dz + dy});
        uv.put("north", new double[]{u + dz, v + dz, u + dz + dx, v + dz + dy});
        uv.put("east",  new double[]{u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy});
        uv.put("south", new double[]{u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy});
        if (c.mirror) { double[] ea = uv.get("east"), we = uv.get("west"); uv.put("east", we); uv.put("west", ea); }
        Map<String, BbModel.Face> out = new LinkedHashMap<>();
        for (String f : FACE) {
            BbModel.Face src = faces.get(f);
            BbModel.Face face = new BbModel.Face();
            face.texture = src == null ? 0 : src.texture;
            face.rotation = src == null ? 0 : src.rotation;
            face.uv = src != null && src.uv != null ? src.uv : uv.get(f);
            out.put(f, face);
        }
        return out;
    }

    private static boolean faceBuried(double[][] box, int fi, CubeOut self, Map<CubeOut, double[][]> boxes) {
        int axis = fi <= 1 ? 2 : fi <= 3 ? 0 : 1;
        boolean positive = fi == 1 || fi == 2 || fi == 4;
        double plane = positive ? box[1][axis] : box[0][axis];
        double eps = 1e-4;
        for (var en : boxes.entrySet()) {
            if (en.getKey() == self) continue;
            double[][] o = en.getValue();
            boolean covers = true;
            for (int a = 0; a < 3 && covers; a++) { if (a == axis) continue; if (o[0][a] - eps > box[0][a] || o[1][a] + eps < box[1][a]) covers = false; }
            if (!covers) continue;
            if (plane < o[0][axis] - eps || plane > o[1][axis] + eps) continue;
            boolean beyond = positive ? o[1][axis] > plane + eps : o[0][axis] < plane - eps;
            if (beyond) return true;
        }
        return false;
    }

    private static void animatedTexture(Map<String, byte[]> files, String packPath, byte[] png, BbModel.Texture tex, List<String> warnings) {
        BufferedImage img;
        try { img = ImageIO.read(new ByteArrayInputStream(png)); } catch (Exception e) { return; }
        if (img == null || img.getHeight() <= img.getWidth() || img.getHeight() % img.getWidth() != 0) return;
        JsonObject anim = new JsonObject();
        anim.addProperty("frametime", tex.frameTime);
        if (tex.interpolate) anim.addProperty("interpolate", true);
        JsonObject root = new JsonObject(); root.add("animation", anim);
        files.put(packPath + ".mcmeta", GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] placeholderPng(int seed) {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            int base = switch (seed % 4) { case 0 -> 0x6B6F5A; case 1 -> 0x4F5A66; case 2 -> 0x7A6B4E; default -> 0x55605A; };
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) { int v = ((x / 4 + y / 4) % 2 == 0) ? base : darken(base, 0.85); img.setRGB(x, y, 0xFF000000 | v); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }
    private static int darken(int rgb, double f) { int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f); return (r << 16) | (g << 8) | b; }

    private static void putJson(Map<String, byte[]> files, String path, JsonObject o, List<String> warnings) {
        String text = GSON.toJson(o);
        if (text.contains("NaN") || text.contains("Infinity")) { text = text.replace("-Infinity", "0").replace("Infinity", "0").replace("NaN", "0"); warnings.add(path + ": non-finite values replaced by 0"); }
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    static JsonArray arr(double... v) { JsonArray a = new JsonArray(); for (double d : v) a.add(Math.round(d * 1000.0) / 1000.0); return a; }

    public static Pattern glob(String h) { return Pattern.compile(("\\Q" + h.trim().toLowerCase(Locale.ROOT) + "\\E").replace("*", "\\E.*\\Q")); }

    /** A resource-location-safe path for a model name. */
    public static String path(String name) { String p = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"); return p.isEmpty() ? "model" : p; }


}
