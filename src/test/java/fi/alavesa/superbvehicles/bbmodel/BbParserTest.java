package fi.alavesa.superbvehicles.bbmodel;

import fi.alavesa.superbvehicles.core.VehicleClass;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.pack.PlaceholderModels;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BbParserTest {
    private static VehicleType type(String id, String cls) {
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("class", cls);
        c.set("seats", List.of(Map.of("name", "driver", "locator", "seat_driver", "role", "driver"), Map.of("name", "gunner", "locator", "seat_gunner", "role", "gunner", "weapons", List.of("mg"))));
        c.set("weapons.mg.type", "mg"); c.set("weapons.mg.muzzle", List.of("muzzle_1"));
        return new VehicleType(id, c, null);
    }

    @Test void placeholderParsesWithLocatorsAndRoles() throws Exception {
        VehicleType t = type("test_truck", "wheeled");
        BbModel m = BbParser.parse(PlaceholderModels.forType(t), "test_truck");
        assertEquals(4, m.formatMajor);
        assertFalse(m.cubes.isEmpty());
        assertNotNull(m.locator("seat_driver"));
        assertNotNull(m.locator("seat_gunner"));
        assertNotNull(m.locator("muzzle_1"));
        assertNotNull(m.bone("turret"));
        assertNotNull(m.bone("gun"));
        assertEquals(BoneRole.WHEEL, BoneRole.of(m.bone("wheel_fl").name));
        assertTrue(BoneRole.steeringWheel("wheel_fl"));
        assertFalse(BoneRole.steeringWheel("wheel_rl"));
        assertEquals(-1, BoneRole.side("wheel_fl"));
        assertEquals(1, BoneRole.side("wheel_rr"));
        // the hitbox cube is hidden and in a hitbox bone
        assertTrue(m.hiddenCubes >= 1);
        assertEquals(BoneRole.HITBOX, BoneRole.of("hitbox_hull"));
        // the idle animation was read with its keyframes
        BbModel.Animation idle = m.animations.get("idle");
        assertNotNull(idle);
        assertTrue(idle.loop);
        assertEquals(2.0, idle.length, 1e-9);
        assertEquals(1, idle.channels.size());
        BbModel.Channels ch = idle.channels.values().iterator().next();
        assertEquals(0.4, ch.pos.at(1.0)[1], 1e-6);
        assertEquals(0.0, ch.pos.at(0.0)[1], 1e-6);
        // Blockbench 4 keyframe signs
        assertArrayEquals(new double[]{-1, -1, 1}, m.animRotSign);
    }

    @Test void everyClassTemplateParses() throws Exception {
        for (VehicleClass c : VehicleClass.values()) {
            VehicleType t = type("t_" + c.name().toLowerCase(), c.name().toLowerCase());
            BbModel m = BbParser.parse(PlaceholderModels.forType(t), t.model);
            assertFalse(m.cubes.isEmpty(), c.name());
            assertNotNull(m.locator("seat_driver"), c + " has no driver seat locator");
            assertTrue(m.bonesStartingWith("hitbox").size() >= 1, c + " has no hitbox bone");
        }
    }

    @Test void brokenFilesGiveActionableErrors() {
        BbParser.BbParseException e1 = assertThrows(BbParser.BbParseException.class, () -> BbParser.parse("{not json", "bad"));
        assertTrue(e1.getMessage().contains("not valid JSON"));
        BbParser.BbParseException e2 = assertThrows(BbParser.BbParseException.class, () -> BbParser.parse("{\"elements\":[]}", "empty"));
        assertTrue(e2.getMessage().contains("no cube"));
    }

    @Test void oddElementsBecomeWarningsNotCrashes() throws Exception {
        String json = "{\"meta\":{\"format_version\":\"5.0\"},\"elements\":[{\"name\":\"c\",\"type\":\"cube\",\"from\":[0,0,0],\"to\":[4,4,4],\"faces\":{\"north\":{\"uv\":[0,0,4,4],\"texture\":0}}},{\"name\":\"m\",\"type\":\"mesh\",\"origin\":[0,0,0],\"vertices\":{\"a\":[0,0,0],\"b\":[2,3,1]}},{\"name\":\"x\",\"type\":\"locator\",\"position\":\"oops\"}],\"outliner\":[],\"textures\":[]}";
        BbModel m = BbParser.parse(json, "odd");
        assertEquals(5, m.formatMajor);
        assertArrayEquals(new double[]{1, 1, 1}, m.animRotSign);
        assertEquals(1, m.meshCount);
        assertTrue(m.warnings.stream().anyMatch(w -> w.contains("mesh")));
        assertTrue(m.warnings.stream().anyMatch(w -> w.contains("malformed position")));
        assertTrue(m.warnings.stream().anyMatch(w -> w.contains("no textures")));
    }
}
