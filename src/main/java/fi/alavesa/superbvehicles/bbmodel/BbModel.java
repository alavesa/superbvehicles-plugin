package fi.alavesa.superbvehicles.bbmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Blockbench file: textures, cubes, the bone (group) tree, locators and animations, all in Blockbench
 * model space (pixels, 16 px = 1 block, group rest rotations ZYX). Produced by {@link BbParser}, consumed by the
 * pack builder (geometry) and the renderer (bone matrices at runtime).
 */
public final class BbModel {
    public String name = "model";
    public int formatMajor = 4;
    public double resW = 16, resH = 16;
    public final List<Texture> textures = new ArrayList<>();
    public final Map<String, Cube> cubes = new LinkedHashMap<>();       // uuid -> cube (outliner order)
    public final Map<String, Bone> bones = new LinkedHashMap<>();       // uuid -> bone
    public final List<Bone> roots = new ArrayList<>();                  // top-level bones
    public final Map<String, Locator> locators = new LinkedHashMap<>(); // lower-case name -> locator
    public final Map<String, Animation> animations = new LinkedHashMap<>(); // name -> animation
    public final List<String> warnings = new ArrayList<>();
    /** Animation channel signs relative to the rest pose (Blockbench 4.x keyframes are in the Bedrock convention). */
    public double[] animRotSign = {1, 1, 1}, animPosSign = {1, 1, 1};
    public int meshCount, hiddenCubes;

    public static final class Texture {
        public String name; public byte[] png; public double uvWidth, uvHeight; public int frameTime = 1; public boolean interpolate;
        public int width, height;   // decoded image size (0 if undecodable)
    }

    public static final class Face {
        public double[] uv;        // null = box-uv / whole texture
        public int texture = -1;   // texture index, -1 = none/missing
        public int rotation;
    }

    public static final class Cube {
        public String uuid, name = "";
        public double[] from = {0, 0, 0}, to = {0, 0, 0}, origin = {0, 0, 0}, rotation = {0, 0, 0};
        public double inflate;
        public final Map<String, Face> faces = new LinkedHashMap<>();   // north south east west up down
        public boolean boxUv, mirror, mesh, hidden;
        public double[] uvOffset = {0, 0};
        public Bone bone;
        public double[] center() { return new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2}; }
    }

    public static final class Bone {
        public String uuid, name = "";
        public double[] origin = {0, 0, 0}, rotation = {0, 0, 0};
        public Bone parent;
        public final List<Bone> children = new ArrayList<>();
        public final List<Cube> cubes = new ArrayList<>();
        public final List<Locator> locators = new ArrayList<>();
        public boolean hidden;
        public int depth() { int d = 0; for (Bone b = parent; b != null; b = b.parent) d++; return d; }
        public boolean isDescendantOf(Bone other) { for (Bone b = parent; b != null; b = b.parent) if (b == other) return true; return false; }
        /** Every cube of this bone and its descendants. */
        public int cubeCount() { int n = cubes.size(); for (Bone c : children) n += c.cubeCount(); return n; }
    }

    public static final class Locator {
        public String uuid, name = "";
        public double[] position = {0, 0, 0}, rotation = {0, 0, 0};
        public Bone bone;
    }

    public static final class Animation {
        public String name = "anim";
        public double length;
        public boolean loop, hold;
        public final Map<String, Channels> channels = new LinkedHashMap<>();   // bone uuid -> tracks
        public final List<SoundKey> sounds = new ArrayList<>();
        public boolean animates(String boneUuid) { return channels.containsKey(boneUuid); }
    }

    public static final class Channels {
        public Track rot = Track.empty(0), pos = Track.empty(0), scale = Track.empty(1);
    }

    public record SoundKey(double time, String effect) { }

    public Bone bone(String name) {
        if (name == null) return null;
        for (Bone b : bones.values()) if (b.name.equalsIgnoreCase(name)) return b;
        return null;
    }

    public Locator locator(String name) { return name == null ? null : locators.get(name.toLowerCase(java.util.Locale.ROOT)); }

    /** Bones whose name matches a lower-case prefix (e.g. "wheel_"). */
    public List<Bone> bonesStartingWith(String prefix) {
        List<Bone> out = new ArrayList<>();
        for (Bone b : bones.values()) if (b.name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) out.add(b);
        return out;
    }

    /** Model bounds in px over all cubes: {minX,minY,minZ,maxX,maxY,maxZ}; a unit box when there are none. */
    public double[] bounds() {
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (Cube c : cubes.values()) for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], Math.min(c.from[i], c.to[i]) - c.inflate); mx[i] = Math.max(mx[i], Math.max(c.from[i], c.to[i]) + c.inflate); }
        if (mn[0] > mx[0]) return new double[]{-8, 0, -8, 8, 16, 8};
        return new double[]{mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]};
    }
}
