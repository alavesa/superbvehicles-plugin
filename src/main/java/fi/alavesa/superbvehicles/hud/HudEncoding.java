package fi.alavesa.superbvehicles.hud;

/**
 * The "data in text colour" encoding of the shader HUD. Every HUD glyph is sent with a text colour
 * <pre>
 *   R = 0xFD (253)                 marker
 *   G = 0b101i_iiii (0xA0..0xBF)    marker in the top 3 bits, element id 0..31 in the low 5 bits
 *   B = value 0..255               element value (fill fraction, needle angle, offset - see HudLayout)
 * </pre>
 * Only colours #FDA000..#FDBFFF are HUD data (8192 of 16.7 million). The shader leaves every other text untouched;
 * the plugin's own messages and the 16 named colours are checked against the range at every pack build.
 */
public final class HudEncoding {
    private HudEncoding() { }
    public static final int MARKER_R = 0xFD;
    public static final int MARKER_G_HIGH = 0xA0;   // top 3 bits 101
    public static final int MAX_ID = 31;

    /** RGB (0xRRGGBB) carrying element id and value. */
    public static int encode(int id, int value) {
        if (id < 0 || id > MAX_ID) throw new IllegalArgumentException("element id " + id);
        int v = Math.max(0, Math.min(255, value));
        return (MARKER_R << 16) | ((MARKER_G_HIGH | id) << 8) | v;
    }

    /** Fraction 0..1 to a byte. */
    public static int fraction(double f) { return (int) Math.round(Math.max(0, Math.min(1, f)) * 255); }

    /** Angle in degrees (-180..180) to a byte: angle = value/255 * 360 - 180. */
    public static int angle(double degrees) {
        double d = ((degrees + 180) % 360 + 360) % 360;
        return (int) Math.round(d / 360.0 * 255);
    }

    public static boolean isMarker(int rgb) { int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255; return r == MARKER_R && (g & 0xE0) == MARKER_G_HIGH; }
    public static int idOf(int rgb) { return ((rgb >> 8) & 255) & 0x1F; }
    public static int valueOf(int rgb) { return rgb & 255; }
    public static double angleOf(int rgb) { return valueOf(rgb) / 255.0 * 360.0 - 180.0; }
}
