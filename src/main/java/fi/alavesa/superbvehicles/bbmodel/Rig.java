package fi.alavesa.superbvehicles.bbmodel;

import fi.alavesa.superbvehicles.pack.Mat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the renderer needs of a converted model: its parts (one item model each, following one animatable bone),
 * the bone tree, and the per-tick bone matrices for a pose = rest + animation clips + procedural deltas.
 * Built by the pack builder, kept in memory per vehicle type; nothing of it is in the pack itself.
 */
public final class Rig {
    /** One item display: the cubes it draws were written in part space with {@code pivot} at the model centre (8,8,8),
     *  shrunk by {@code k} when they would leave the Java range. World (px) = bone matrix * extra, see {@link #matrices}. */
    public static final class Part {
        public int index;
        public BbModel.Bone owner;                     // null = the virtual root (static geometry, cubes outside any animatable bone)
        public double[] pivot = {8, 8, 8};
        public double k = 1;
        public double[][] extra = Mat.identity();      // a display part's shared rotation about the pivot (identity for bone parts)
        public final Map<String, String> itemModel = new LinkedHashMap<>();   // skin -> item model id (namespace:path)
        public int cubes, faces;
        public BoneRole role = BoneRole.NONE;          // of the owner (or NONE)
        public boolean damageState;                    // dmg/wreck/intact bones: shown or hidden by health
        public String ownerName() { return owner == null ? "root" : owner.name; }
    }

    /** Per-bone deltas for one pose: rotation (deg, Blockbench ZYX, added to rest), position (px) and scale. */
    public static final class BoneDelta {
        public final double[] rot = {0, 0, 0}, pos = {0, 0, 0}, scale = {1, 1, 1};
        public boolean hidden;
        public void reset() { rot[0] = rot[1] = rot[2] = 0; pos[0] = pos[1] = pos[2] = 0; scale[0] = scale[1] = scale[2] = 1; hidden = false; }
        public boolean identity() { return !hidden && rot[0] == 0 && rot[1] == 0 && rot[2] == 0 && pos[0] == 0 && pos[1] == 0 && pos[2] == 0 && scale[0] == 1 && scale[1] == 1 && scale[2] == 1; }
    }

    public final BbModel model;
    public final List<Part> parts = new ArrayList<>();
    public final Set<String> animatable = new LinkedHashSet<>();   // bone uuids that are part owners
    public final Map<String, Part> partsByBone = new HashMap<>();  // owner uuid -> first (bone) part
    public final List<String> warnings = new ArrayList<>();
    /** Rest-pose world matrices of every bone (no deltas) - the static baseline. */
    private final Map<String, double[][]> restWorld = new HashMap<>();

    public Rig(BbModel model) { this.model = model; }

    public void sealRest() {
        restWorld.clear();
        Map<String, double[][]> cache = new HashMap<>();
        for (BbModel.Bone b : model.bones.values()) restWorld.put(b.uuid, world(b, null, cache));
    }

    public double[][] restWorld(BbModel.Bone b) { return b == null ? Mat.identity() : restWorld.getOrDefault(b.uuid, Mat.identity()); }

    /** World matrix (px) of a bone with the given deltas (null = rest). */
    public double[][] world(BbModel.Bone b, Map<String, BoneDelta> deltas, Map<String, double[][]> cache) {
        if (b == null) return Mat.identity();
        double[][] w = cache.get(b.uuid);
        if (w != null) return w;
        double[] r = b.rotation.clone(), p = {0, 0, 0}, s = {1, 1, 1};
        BoneDelta d = deltas == null ? null : deltas.get(b.uuid);
        if (d != null) { for (int i = 0; i < 3; i++) { r[i] += d.rot[i]; p[i] += d.pos[i]; s[i] *= d.scale[i]; } }
        double[][] local = Mat.mul(Mat.mul(Mat.mul(Mat.translate(b.origin[0] + p[0], b.origin[1] + p[1], b.origin[2] + p[2]), Mat.rotZYX(r)), Mat.scale4(s)), Mat.translate(-b.origin[0], -b.origin[1], -b.origin[2]));
        w = b.parent == null ? local : Mat.mul(world(b.parent, deltas, cache), local);
        cache.put(b.uuid, w);
        return w;
    }

    /** Is this bone, or any ancestor, hidden by the deltas (damage states, scale 0)? */
    public boolean hidden(BbModel.Bone b, Map<String, BoneDelta> deltas) {
        if (deltas == null) return false;
        for (BbModel.Bone w = b; w != null; w = w.parent) { BoneDelta d = deltas.get(w.uuid); if (d != null && d.hidden) return true; }
        return false;
    }

    /** Every part's world matrix (px, 4x4) for this pose; null = hidden. */
    public double[][][] matrices(Map<String, BoneDelta> deltas) {
        Map<String, double[][]> cache = new HashMap<>();
        double[][][] out = new double[parts.size()][][];
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.owner != null && hidden(part.owner, deltas)) continue;
            double[][] M = Mat.mul(part.owner == null ? Mat.identity() : world(part.owner, deltas, cache), part.extra);
            double[] sc = Mat.columnScale(Mat.upper3(M));
            if (sc[0] < 0.01 || sc[1] < 0.01 || sc[2] < 0.01 || !Mat.finite(M)) continue;
            out[i] = M;
        }
        return out;
    }

    /** World position (px) of a locator in this pose. */
    public double[] locatorPosition(BbModel.Locator l, Map<String, BoneDelta> deltas, Map<String, double[][]> cache) {
        if (l == null) return new double[]{0, 0, 0};
        return Mat.apply(world(l.bone, deltas, cache), l.position);
    }

    /** World (px) of a bone's pivot in this pose. */
    public double[] bonePivot(BbModel.Bone b, Map<String, BoneDelta> deltas, Map<String, double[][]> cache) {
        return Mat.apply(world(b, deltas, cache), b.origin);
    }

    /** Forward (-Z) direction of a bone in this pose, normalised, in model px space. */
    public double[] boneForward(BbModel.Bone b, Map<String, BoneDelta> deltas, Map<String, double[][]> cache) {
        double[] f = Mat.apply3(Mat.upper3(world(b, deltas, cache)), new double[]{0, 0, -1});
        double n = Math.sqrt(f[0]*f[0] + f[1]*f[1] + f[2]*f[2]);
        return n < 1e-9 ? new double[]{0, 0, -1} : new double[]{f[0]/n, f[1]/n, f[2]/n};
    }
}
