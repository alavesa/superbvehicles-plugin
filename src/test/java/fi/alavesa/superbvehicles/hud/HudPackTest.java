package fi.alavesa.superbvehicles.hud;

import fi.alavesa.superbvehicles.pack.HudPackBuilder;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class HudPackTest {
    @Test void encodingRoundTripsAndStaysInTheMarkerRange() {
        for (int id = 0; id <= 31; id++) for (int v : new int[]{0, 1, 127, 254, 255}) {
            int rgb = HudEncoding.encode(id, v);
            assertTrue(HudEncoding.isMarker(rgb));
            assertEquals(id, HudEncoding.idOf(rgb)); assertEquals(v, HudEncoding.valueOf(rgb));
            assertTrue(rgb >= 0xFDA000 && rgb <= 0xFDBFFF, Integer.toHexString(rgb));
        }
        assertEquals(0, HudEncoding.angleOf(HudEncoding.encode(1, HudEncoding.angle(0))), 1.5);
        assertEquals(-135, HudEncoding.angleOf(HudEncoding.encode(1, HudEncoding.angle(-135))), 1.5);
        assertEquals(135, HudEncoding.angleOf(HudEncoding.encode(1, HudEncoding.angle(135))), 1.5);
        assertThrows(IllegalArgumentException.class, () -> HudEncoding.encode(32, 0));
    }

    @Test void noNamedOrPluginColourCollidesWithTheMarker() throws Exception {
        List<Integer> colours = new ArrayList<>();
        for (NamedTextColor c : NamedTextColor.NAMES.values()) colours.add(c.value());
        YamlConfiguration msgs = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        for (String k : msgs.getKeys(true)) if (msgs.isString(k)) colours.addAll(HudPackBuilder.colorsIn(msgs.getString(k)));
        for (int c : colours) assertFalse(HudEncoding.isMarker(c), Integer.toHexString(c));
        assertFalse(HudPackBuilder.check(Map.of(), List.of(0xFDA512)).stream().noneMatch(p -> p.contains("collides")), "a colliding colour must fail the build");
    }

    @Test void hudPackBuildsWithEveryRequiredFile() throws Exception {
        File out = new File("target/hud-test/SuperbVehicles-hud.zip");
        HudPackBuilder.Result r = new HudPackBuilder(out, HudLayout.of(null), List.of(0xFFFFFF)).build();
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        try (ZipFile z = new ZipFile(out)) {
            assertNotNull(z.getEntry("pack.mcmeta"));
            assertNotNull(z.getEntry("assets/minecraft/shaders/core/text.vsh"));
            assertNotNull(z.getEntry("assets/minecraft/shaders/core/text.fsh"));
            for (String skin : HudPackBuilder.SKINS) {
                assertNotNull(z.getEntry("assets/superbvehicles/font/hud_" + skin + ".json"), skin);
                for (String g : HudPackBuilder.CHARS.keySet()) assertNotNull(z.getEntry("assets/superbvehicles/textures/font/hud/" + skin + "/" + g + ".png"), skin + "/" + g);
            }
            String vsh = new String(z.getInputStream(z.getEntry("assets/minecraft/shaders/core/text.vsh")).readAllBytes());
            assertTrue(vsh.contains("const vec4 SBV_RECT[32]"));
            assertTrue(vsh.indexOf("#ifdef IS_GUI") < vsh.indexOf("void sbvHud()"), "HUD code only for GUI text");
        }
        // every layout element has a glyph texture
        for (HudLayout.Element e : HudLayout.ELEMENTS) assertTrue(e.glyph().equals("digit") || e.glyph().equals("unit") || HudPackBuilder.CHARS.containsKey(e.glyph()), e.glyph());
    }
}
