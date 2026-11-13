package fi.alavesa.superbvehicles.hud;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Where every HUD element sits and how the shader treats it. Two instrument profiles, each with its own anchor,
 * offset, scale and opacity from config.yml (hud.profiles.ground / hud.profiles.aircraft):
 * <ul>
 *   <li>GROUND (wheeled, tracked, boat): speed dial + needle + 3 digits + unit, fuel bar or fuel arc, warning and
 *       engine-off icons;</li>
 *   <li>AIRCRAFT (helicopter, plane): speed dial, vertical-speed indicator, artificial horizon (roll) with pitch bar,
 *       4-digit altitude, fuel arc, icons.</li>
 * </ul>
 * Rects are in GUI pixels relative to the profile anchor (negative = left/up of a right/bottom anchor); they scale with
 * the GUI scale like the vanilla HUD. Element values: ROTATE kinds take an angle (HudEncoding.angle), FILL and FACE
 * kinds a fraction, TRANSLATE_Y a fraction where 0.5 = centred.
 */
public final class HudLayout {
    public enum Kind { STATIC, ROTATE, ROTATE_JITTER, TRANSLATE_Y, FILL_V, FILL_ARC, BLINK, FACE_DARK }
    public enum Profile { GROUND, AIRCRAFT }

    /** One element: id (= colour-encoded), profile, rect {x, y, w, h}, pivot {x, y} inside the rect, kind, glyph name. */
    public record Element(int id, Profile profile, double x, double y, double w, double h, double px, double py, Kind kind, String glyph) { }

    // ---- ground profile
    public static final int DIAL = 0, NEEDLE = 1, NEEDLE_RED = 2, DIGIT0 = 3, UNIT = 6, FUEL_BAR_FACE = 7, FUEL_BAR_FILL = 8,
        FUEL_ARC_FACE = 9, FUEL_ARC_FILL = 10, WARN = 11, ENGINE_OFF = 12;
    // ---- aircraft profile
    public static final int A_DIAL = 13, A_NEEDLE = 14, A_NEEDLE_RED = 15, A_DIGIT0 = 16, A_UNIT = 19, VSI_FACE = 20, VSI_NEEDLE = 21,
        HORIZON = 22, PITCH_BAR = 23, ALT_DIGIT0 = 24, A_FUEL_ARC_FACE = 28, A_FUEL_ARC_FILL = 29, A_WARN = 30, A_ENGINE_OFF = 31;

    public static final Element[] ELEMENTS = new Element[32];
    static {
        put(new Element(DIAL, Profile.GROUND, -66, -66, 64, 64, 32, 32, Kind.STATIC, "dial"));
        put(new Element(NEEDLE, Profile.GROUND, -37, -61, 6, 30, 3, 27, Kind.ROTATE, "needle"));
        put(new Element(NEEDLE_RED, Profile.GROUND, -37, -61, 6, 30, 3, 27, Kind.ROTATE_JITTER, "needle"));
        for (int i = 0; i < 3; i++) put(new Element(DIGIT0 + i, Profile.GROUND, -46 + i * 8, -29, 8, 11, 0, 0, Kind.STATIC, "digit"));
        put(new Element(UNIT, Profile.GROUND, -46, -16, 24, 7, 0, 0, Kind.STATIC, "unit"));
        put(new Element(FUEL_BAR_FACE, Profile.GROUND, -84, -66, 14, 64, 0, 0, Kind.FACE_DARK, "fuel_bar_face"));
        put(new Element(FUEL_BAR_FILL, Profile.GROUND, -84, -66, 14, 64, 0, 0, Kind.FILL_V, "fuel_bar_fill"));
        put(new Element(FUEL_ARC_FACE, Profile.GROUND, -126, -58, 48, 48, 0, 0, Kind.FACE_DARK, "fuel_arc_face"));
        put(new Element(FUEL_ARC_FILL, Profile.GROUND, -126, -58, 48, 48, 0, 0, Kind.FILL_ARC, "fuel_arc_fill"));
        put(new Element(WARN, Profile.GROUND, -84, -82, 14, 14, 0, 0, Kind.BLINK, "warn"));
        put(new Element(ENGINE_OFF, Profile.GROUND, -100, -82, 14, 14, 0, 0, Kind.STATIC, "engine_off"));

        put(new Element(A_DIAL, Profile.AIRCRAFT, -66, -66, 64, 64, 32, 32, Kind.STATIC, "dial"));
        put(new Element(A_NEEDLE, Profile.AIRCRAFT, -37, -61, 6, 30, 3, 27, Kind.ROTATE, "needle"));
        put(new Element(A_NEEDLE_RED, Profile.AIRCRAFT, -37, -61, 6, 30, 3, 27, Kind.ROTATE_JITTER, "needle"));
        for (int i = 0; i < 3; i++) put(new Element(A_DIGIT0 + i, Profile.AIRCRAFT, -46 + i * 8, -29, 8, 11, 0, 0, Kind.STATIC, "digit"));
        put(new Element(A_UNIT, Profile.AIRCRAFT, -46, -16, 24, 7, 0, 0, Kind.STATIC, "unit"));
        put(new Element(VSI_FACE, Profile.AIRCRAFT, -134, -66, 64, 64, 32, 32, Kind.STATIC, "vsi_face"));
        put(new Element(VSI_NEEDLE, Profile.AIRCRAFT, -105, -61, 6, 30, 3, 27, Kind.ROTATE, "needle"));
        put(new Element(HORIZON, Profile.AIRCRAFT, -202, -66, 64, 64, 32, 32, Kind.ROTATE, "horizon"));
        put(new Element(PITCH_BAR, Profile.AIRCRAFT, -198, -37, 56, 6, 28, 3, Kind.TRANSLATE_Y, "pitch_bar"));
        for (int i = 0; i < 4; i++) put(new Element(ALT_DIGIT0 + i, Profile.AIRCRAFT, -186 + i * 8, -80, 8, 11, 0, 0, Kind.STATIC, "digit"));
        put(new Element(A_FUEL_ARC_FACE, Profile.AIRCRAFT, -256, -58, 48, 48, 0, 0, Kind.FACE_DARK, "fuel_arc_face"));
        put(new Element(A_FUEL_ARC_FILL, Profile.AIRCRAFT, -256, -58, 48, 48, 0, 0, Kind.FILL_ARC, "fuel_arc_fill"));
        put(new Element(A_WARN, Profile.AIRCRAFT, -256, -74, 14, 14, 0, 0, Kind.BLINK, "warn"));
        put(new Element(A_ENGINE_OFF, Profile.AIRCRAFT, -240, -74, 14, 14, 0, 0, Kind.STATIC, "engine_off"));
        for (int i = 0; i < 32; i++) if (ELEMENTS[i] == null) throw new IllegalStateException("element " + i + " undefined");
    }
    private static void put(Element e) { if (ELEMENTS[e.id()] != null) throw new IllegalStateException("duplicate element " + e.id()); ELEMENTS[e.id()] = e; }

    /** Pitch bar travel (GUI px) for a value of 0 or 1. */
    public static final double PITCH_RANGE = 24;

    /** Placement of one profile on screen. anchor: bottom-right | bottom-left | top-right | top-left | bottom-center. */
    public record Placement(String anchor, double offsetX, double offsetY, double scale, double opacity) {
        double ax() { return anchor.endsWith("right") ? 1 : anchor.endsWith("left") ? 0 : 0.5; }
        double ay() { return anchor.startsWith("top") ? 0 : 1; }
        static Placement of(ConfigurationSection s, double defScale) {
            if (s == null) return new Placement("bottom-right", -8, -8, defScale, 0.95);
            java.util.List<Double> off = s.getDoubleList("offset");
            return new Placement(s.getString("anchor", "bottom-right").toLowerCase(Locale.ROOT), off.size() == 2 ? off.get(0) : -8, off.size() == 2 ? off.get(1) : -8,
                Math.max(0.25, Math.min(4, s.getDouble("scale", defScale))), Math.max(0.05, Math.min(1, s.getDouble("opacity", 0.95))));
        }
    }

    public final Placement ground, aircraft;
    public HudLayout(Placement ground, Placement aircraft) { this.ground = ground; this.aircraft = aircraft; }
    public static HudLayout of(ConfigurationSection hud) {
        ConfigurationSection pr = hud == null ? null : hud.getConfigurationSection("profiles");
        double scale = hud == null ? 1 : hud.getDouble("scale", 1);
        return new HudLayout(Placement.of(pr == null ? null : pr.getConfigurationSection("ground"), scale), Placement.of(pr == null ? null : pr.getConfigurationSection("aircraft"), scale));
    }

    /** GLSL constants and helpers shared by text.vsh and text.fsh (generated into both). */
    public String glsl() {
        StringBuilder kinds = new StringBuilder(), rects = new StringBuilder(), pivots = new StringBuilder(), profiles = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            Element e = ELEMENTS[i];
            String sep = i < 31 ? ", " : "";
            kinds.append(e.kind().ordinal()).append(sep);
            rects.append("vec4(").append(f(e.x())).append(", ").append(f(e.y())).append(", ").append(f(e.w())).append(", ").append(f(e.h())).append(")").append(sep);
            pivots.append("vec2(").append(f(e.px())).append(", ").append(f(e.py())).append(")").append(sep);
            profiles.append(e.profile().ordinal()).append(sep);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// ---- SuperbVehicles HUD layout (generated from config.yml hud.profiles; do not edit, rebuild the pack)\n");
        for (Kind k : Kind.values()) sb.append("#define SBV_").append(k.name()).append(' ').append(k.ordinal()).append('\n');
        sb.append("const int SBV_KIND[32] = int[32](").append(kinds).append(");\n");
        sb.append("const vec4 SBV_RECT[32] = vec4[32](").append(rects).append(");\n");
        sb.append("const vec2 SBV_PIVOT[32] = vec2[32](").append(pivots).append(");\n");
        sb.append("const int SBV_PROFILE[32] = int[32](").append(profiles).append(");\n");
        sb.append("const vec2 SBV_ANCHOR[2] = vec2[2](vec2(").append(f(ground.ax())).append(", ").append(f(ground.ay())).append("), vec2(").append(f(aircraft.ax())).append(", ").append(f(aircraft.ay())).append("));\n");
        sb.append("const vec2 SBV_OFFSET[2] = vec2[2](vec2(").append(f(ground.offsetX())).append(", ").append(f(ground.offsetY())).append("), vec2(").append(f(aircraft.offsetX())).append(", ").append(f(aircraft.offsetY())).append("));\n");
        sb.append("const float SBV_SCALE[2] = float[2](").append(f(ground.scale())).append(", ").append(f(aircraft.scale())).append(");\n");
        sb.append("const float SBV_OPACITY[2] = float[2](").append(f(ground.opacity())).append(", ").append(f(aircraft.opacity())).append(");\n");
        sb.append("const float SBV_PITCH_RANGE = ").append(f(PITCH_RANGE)).append(";\n");
        return sb.toString();
    }

    static String f(double v) { String s = String.format(Locale.ROOT, "%.4f", v); return s.contains(".") ? s : s + ".0"; }
}
