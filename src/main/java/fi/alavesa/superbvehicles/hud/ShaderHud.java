package fi.alavesa.superbvehicles.hud;

import fi.alavesa.superbvehicles.pack.HudPackBuilder;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the shader HUD's action-bar component from instrument values (no server state: unit-tested). Each glyph
 * carries its element id and value in its colour (HudEncoding); its text shadow is fully transparent so only the
 * repositioned glyph is drawn. A run of negative spaces after the glyphs cancels their advance, so a message appended
 * after the HUD stays centred.
 */
public final class ShaderHud {
    private ShaderHud() { }

    /** One glyph of the HUD: element id, glyph name (HudPackBuilder.CHARS), value byte. */
    public record Glyph(int id, String glyph, int value) { }

    /** Instrument values in display terms. */
    public static final class Values {
        public boolean aircraft;
        public double speedFraction;        // 0..1 of the gauge's full scale
        public double sweep = 270;          // degrees
        public double redline = 0.85;       // fraction
        public int speedReadout;            // already in the player's unit
        public boolean mph;
        public boolean hasFuel = true;
        public double fuelFraction;         // 0..1
        public boolean fuelArc;
        public boolean engineOff;
        public double verticalFraction;     // -1..1 of the VSI full scale
        public double rollDegrees, pitchDegrees;
        public int altitude;
    }

    public static List<Glyph> glyphs(Values v) {
        List<Glyph> out = new ArrayList<>();
        boolean a = v.aircraft;
        double frac = Math.max(0, Math.min(1, v.speedFraction));
        double needle = -v.sweep / 2 + v.sweep * frac;
        out.add(new Glyph(a ? HudLayout.A_DIAL : HudLayout.DIAL, "dial", 0));
        boolean red = frac >= v.redline;
        out.add(new Glyph(a ? (red ? HudLayout.A_NEEDLE_RED : HudLayout.A_NEEDLE) : (red ? HudLayout.NEEDLE_RED : HudLayout.NEEDLE), "needle", HudEncoding.angle(needle)));
        String digits = String.valueOf(Math.max(0, Math.min(999, v.speedReadout)));
        int first = 3 - digits.length();
        for (int i = 0; i < digits.length(); i++) out.add(new Glyph((a ? HudLayout.A_DIGIT0 : HudLayout.DIGIT0) + first + i, "digit" + digits.charAt(i), 0));
        out.add(new Glyph(a ? HudLayout.A_UNIT : HudLayout.UNIT, v.mph ? "unit_mph" : "unit_kmh", 0));
        if (v.hasFuel) {
            double f = Math.max(0, Math.min(1, v.fuelFraction));
            int fb = HudEncoding.fraction(f);
            if (f > 0 && fb == 0) fb = 1;   // a nearly empty tank is not "empty"
            if (a) { out.add(new Glyph(HudLayout.A_FUEL_ARC_FACE, "fuel_arc_face", fb)); out.add(new Glyph(HudLayout.A_FUEL_ARC_FILL, "fuel_arc_fill", fb)); }
            else if (v.fuelArc) { out.add(new Glyph(HudLayout.FUEL_ARC_FACE, "fuel_arc_face", fb)); out.add(new Glyph(HudLayout.FUEL_ARC_FILL, "fuel_arc_fill", fb)); }
            else { out.add(new Glyph(HudLayout.FUEL_BAR_FACE, "fuel_bar_face", fb)); out.add(new Glyph(HudLayout.FUEL_BAR_FILL, "fuel_bar_fill", fb)); }
            if (f > 0 && f < 0.15) out.add(new Glyph(a ? HudLayout.A_WARN : HudLayout.WARN, "warn", 0));
        }
        if (v.engineOff || (v.hasFuel && v.fuelFraction <= 0)) out.add(new Glyph(a ? HudLayout.A_ENGINE_OFF : HudLayout.ENGINE_OFF, "engine_off", 0));
        if (a) {
            out.add(new Glyph(HudLayout.VSI_FACE, "vsi_face", 0));
            out.add(new Glyph(HudLayout.VSI_NEEDLE, "needle", HudEncoding.angle(-90 + Math.max(-1, Math.min(1, v.verticalFraction)) * 90)));
            out.add(new Glyph(HudLayout.HORIZON, "horizon", HudEncoding.angle(-v.rollDegrees)));
            out.add(new Glyph(HudLayout.PITCH_BAR, "pitch_bar", HudEncoding.fraction(0.5 - Math.max(-40, Math.min(40, v.pitchDegrees)) / 40.0 * 0.5)));
            String alt = String.format(java.util.Locale.ROOT, "%04d", Math.max(0, Math.min(9999, v.altitude)));
            for (int i = 0; i < 4; i++) out.add(new Glyph(HudLayout.ALT_DIGIT0 + i, "digit" + alt.charAt(i), 0));
        }
        return out;
    }

    /** The component: HUD glyphs in the skin's font, negative space to cancel their width, then `tail` (may be null). */
    public static Component component(List<Glyph> glyphs, String skin, Map<String, Integer> advances, Component tail) {
        Key font = Key.key("superbvehicles", "hud_" + skin);
        TextComponent.Builder b = Component.text();
        int total = 0;
        for (Glyph g : glyphs) {
            Character ch = HudPackBuilder.CHARS.get(g.glyph());
            if (ch == null) continue;
            b.append(Component.text(String.valueOf(ch)).font(font).color(TextColor.color(HudEncoding.encode(g.id(), g.value()))).shadowColor(ShadowColor.shadowColor(0)));
            total += advances.getOrDefault(g.glyph(), 9);
        }
        StringBuilder neg = new StringBuilder();
        for (int i = HudPackBuilder.SPACE_BITS - 1; i >= 0; i--) if ((total & (1 << i)) != 0) neg.append((char) (HudPackBuilder.SPACE_BASE + i));
        if (neg.length() > 0) b.append(Component.text(neg.toString()).font(font));
        if (tail != null) b.append(tail);
        return b.build();
    }
}
