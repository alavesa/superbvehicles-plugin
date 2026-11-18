package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.seat.PassengerAndSafeDismountController.Exit;
import fi.alavesa.superbvehicles.seat.PassengerAndSafeDismountController.Scanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exit search around a 2 x 1.6 x 4 hull at (0.5, 64, 0.5) facing +Z (left = +X); the driver sits on the left. */
class SafeDismountTest {
    static final double G = 64;
    final Scanner s = new Scanner();

    boolean find(TestVoxels w) { return s.find(w, 0.5, G, 0.5, 0, 1, -1, 0, 1.0, 2.0, 1.6, -0.4, 0.3); }

    @Test void openGroundUsesTheDriversDoor() {
        assertTrue(find(new TestVoxels(G)));
        assertEquals(Exit.LEFT_DOOR, s.exit);
        assertEquals(0.5 + 1.55, s.x, 1e-9); assertEquals(G, s.y, 1e-9); assertEquals(0.8, s.z, 1e-9);
    }

    @Test void wallOnTheLeftSendsTheRiderRight() {
        assertTrue(find(new TestVoxels(G).box(1.6, G, -10, 3, G + 3, 10)));
        assertEquals(Exit.RIGHT_DOOR, s.exit);
    }

    @Test void halfBlockedSpaceIsNotAnExit() {
        // a fence-thin post inside the player's box is enough
        assertTrue(find(new TestVoxels(G).box(2.2, G + 1, 0.7, 2.3, G + 2.5, 0.8)));
        assertEquals(Exit.RIGHT_DOOR, s.exit);
    }

    @Test void lavaAndMagmaAreRefused() {
        TestVoxels lava = new TestVoxels(G);
        for (int z = -3; z <= 3; z++) lava.addHazard(2, (int) G, z);            // lava standing beside the left door
        assertTrue(find(lava)); assertEquals(Exit.RIGHT_DOOR, s.exit);
        TestVoxels magma = new TestVoxels(G);
        for (int z = -3; z <= 3; z++) magma.addHazard(1, (int) G - 1, z);        // the floor block itself
        assertTrue(find(magma)); assertEquals(Exit.RIGHT_DOOR, s.exit);
    }

    @Test void theVoidIsRefusedButWaterBelowIsFine() {
        TestVoxels pit = new TestVoxels(G).hole(1, 3);
        assertTrue(find(pit)); assertEquals(Exit.RIGHT_DOOR, s.exit);
        TestVoxels pond = new TestVoxels(G).hole(1, 3).addWater(2, (int) G - 2, 0);
        assertTrue(find(pond)); assertEquals(Exit.LEFT_DOOR, s.exit);
    }

    @Test void stepsDownAndUpBesideTheVehicle() {
        TestVoxels up = new TestVoxels(G).box(1.6, G, -10, 3, G + 1, 10);      // a block-high kerb on the left
        assertTrue(find(up)); assertEquals(Exit.LEFT_DOOR, s.exit); assertEquals(G + 1, s.y, 1e-9);
    }

    @Test void boxedInGoesToTheRearThenTheRoof() {
        TestVoxels w = new TestVoxels(G).box(1.6, G, -10, 3, G + 4, 10).box(-2, G, -10, -0.6, G + 4, 10);
        assertTrue(find(w)); assertEquals(Exit.REAR, s.exit);
        w.box(-2, G, -4, 3, G + 4, -1.6);                                       // and a wall behind
        assertTrue(find(w)); assertEquals(Exit.ROOF, s.exit); assertEquals(G + 1.65, s.y, 1e-9);
    }

    @Test void nowhereSafeMeansNoExit() {
        TestVoxels w = new TestVoxels(G).box(1.6, G, -10, 3, G + 4, 10).box(-2, G, -10, -0.6, G + 4, 10).box(-2, G, -4, 3, G + 4, -1.6)
            .box(-2, G + 1.7, -10, 3, G + 5, 10);                               // a ceiling just above the roof
        assertFalse(find(w));
    }
}
