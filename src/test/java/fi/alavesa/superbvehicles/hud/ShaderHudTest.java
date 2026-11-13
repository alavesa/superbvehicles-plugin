package fi.alavesa.superbvehicles.hud;

import fi.alavesa.superbvehicles.pack.HudPackBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HUD component and a CPU re-implementation of the shader's placement, used to render preview images
 * (target/hud-preview-*.png) so the layout and art can be checked without a client.
 */
class ShaderHudTest {
    @Test void groundComponentCarriesEncodedGlyphsAndCancelsItsWidth() {
        ShaderHud.Values v = new ShaderHud.Values();
        v.speedFraction = 0.5; v.speedReadout = 72; v.fuelFraction = 0.6;
        List<ShaderHud.Glyph> g = ShaderHud.glyphs(v);
        assertTrue(g.stream().anyMatch(x -> x.id() == HudLayout.NEEDLE), "needle below the redline");
        assertEquals(2, g.stream().filter(x -> x.glyph().startsWith("digit")).count(), "72 = two digits");
        assertTrue(g.stream().anyMatch(x -> x.id() == HudLayout.DIGIT0 + 1 && x.glyph().equals("digit7")), "right-aligned digits");
        assertTrue(g.stream().noneMatch(x -> x.id() == HudLayout.WARN));
        Component c = ShaderHud.component(g, "military", Map.of("dial", 9), Component.text("hi"));
        String flat = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        assertTrue(flat.endsWith("hi"));
        for (Component child : c.children()) {
            if (child instanceof TextComponent t && t.color() != null && HudEncoding.isMarker(t.color().value())) {
                assertNotNull(t.shadowColor(), "HUD glyphs must have a transparent shadow");
                assertEquals(0, t.shadowColor().alpha());
            }
        }
        v.speedFraction = 0.95; v.fuelFraction = 0.1;
        g = ShaderHud.glyphs(v);
        assertTrue(g.stream().anyMatch(x -> x.id() == HudLayout.NEEDLE_RED), "over the redline");
        assertTrue(g.stream().anyMatch(x -> x.id() == HudLayout.WARN), "low fuel warning");
        v.fuelFraction = 0;
        g = ShaderHud.glyphs(v);
        assertTrue(g.stream().anyMatch(x -> x.id() == HudLayout.ENGINE_OFF), "empty tank");
        assertEquals(0, g.stream().filter(x -> x.id() == HudLayout.FUEL_BAR_FACE).findFirst().orElseThrow().value(), "dark face when empty");
    }

    @Test void renderPreviews() throws Exception {
        ShaderHud.Values car = new ShaderHud.Values();
        car.speedFraction = 0.55; car.speedReadout = 66; car.fuelFraction = 0.62;
        preview(car, "military", "target/hud-preview-ground.png", 0);
        car.speedFraction = 0.93; car.speedReadout = 111; car.fuelFraction = 0.09; car.fuelArc = true;
        preview(car, "civilian", "target/hud-preview-ground-redline.png", 0);
        ShaderHud.Values heli = new ShaderHud.Values();
        heli.aircraft = true; heli.speedFraction = 0.6; heli.speedReadout = 88; heli.fuelFraction = 0.4;
        heli.verticalFraction = 0.5; heli.rollDegrees = 20; heli.pitchDegrees = -8; heli.altitude = 142;
        preview(heli, "aircraft", "target/hud-preview-aircraft.png", 0);
        assertTrue(new File("target/hud-preview-aircraft.png").length() > 1000);
    }

    /** Draw what the shader would draw, at GUI scale 3 on a 1280x720 screen, blinking icons visible. */
    static void preview(ShaderHud.Values v, String skin, String out, double gameTime) throws Exception {
        Map<String, byte[]> art = HudTextures.all(HudTextures.skin(skin));
        int W = 1280, H = 720; double gs = 3;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(120, 170, 220), 0, H, new Color(90, 120, 70)));
        g.fillRect(0, 0, W, H);
        HudLayout layout = HudLayout.of(null);
        double guiW = W / gs, guiH = H / gs;
        for (ShaderHud.Glyph gl : ShaderHud.glyphs(v)) {
            HudLayout.Element e = HudLayout.ELEMENTS[gl.id()];
            HudLayout.Placement pl = e.profile() == HudLayout.Profile.GROUND ? layout.ground : layout.aircraft;
            BufferedImage tex = ImageIO.read(new ByteArrayInputStream(art.get(gl.glyph())));
            double val = gl.value() / 255.0;
            tex = shade(tex, e.kind(), val);
            double ax = guiW * (pl.anchor().endsWith("right") ? 1 : pl.anchor().endsWith("left") ? 0 : 0.5), ay = guiH * (pl.anchor().startsWith("top") ? 0 : 1);
            double ox = ax + (pl.offsetX() + e.x()) * pl.scale(), oy = ay + (pl.offsetY() + e.y()) * pl.scale();
            AffineTransform t = new AffineTransform();
            t.scale(gs, gs);
            t.translate(ox, oy);
            t.scale(pl.scale(), pl.scale());
            if (e.kind() == HudLayout.Kind.ROTATE || e.kind() == HudLayout.Kind.ROTATE_JITTER) t.rotate(val * Math.PI * 2 - Math.PI, e.px(), e.py());
            if (e.kind() == HudLayout.Kind.TRANSLATE_Y) t.translate(0, (val - 0.5) * 2 * HudLayout.PITCH_RANGE);
            t.scale(e.w() / tex.getWidth(), e.h() / tex.getHeight());
            g.drawImage(tex, t, null);
        }
        g.dispose();
        new File(out).getParentFile().mkdirs();
        ImageIO.write(img, "png", new File(out));
    }

    /** CPU version of sbvShade() in text.fsh. */
    static BufferedImage shade(BufferedImage src, HudLayout.Kind kind, double v) {
        BufferedImage o = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) {
            int argb = src.getRGB(x, y);
            double a = (argb >>> 24) / 255.0, r = ((argb >> 16) & 255) / 255.0, gg = ((argb >> 8) & 255) / 255.0, b = (argb & 255) / 255.0;
            double kx = (x + 0.5) / src.getWidth(), ky = (y + 0.5) / src.getHeight();
            double[] ramp = v > 0.5 ? mix(new double[]{1, 0.85, 0.15}, new double[]{0.25, 0.9, 0.3}, (v - 0.5) * 2) : mix(new double[]{0.95, 0.2, 0.15}, new double[]{1, 0.85, 0.15}, v * 2);
            switch (kind) {
                case FILL_V -> { if (1 - ky > v) a = 0; r *= ramp[0]; gg *= ramp[1]; b *= ramp[2]; }
                case FILL_ARC -> { double theta = Math.toDegrees(Math.atan2(kx - 0.5, -(ky - 0.5))); double tt = (theta + 135) / 270; if (tt < 0 || tt > 1 || tt > v) a = 0; r *= ramp[0]; gg *= ramp[1]; b *= ramp[2]; }
                case FACE_DARK -> { if (v < 0.002) { r *= 0.35; gg *= 0.35; b *= 0.35; } }
                case ROTATE_JITTER -> { r = r * 0.4 + 0.6; gg = gg * 0.4 + 0.15; b = b * 0.4 + 0.12; }
                default -> { }
            }
            a *= 0.95;
            if (a < 0.1) continue;
            o.setRGB(x, y, ((int) (a * 255) << 24) | ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, gg) * 255) << 8) | (int) (Math.min(1, b) * 255));
        }
        return o;
    }
    private static double[] mix(double[] a, double[] b, double t) { return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}; }

    @Test void everyGlyphHasACharacter() { for (HudLayout.Element e : HudLayout.ELEMENTS) if (!e.glyph().equals("digit") && !e.glyph().equals("unit")) assertTrue(HudPackBuilder.CHARS.containsKey(e.glyph()), e.glyph()); }
}
