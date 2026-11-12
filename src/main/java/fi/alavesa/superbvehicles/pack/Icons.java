package fi.alavesa.superbvehicles.pack;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Flat 64x64 label cards used as the GUI icon of spawn items (and the fuel / ammo / repair items). */
public final class Icons {
    private Icons() { }

    public static byte[] label(String displayName, String fallback, int accent) throws IOException {
        String text = displayName.replaceAll("<[^>]*>", "").replaceAll("&[0-9a-fk-or]", "").trim().toUpperCase(Locale.ROOT);
        if (text.isEmpty()) text = fallback.toUpperCase(Locale.ROOT);
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(28, 30, 36, 235));
        g.fillRoundRect(2, 2, size - 4, size - 4, 12, 12);
        g.setColor(new Color(90, 96, 110, 255));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(2, 2, size - 4, size - 4, 12, 12);
        g.setColor(new Color(accent));
        g.fillRoundRect(10, size - 14, size - 20, 4, 3, 3);
        String[] lines = text.length() > 9 && text.contains(" ") ? text.split(" ", 2) : new String[]{text};
        g.setColor(Color.WHITE);
        float fs = lines.length == 1 ? 22f : 16f;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int) fs);
        for (int i = 0; i < 12; i++) {
            font = font.deriveFont(fs);
            FontMetrics fm = g.getFontMetrics(font);
            int w = 0; for (String l : lines) w = Math.max(w, fm.stringWidth(l));
            if (w <= size - 12) break;
            fs -= 1.5f;
        }
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getAscent() + 1, totalH = lineH * lines.length;
        int y = (size - 10) / 2 - totalH / 2 + fm.getAscent() - 1;
        for (String l : lines) { g.drawString(l, (size - fm.stringWidth(l)) / 2, y); y += lineH; }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A 16 px wide, 512 tall glyph strip is not needed: HUD bars are 11 small glyphs (0..10 filled segments). */
    public static byte[] barGlyph(int filled, int of, int colorRgb) throws IOException {
        int w = 48, h = 8;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
            boolean border = x == 0 || y == 0 || x == w - 1 || y == h - 1;
            int segW = (w - 2) / of;
            boolean on = !border && (x - 1) / Math.max(1, segW) < filled;
            int c = border ? 0xFF2A2A2A : on ? 0xFF000000 | colorRgb : 0xFF4A4A4A;
            img.setRGB(x, y, c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
