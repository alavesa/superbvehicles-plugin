package fi.alavesa.superbvehicles.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BbParser;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.pack.CompositeBuilder;
import fi.alavesa.superbvehicles.pack.Mat;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end: an asymmetric entity-format Blockbench model -> parser -> pack converter -> renderer transformation ->
 * what the 26.3 client draws (item model centred at px/16 - 0.5, turned 180 degrees about Y by the item display
 * renderer, then the display transformation). Every drawn cube corner must land where Blockbench has it.
 */
class RenderPipelineTest {
    /** An original test model in the style of mod (GeckoLib / Bedrock entity) exports. */
    static String fixture() {
        return """
            {"meta":{"format_version":"4.10","model_format":"animated_entity_model","box_uv":true},
             "name":"geotruck","resolution":{"width":64,"height":128},
             "elements":[
              {"name":"chassis","uuid":"c1","type":"cube","box_uv":true,"from":[-12,4,-30],"to":[12,12,30],"origin":[0,0,0],"uv_offset":[0,0],"faces":{"north":{"uv":[0,0,24,8],"texture":0},"south":{"uv":[0,8,24,16],"texture":0},"east":{"uv":[0,16,60,24],"texture":0},"west":{"uv":[0,24,60,32],"texture":0},"up":{"uv":[0,32,24,92],"texture":0},"down":{"uv":[0,0,24,60],"texture":null}}},
              {"name":"cab","uuid":"c2","type":"cube","box_uv":true,"from":[-10,12,-28],"to":[10,26,-10],"origin":[0,0,0],"uv_offset":[0,92]},
              {"name":"mirror","uuid":"c3","type":"cube","from":[11,18,-24],"to":[16,22,-23],"origin":[13,20,-23.5],"rotation":[0,30,0],"faces":{"north":{"uv":[0,0,5,4],"texture":0},"south":{"uv":[0,0,5,4],"texture":0},"east":{"uv":[0,0,1,4],"texture":0},"west":{"uv":[0,0,1,4],"texture":0},"up":{"uv":[0,0,5,1],"texture":0},"down":{"uv":[0,0,5,1],"texture":0}}},
              {"name":"crate","uuid":"c4","type":"cube","from":[-8,12,14],"to":[2,18,26],"origin":[0,12,20],"faces":{"north":{"uv":[0,0,10,6],"texture":0},"south":{"uv":[0,0,10,6],"texture":0},"east":{"uv":[0,0,12,6],"texture":0},"west":{"uv":[0,0,12,6],"texture":0},"up":{"uv":[0,0,10,12],"texture":0},"down":{"uv":[0,0,10,12],"texture":0}}},
              {"name":"seat_driver","uuid":"l1","type":"null_object","position":[-5,14,-18]},
              {"name":"turret_ring","uuid":"c5","type":"cube","from":[-6,26,-4],"to":[6,28,8],"origin":[0,26,2],"faces":{"north":{"uv":[0,0,12,2],"texture":0},"south":{"uv":[0,0,12,2],"texture":0},"east":{"uv":[0,0,12,2],"texture":0},"west":{"uv":[0,0,12,2],"texture":0},"up":{"uv":[0,0,12,12],"texture":0},"down":{"uv":[0,0,12,12],"texture":0}}},
              {"name":"barrel","uuid":"c6","type":"cube","from":[-1,27,-26],"to":[1,29,0],"origin":[0,28,0],"faces":{"north":{"uv":[0,0,2,2],"texture":0},"south":{"uv":[0,0,2,2],"texture":0},"east":{"uv":[0,0,26,2],"texture":0},"west":{"uv":[0,0,26,2],"texture":0},"up":{"uv":[0,0,2,26],"texture":0},"down":{"uv":[0,0,2,26],"texture":0}}}
             ],
             "outliner":[
              {"name":"body","uuid":"g1","origin":[0,0,0],"rotation":[0,0,0],"children":["c1","c2","c3","l1",
                {"name":"cargo","uuid":"g2","origin":[0,12,20],"rotation":[0,180,0],"children":["c4"]}]},
              {"name":"turret","uuid":"g3","origin":[0,26,2],"rotation":[0,0,0],"children":["c5",
                {"name":"gun","uuid":"g4","origin":[0,28,0],"rotation":[-10,0,0],"children":["c6"]}]}
             ],
             "textures":[{"name":"geotruck_tex","id":"0","uuid":"t0","path":"C:\\\\Users\\\\someone\\\\geotruck_tex.png","uv_width":64,"uv_height":128}],
             "animations":[]}
            """;
    }

    static File writeFixture() throws Exception {
        File dir = new File("target/render-test/models");
        dir.mkdirs();
        Files.writeString(new File(dir, "geotruck.bbmodel").toPath(), fixture());
        BufferedImage tall = new BufferedImage(64, 128, BufferedImage.TYPE_INT_ARGB);   // taller than wide: must NOT become an animation
        for (int y = 0; y < 128; y++) for (int x = 0; x < 64; x++) tall.setRGB(x, y, 0xFF000000 | (x * 4 << 16) | (y * 2 << 8));
        ImageIO.write(tall, "png", new File(dir, "geotruck_tex.png"));
        return new File(dir, "geotruck.bbmodel");
    }

    @Test void entityFormatModelParsesCleanly() throws Exception {
        BbModel m = BbParser.parse(writeFixture());
        assertNotNull(m.textures.get(0).png, "the texture next to the .bbmodel is loaded: " + m.warnings);
        assertEquals(64, m.textures.get(0).width); assertEquals(128, m.textures.get(0).height);
        assertNotNull(m.locator("seat_driver"), "null objects are locators");
        assertEquals(1, m.texturelessFaces);
        assertFalse(m.cubes.get("c1").faces.containsKey("down"), "a face without texture is not drawn");
        CompositeBuilder.Output out = CompositeBuilder.build(m, "sbv", "geotruck", Map.of(), List.of(), Map.of(), false, List.of());
        assertTrue(out.files().keySet().stream().noneMatch(k -> k.endsWith(".mcmeta")), "a tall vehicle texture is not an animation strip");
        assertTrue(out.rig().parts.size() >= 3, "turret, gun and the 30-degree mirror get parts of their own: " + out.rig().parts.size());
    }

    @Test void everyCubeCornerLandsWhereBlockbenchHasIt() throws Exception {
        assertEquals(0, worstError(true), 0.02, "with the 180-degree compensation every corner matches");
        assertTrue(worstError(false) > 0.5, "the test must notice a missing compensation");
    }

    /** Largest distance (blocks) between a corner the client draws and the nearest Blockbench corner, and vice versa. */
    static double worstError(boolean compensate) throws Exception {
        BbModel m = BbParser.parse(writeFixture());
        CompositeBuilder.Output out = CompositeBuilder.build(m, "sbv", "geotruck", Map.of(), List.of(), Map.of(), false, List.of());
        Rig rig = out.rig();
        Pose pose = new Pose(); pose.x = 100; pose.y = 64; pose.z = -40; pose.yaw = 37; pose.pitch = 5; pose.roll = -3; pose.modelYaw = 180;
        double scale = 1.25;
        Map<String, Rig.BoneDelta> deltas = new HashMap<>();
        // turn the turret and lift the gun, like a gunner would
        Rig.BoneDelta tur = new Rig.BoneDelta(); tur.rot[1] = -40; deltas.put("g3", tur);
        Rig.BoneDelta gun = new Rig.BoneDelta(); gun.rot[0] = 12; deltas.put("g4", gun);
        double[][][] M = rig.matrices(deltas);
        // truth: Blockbench's corners through the animated bone chain
        List<double[]> truth = new ArrayList<>();
        Map<String, double[][]> cache = new HashMap<>();
        for (BbModel.Cube c : m.cubes.values()) {
            double[][] W = Mat.mul(rig.world(c.bone, deltas, cache), Mat.mul(Mat.mul(Mat.translate(c.origin[0], c.origin[1], c.origin[2]), Mat.rotZYX(c.rotation)), Mat.translate(-c.origin[0], -c.origin[1], -c.origin[2])));
            for (int k = 0; k < 8; k++) {
                double[] v = {(k & 1) == 0 ? c.from[0] : c.to[0], (k & 2) == 0 ? c.from[1] : c.to[1], (k & 4) == 0 ? c.from[2] : c.to[2]};
                org.joml.Vector3d w = pose.toWorld(Mat.apply(W, v), scale);
                truth.add(new double[]{w.x, w.y, w.z});
            }
        }
        // drawn: written element corners -> item centring -> 180 about Y -> display transformation -> entity position
        List<double[]> drawn = new ArrayList<>();
        for (Rig.Part part : rig.parts) {
            double[][] mp = M[part.index];
            assertNotNull(mp);
            Transformation t = VehicleRenderer.transformation(pose.rotation(), scale, part, mp, 0f);
            Matrix4f tf = new Matrix4f().translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale());
            if (compensate) tf.rotate(t.getRightRotation());
            tf.rotateY((float) Math.PI);                       // DisplayRenderer.ItemDisplayRenderer
            tf.translate(-0.5f, -0.5f, -0.5f);                 // item models are drawn centred
            String key = part.itemModel.get("default");
            String json = new String(out.files().get("assets/sbv/models/item/" + key.substring(4) + ".json"), StandardCharsets.UTF_8);
            for (JsonElement ee : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("elements")) {
                JsonObject e = ee.getAsJsonObject();
                double[] f = vec(e.getAsJsonArray("from")), to = vec(e.getAsJsonArray("to"));
                double[][] rot = null; double[] origin = null;
                if (e.has("rotation")) {
                    JsonObject r = e.getAsJsonObject("rotation");
                    double[] deg = {0, 0, 0}; deg["xyz".indexOf(r.get("axis").getAsString())] = r.get("angle").getAsDouble();
                    rot = Mat.rot3(deg); origin = vec(r.getAsJsonArray("origin"));
                }
                for (int k = 0; k < 8; k++) {
                    double[] v = {(k & 1) == 0 ? f[0] : to[0], (k & 2) == 0 ? f[1] : to[1], (k & 4) == 0 ? f[2] : to[2]};
                    if (rot != null) { double[] d = {v[0] - origin[0], v[1] - origin[1], v[2] - origin[2]}; double[] rd = Mat.apply3(rot, d); v = new double[]{rd[0] + origin[0], rd[1] + origin[1], rd[2] + origin[2]}; }
                    Vector4f p = tf.transform(new Vector4f((float) (v[0] / 16), (float) (v[1] / 16), (float) (v[2] / 16), 1f));
                    drawn.add(new double[]{p.x + pose.x, p.y + pose.y, p.z + pose.z});
                }
            }
        }
        return Math.max(nearest(drawn, truth), nearest(truth, drawn));
    }

    private static double nearest(List<double[]> from, List<double[]> to) {
        double worst = 0;
        for (double[] a : from) {
            double best = Double.MAX_VALUE;
            for (double[] b : to) best = Math.min(best, Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])));
            worst = Math.max(worst, best);
        }
        return worst;
    }

    private static double[] vec(JsonArray a) { return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }

    @SuppressWarnings("unused") private static Vector3f unused() { return new Vector3f(); }
}
