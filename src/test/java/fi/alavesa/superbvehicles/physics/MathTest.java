package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.bbmodel.Track;
import fi.alavesa.superbvehicles.pack.Mat;
import fi.alavesa.superbvehicles.render.Pose;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MathTest {
    @Test void poseConventions() {
        Pose p = new Pose(); p.modelYaw = 180;
        // Minecraft yaw 0 faces +Z (south): the model nose (-Z) must point to +Z
        Vector3d f = p.forward();
        assertEquals(0, f.x, 1e-9); assertEquals(1, f.z, 1e-9);
        p.yaw = 90;   // west
        f = p.forward(); assertEquals(-1, f.x, 1e-9); assertEquals(0, f.z, 1e-9);
        assertEquals(p.forwardFlat().x, f.x, 1e-9); assertEquals(p.forwardFlat().z, f.z, 1e-9);
        // right hand of a vehicle facing south is west (-X)
        p.yaw = 0; assertEquals(-1, p.right().x, 1e-9);
        // nose up lifts the front
        p.pitch = 30; assertTrue(p.forward().y > 0.49);
        // right side down with positive roll
        p.pitch = 0; p.roll = 30; assertTrue(p.right().y < -0.49);
        // model px -> world: 16 px ahead = 1 block ahead
        p.roll = 0; Vector3d w = p.toWorld(0, 0, -16, 1.0); assertEquals(1, w.z, 1e-9); assertEquals(0, w.x, 1e-9);
        // quaternion and matrix agree
        p.yaw = 37; p.pitch = 12; p.roll = -8;
        Vector3d a = p.rotation().transform(new Vector3d(1, 2, 3));
        org.joml.Vector3f b = p.quaternion().transform(new org.joml.Vector3f(1, 2, 3));
        assertEquals(a.x, b.x, 1e-4); assertEquals(a.y, b.y, 1e-4); assertEquals(a.z, b.z, 1e-4);
        assertEquals(-170, Pose.wrap(190), 1e-9);
        assertEquals(20, Pose.approachAngle(350, 20, 30), 1e-9);
        assertEquals(10, Pose.approachAngle(350, 20, 20), 1e-9);
    }

    @Test void obbTests() {
        Obb o = new Obb(new Vector3d(0, 1, 0), new Vector3d(2, 1, 4), new org.joml.Matrix3d().rotationY(Math.toRadians(45)));
        assertTrue(o.contains(new Vector3d(0, 1, 0)));
        assertFalse(o.contains(new Vector3d(0, 3.5, 0)));
        BoundingBox far = new BoundingBox(10, 0, 10, 11, 1, 11), near = new BoundingBox(0, 1.5, 0, 1, 2.5, 1);
        assertFalse(o.intersects(far)); assertTrue(o.intersects(near));
        // a block at the corner of the AABB but outside the rotated box
        BoundingBox corner = new BoundingBox(3.5, 0, 3.5, 4.5, 1, 4.5);
        assertTrue(o.aabb().overlaps(corner)); assertFalse(o.intersects(corner));
        Obb other = new Obb(new Vector3d(1, 1, 1), new Vector3d(1, 1, 1), new org.joml.Matrix3d());
        assertTrue(o.intersects(other));
        assertFalse(o.intersects(new Obb(new Vector3d(9, 1, 0), new Vector3d(1, 1, 1), new org.joml.Matrix3d())));
        double[] hit = new Obb(new Vector3d(0, 0, 0), new Vector3d(1, 1, 1), new org.joml.Matrix3d()).rayHit(new Vector3d(0, 0, -10), new Vector3d(0, 0, 1), 50);
        assertNotNull(hit); assertEquals(9, hit[0], 1e-9); assertEquals(2, (int) hit[1]); assertEquals(-1, hit[2], 1e-9);
        assertNull(new Obb(new Vector3d(), new Vector3d(1, 1, 1), new org.joml.Matrix3d()).rayHit(new Vector3d(5, 0, -10), new Vector3d(0, 0, 1), 50));
    }

    @Test void trackSampling() {
        Track t = new Track(List.of(new Track.Key(0, new double[]{0, 0, 0}, Track.Mode.LINEAR), new Track.Key(1, new double[]{10, 0, 0}, Track.Mode.LINEAR), new Track.Key(2, new double[]{10, 5, 0}, Track.Mode.STEP), new Track.Key(3, new double[]{0, 0, 0}, Track.Mode.LINEAR)), 0);
        assertEquals(5, t.at(0.5)[0], 1e-9);
        assertEquals(10, t.at(1.5)[0], 1e-9);
        assertEquals(5, t.at(2.5)[1], 1e-9);   // step holds
        assertEquals(0, t.at(9)[0], 1e-9);     // after the end
        assertEquals(1, Track.empty(1).at(3)[0], 1e-9);
        assertEquals(0.5, Track.catmullRom(0.5, 0, 0, 1, 1), 1e-9);
    }

    @Test void matHelpers() {
        double[][] r = Mat.rot3(new double[]{0, 22.5, 0});
        assertArrayEquals(new double[]{1, 22.5}, Mat.plainAxisAngle(r), 1e-9);
        assertNull(Mat.plainAxisAngle(Mat.rot3(new double[]{0, 30, 0})));
        assertNull(Mat.plainAxisAngle(Mat.rot3(new double[]{22.5, 22.5, 0})));
        double[][] m = Mat.mul(Mat.translate(1, 2, 3), Mat.rotZYX(new double[]{10, 20, 30}));
        double[] p = Mat.apply(Mat.mul(Mat.invertAffine(m), m), new double[]{4, 5, 6});
        assertArrayEquals(new double[]{4, 5, 6}, p, 1e-9);
        float[] q = Mat.quaternion(Mat.upper3(Mat.rotZYX(new double[]{0, 90, 0})));
        assertEquals(Math.sqrt(0.5), q[1], 1e-5); assertEquals(Math.sqrt(0.5), q[3], 1e-5);
        double[] e = Mat.eulerXYZ(Mat.rotXYZ(new double[]{15, -40, 70}));
        assertArrayEquals(new double[]{15, -40, 70}, e, 1e-6);
    }

    @Test void controlsKeys() {
        Controls c = new Controls();
        c.held(new org.bukkit.Input() {
            public boolean isForward() { return true; } public boolean isBackward() { return false; } public boolean isLeft() { return false; }
            public boolean isRight() { return true; } public boolean isJump() { return true; } public boolean isSneak() { return false; } public boolean isSprint() { return false; }
        });
        assertEquals(1, c.throttle(), 1e-9); assertEquals(1, c.steer(), 1e-9);
        assertTrue(c.jumpPressed); assertTrue(c.key("jump")); assertTrue(c.pressed("space"));
        c.clearEdges(); assertFalse(c.jumpPressed); assertTrue(c.jump);
    }
}
