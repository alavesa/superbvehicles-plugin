package fi.alavesa.superbvehicles.pack;

import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BbParser;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.VehicleType;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompositeBuilderTest {
    private static BbModel model(String cls) throws Exception {
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("class", cls);
        c.set("seats", List.of(Map.of("name", "driver", "locator", "seat_driver", "role", "driver"), Map.of("name", "gunner", "locator", "seat_gunner", "role", "gunner", "weapons", List.of("mg"))));
        c.set("weapons.mg.type", "mg");
        VehicleType t = new VehicleType("t", c, null);
        return BbParser.parse(PlaceholderModels.forType(t), "t");
    }

    @Test void onePartPerAnimatableBoneAndStaticGeometryFolded() throws Exception {
        BbModel m = model("wheeled");
        CompositeBuilder.Output out = CompositeBuilder.build(m, "sbv", "t", Map.of(), List.of(), Map.of(), false, List.copyOf(m.animations.keySet()));
        Rig rig = out.rig();
        // wheels (4) + turret + gun + body (idle-animated) = animatable; cab/bumper/lights fold into body
        assertTrue(rig.animatable.contains(m.bone("wheel_fl").uuid));
        assertTrue(rig.animatable.contains(m.bone("turret").uuid));
        assertTrue(rig.animatable.contains(m.bone("body").uuid));
        assertFalse(rig.animatable.contains(m.bone("hitbox_hull").uuid));
        assertEquals(rig.animatable.size(), rig.parts.size(), "placeholder cubes are axis-aligned: one bone part per animatable bone");
        int cubes = 0; for (Rig.Part p : rig.parts) cubes += p.cubes;
        long visible = m.cubes.values().stream().filter(c -> !c.hidden).count();
        assertEquals(visible, cubes, "every visible cube is drawn exactly once");
        // files: geometry + item definition per part for the default skin, textures, no NaN
        for (Rig.Part p : rig.parts) {
            String key = p.itemModel.get("default");
            assertNotNull(key);
            assertTrue(out.files().containsKey("assets/sbv/models/item/" + key.substring(4) + ".json"), key);
            assertTrue(out.files().containsKey("assets/sbv/items/" + key.substring(4) + ".json"), key);
        }
        for (var e : out.files().entrySet()) if (e.getKey().endsWith(".json")) {
            String s = new String(e.getValue(), StandardCharsets.UTF_8);
            assertFalse(s.contains("NaN") || s.contains("Infinity"), e.getKey());
            if (e.getKey().contains("/models/item/")) assertTrue(withinJavaRange(s), e.getKey() + " leaves the -16..32 range");
        }
        assertTrue(out.files().containsKey("assets/sbv/textures/item/t/default/t.png"));
    }

    @Test void skinsReplaceTexturesAndGetTheirOwnItems() throws Exception {
        BbModel m = model("boat");
        CompositeBuilder.Output out = CompositeBuilder.build(m, "sbv", "t", Map.of(), List.of(), Map.of("navy", Map.of("*", CompositeBuilder.placeholderPng(1))), false, List.of());
        assertTrue(out.files().containsKey("assets/sbv/textures/item/t/navy/t.png"));
        for (Rig.Part p : out.rig().parts) { assertEquals("sbv:t/navy/p" + p.index, p.itemModel.get("navy")); assertEquals("sbv:t/default/p" + p.index, p.itemModel.get("default")); }
    }

    @Test void rotatedCubesKeepTheirCornersInWorld() throws Exception {
        // a cube turned 30 degrees about Y inside a static group: it must land as a display part whose matrix puts the corners back
        String json = "{\"meta\":{\"format_version\":\"4.10\"},\"resolution\":{\"width\":16,\"height\":16},\"textures\":[],\"elements\":[" +
            "{\"name\":\"a\",\"uuid\":\"a\",\"type\":\"cube\",\"from\":[-4,0,-4],\"to\":[4,2,4],\"origin\":[0,0,0],\"rotation\":[0,30,0],\"faces\":{\"up\":{\"uv\":[0,0,8,8],\"texture\":0}}}," +
            "{\"name\":\"b\",\"uuid\":\"b\",\"type\":\"cube\",\"from\":[10,0,-2],\"to\":[14,2,2],\"faces\":{\"up\":{\"uv\":[0,0,4,4],\"texture\":0}}}]," +
            "\"outliner\":[{\"name\":\"body\",\"uuid\":\"g\",\"origin\":[0,0,0],\"rotation\":[0,0,0],\"children\":[\"a\",\"b\"]}],\"animations\":[]}";
        BbModel m = BbParser.parse(json, "rot");
        CompositeBuilder.Output out = CompositeBuilder.build(m, "sbv", "rot", Map.of(), List.of(), Map.of(), false, List.of());
        Rig rig = out.rig();
        assertEquals(2, rig.parts.size(), "one root part for the plain cube, one display part for the 30-degree cube");
        Rig.Part disp = rig.parts.stream().filter(p -> !Mat.plainAxisAngleIdentity(p.extra)).findFirst().orElseThrow();
        double[][] M = rig.matrices(Map.of())[disp.index];
        // the display part's matrix turns the part frame by 30 degrees about Y around its pivot
        double[] fwd = Mat.apply3(Mat.upper3(M), new double[]{0, 0, -1});
        assertEquals(Math.sin(Math.toRadians(30)), -fwd[0], 1e-6);   // rotZYX(0,30,0) maps -Z toward -X
        assertEquals(-Math.cos(Math.toRadians(30)), fwd[2], 1e-6);
    }

    private static boolean withinJavaRange(String modelJson) {
        var mm = java.util.regex.Pattern.compile("\"(from|to)\":\\[(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)\\]").matcher(modelJson);
        while (mm.find()) for (int i = 2; i <= 4; i++) { double v = Double.parseDouble(mm.group(i)); if (v < -16.001 || v > 32.001) return false; }
        return true;
    }
}
