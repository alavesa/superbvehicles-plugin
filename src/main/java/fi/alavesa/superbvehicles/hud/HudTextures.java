package fi.alavesa.superbvehicles.hud;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Original, procedurally drawn HUD art (no external assets). Three skins differ in face, rim and accent colours.
 * Every image has one 1/255-alpha pixel in its top-right corner so the client measures the glyph at its full width
 * (bitmap glyph widths are taken from the right-most non-transparent column); the shader discards it.
 * Image sizes are 4x the GUI size of the element (HudLayout) for crisp scaling.
 */
public final class HudTextures {
    private HudTextures() { }

    public record Skin(String name, int face, int rim, int ticks, int accent, int text) { }

    public static Skin skin(String name) {
        return switch (name) {
            case "civilian" -> new Skin("civilian", 0xF2F2EE, 0x2B2F36, 0x22262C, 0xD9472B, 0x16181C);
            case "aircraft" -> new Skin("aircraft", 0x101418, 0x5A6370, 0xE8ECEF, 0xF0B429, 0xE8ECEF);
            default -> new Skin("military", 0x1E241C, 0x56604A, 0xD8E0C8, 0xF0B429, 0xD8E0C8);
        };
    }

    /** glyph name -> png for one skin (names as in HudLayout.Element.glyph, digits "digit0".."digit9", "unit_kmh", "unit_mph"). */
    public static Map<String, byte[]> all(Skin s) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("dial", dial(s, false));
        out.put("vsi_face", dial(s, true));
        out.put("needle", needle(s));
        out.put("horizon", horizon(s));
        out.put("pitch_bar", pitchBar(s));
        out.put("fuel_bar_face", fuelBarFace(s));
        out.put("fuel_bar_fill", fuelBarFill());
        out.put("fuel_arc_face", fuelArcFace(s));
        out.put("fuel_arc_fill", fuelArcFill());
        out.put("warn", warn());
        out.put("engine_off", engineOff(s));
        for (int d = 0; d <= 9; d++) out.put("digit" + d, digit(s, d));
        out.put("unit_kmh", label(s, "km/h"));
        out.put("unit_mph", label(s, "mph"));
        return out;
    }

    private static BufferedImage canvas(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
    private static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }
    private static Color c(int rgb) { return new Color(rgb); }
    private static Color c(int rgb, int a) { return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a); }

    static byte[] png(BufferedImage img) throws IOException {
        int x = img.getWidth() - 1;
        if (((img.getRGB(x, 0) >>> 24) & 255) == 0) img.setRGB(x, 0, 0x01000000);   // full-width marker pixel
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Speed dial (or vertical-speed face): 270 degree scale from bottom-left clockwise to bottom-right. */
    static byte[] dial(Skin s, boolean vsi) throws IOException {
        int n = 256; BufferedImage img = canvas(n, n); Graphics2D g = g(img);
        g.setColor(c(s.rim())); g.fillOval(4, 4, n - 8, n - 8);
        g.setColor(c(s.face())); g.fillOval(16, 16, n - 32, n - 32);
        double cx = n / 2.0, cy = n / 2.0;
        int major = vsi ? 8 : 10;
        for (int i = 0; i <= major * 5; i++) {
            double t = (double) i / (major * 5);
            double ang = Math.toRadians(vsi ? -90 + t * 180 - 90 : -135 + t * 270);   // 0 = up, clockwise
            boolean big = i % 5 == 0;
            double r1 = n / 2.0 - 22, r2 = r1 - (big ? 22 : 11);
            boolean red = !vsi && t > 0.85;
            g.setColor(red ? c(s.accent()) : c(s.ticks()));
            g.setStroke(new BasicStroke(big ? 5f : 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) (cx + Math.sin(ang) * r1), (int) (cy - Math.cos(ang) * r1), (int) (cx + Math.sin(ang) * r2), (int) (cy - Math.cos(ang) * r2));
        }
        if (vsi) { g.setColor(c(s.ticks())); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26)); center(g, "VS", cx, cy + 70); center(g, "+", cx - 62, cy - 52); center(g, "-", cx - 62, cy + 70); }
        else { g.setColor(c(s.accent(), 160)); g.setStroke(new BasicStroke(6f)); g.draw(new Arc2D.Double(34, 34, n - 68, n - 68, -45, 270 * 0.15, Arc2D.OPEN)); }   // redline zone: last 15% of the scale
        g.setColor(c(s.rim())); g.fillOval((int) cx - 12, (int) cy - 12, 24, 24);
        g.dispose();
        return png(img);
    }

    /** Needle pointing up; pivot at (12, 108) of a 24x120 image = HudLayout pivot (3, 27) at 4x. */
    static byte[] needle(Skin s) throws IOException {
        BufferedImage img = canvas(24, 120); Graphics2D g = g(img);
        g.setColor(c(s.accent()));
        g.fillPolygon(new int[]{12, 17, 7}, new int[]{2, 108, 108}, 3);
        g.setColor(c(s.accent(), 200)); g.fillOval(4, 100, 16, 16);
        g.dispose();
        return png(img);
    }

    /** Artificial horizon disc: sky over ground, rotated by the roll angle. */
    static byte[] horizon(Skin s) throws IOException {
        int n = 256; BufferedImage img = canvas(n, n); Graphics2D g = g(img);
        g.setClip(new java.awt.geom.Ellipse2D.Double(8, 8, n - 16, n - 16));
        g.setColor(c(0x3D7FC4)); g.fillRect(0, 0, n, n / 2);
        g.setColor(c(0x6B4A2B)); g.fillRect(0, n / 2, n, n / 2);
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(4f)); g.drawLine(0, n / 2, n, n / 2);
        for (int i = 1; i <= 3; i++) { int w = 60 - i * 10; g.drawLine(n / 2 - w, n / 2 - i * 28, n / 2 + w, n / 2 - i * 28); g.drawLine(n / 2 - w, n / 2 + i * 28, n / 2 + w, n / 2 + i * 28); }
        g.setClip(null);
        g.setColor(c(s.rim())); g.setStroke(new BasicStroke(10f)); g.drawOval(8, 8, n - 16, n - 16);
        g.dispose();
        return png(img);
    }

    /** Aircraft symbol bar that moves with the pitch. */
    static byte[] pitchBar(Skin s) throws IOException {
        BufferedImage img = canvas(224, 24); Graphics2D g = g(img);
        g.setColor(c(s.accent())); g.fillRoundRect(0, 8, 80, 8, 6, 6); g.fillRoundRect(144, 8, 80, 8, 6, 6); g.fillOval(100, 0, 24, 24);
        g.dispose();
        return png(img);
    }

    static byte[] fuelBarFace(Skin s) throws IOException {
        BufferedImage img = canvas(56, 256); Graphics2D g = g(img);
        g.setColor(c(s.rim())); g.fillRoundRect(0, 0, 56, 256, 16, 16);
        g.setColor(c(s.face() == 0xF2F2EE ? 0xD4D4CE : 0x0E120D)); g.fillRoundRect(8, 8, 40, 240, 10, 10);
        g.setColor(c(s.ticks())); for (int i = 1; i < 4; i++) g.fillRect(40, 8 + i * 60, 8, 3);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18)); g.setColor(c(s.ticks()));
        g.dispose();
        return png(img);
    }

    /** White fill; the shader cuts it to the fuel level and tints it green / yellow / red. */
    static byte[] fuelBarFill() throws IOException {
        BufferedImage img = canvas(56, 256); Graphics2D g = g(img);
        g.setColor(new Color(240, 240, 240)); g.fillRoundRect(12, 12, 32, 232, 8, 8);
        g.setColor(new Color(255, 255, 255, 120)); g.fillRect(14, 12, 6, 232);
        g.dispose();
        return png(img);
    }

    static byte[] fuelArcFace(Skin s) throws IOException {
        int n = 192; BufferedImage img = canvas(n, n); Graphics2D g = g(img);
        g.setColor(c(s.rim())); g.setStroke(new BasicStroke(30f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(24, 24, n - 48, n - 48, -45, 270, Arc2D.OPEN));
        g.setColor(c(s.ticks())); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34)); center(g, "F", n / 2.0, n / 2.0 + 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22)); center(g, "E", 40, n - 22); center(g, "F", n - 40, n - 22);
        g.dispose();
        return png(img);
    }

    static byte[] fuelArcFill() throws IOException {
        int n = 192; BufferedImage img = canvas(n, n); Graphics2D g = g(img);
        g.setColor(new Color(240, 240, 240)); g.setStroke(new BasicStroke(18f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(24, 24, n - 48, n - 48, -45, 270, Arc2D.OPEN));
        g.dispose();
        return png(img);
    }

    static byte[] warn() throws IOException {
        BufferedImage img = canvas(56, 56); Graphics2D g = g(img);
        g.setColor(new Color(0xF0B429)); g.fillPolygon(new int[]{28, 54, 2}, new int[]{3, 52, 52}, 3);
        g.setColor(new Color(0x16181C)); g.fillRect(25, 18, 6, 20); g.fillOval(24, 42, 8, 8);
        g.dispose();
        return png(img);
    }

    static byte[] engineOff(Skin s) throws IOException {
        BufferedImage img = canvas(56, 56); Graphics2D g = g(img);
        g.setColor(new Color(0xD9472B)); g.setStroke(new BasicStroke(5f));
        g.drawRect(8, 16, 36, 26); g.fillRect(16, 10, 18, 6); g.fillRect(44, 22, 8, 12);
        g.drawLine(4, 52, 52, 4);
        g.dispose();
        return png(img);
    }

    static byte[] digit(Skin s, int d) throws IOException {
        BufferedImage img = canvas(32, 44); Graphics2D g = g(img);
        g.setColor(c(s.text())); g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 44));
        center(g, String.valueOf(d), 16, 38);
        g.dispose();
        return png(img);
    }

    static byte[] label(Skin s, String text) throws IOException {
        BufferedImage img = canvas(96, 28); Graphics2D g = g(img);
        g.setColor(c(s.text())); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        center(g, text, 48, 22);
        g.dispose();
        return png(img);
    }

    private static void center(Graphics2D g, String s, double cx, double baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseline);
    }
}
