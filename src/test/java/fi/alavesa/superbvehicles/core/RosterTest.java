package fi.alavesa.superbvehicles.core;

import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BbParser;
import fi.alavesa.superbvehicles.weapon.WeaponType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Every shipped vehicles/*.yml must load, reference a shipped model, and have its seat / muzzle locators in that model. */
class RosterTest {
    @Test void shippedRosterIsConsistent() throws Exception {
        File res = new File("src/main/resources");
        List<String> errors = new ArrayList<>();
        List<VehicleType> types = VehicleType.loadAll(new File(res, "vehicles"), errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(types.size() >= 20, "roster has " + types.size() + " vehicles");
        Set<String> ids = new HashSet<>();
        for (VehicleType t : types) {
            assertTrue(ids.add(t.id), "duplicate id " + t.id);
            File bb = new File(new File(res, "models"), t.model + ".bbmodel");
            assertTrue(bb.isFile(), t.id + " has no shipped model " + bb.getName());
            BbModel m = BbParser.parse(bb);
            for (VehicleType.Seat s : t.seats) {
                assertNotNull(m.locator(s.locator), t.id + ": seat locator " + s.locator + " missing in " + bb.getName() + " (has " + m.locators.keySet() + ")");
                for (String w : s.weapons) assertTrue(t.weapons.containsKey(w), t.id + ": seat " + s.name + " lists unknown weapon " + w);
            }
            for (WeaponType w : t.weapons.values()) {
                if (w.kind.countermeasure()) continue;
                for (String mz : w.muzzles) assertNotNull(m.locator(mz), t.id + ": muzzle " + mz + " of " + w.name + " missing in the model");
                assertTrue(w.damage > 0 && w.velocity >= 0 && w.cooldownTicks >= 1, t.id + "/" + w.name);
            }
            assertTrue(t.health > 0 && t.maxSpeed >= 0, t.id);
            if (t.vehicleClass.ground()) assertFalse(m.bonesStartingWith("wheel_").isEmpty() && m.bonesStartingWith("track_").isEmpty(), t.id + " has neither wheel nor track bones");
            if (t.vehicleClass == VehicleClass.HELICOPTER) assertNotNull(m.bone("rotor_main"), t.id);
            if (t.vehicleClass == VehicleClass.PLANE) { assertNotNull(m.bone("gear_front"), t.id); assertNotNull(m.bone("flap_l"), t.id); }
        }
        // the defaults index lists every yml and model
        String index = java.nio.file.Files.readString(new File(res, "defaults-index.txt").toPath());
        for (VehicleType t : types) { assertTrue(index.contains("vehicles/" + t.source.getName()), t.id); assertTrue(index.contains("models/" + t.model + ".bbmodel"), t.id); }
    }

    @Test void weaponKindsParse() {
        assertEquals(WeaponType.Kind.MISSILE, WeaponType.parseKind("atgm"));
        assertEquals(WeaponType.Kind.CIWS, WeaponType.parseKind("auto-turret"));
        assertEquals(WeaponType.Kind.MG, WeaponType.parseKind("nonsense"));
        assertEquals(WeaponType.Guidance.WIRE, WeaponType.parseGuidance("tow"));
        assertEquals("Heavy Mg", WeaponType.prettify("heavy_mg"));
        assertEquals(VehicleClass.TRACKED, VehicleClass.parse("tank"));
        assertEquals(VehicleClass.PLANE, VehicleClass.parse("fixed-wing"));
    }
}
