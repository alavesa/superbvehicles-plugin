package fi.alavesa.superbvehicles.render;

import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.Keys;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.pack.Mat;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The visible body: an invisible marker ArmorStand (root) at the vehicle position with one ItemDisplay per rig part
 * riding it (render.mode passengers) or teleported along (render.mode teleport). Each tick the parts whose world
 * matrix changed get a new Transformation: translation = R_vehicle * (M * pivot) / 16 * scale, rotation = R_vehicle
 * * rot(M), scale = colScale(M) * scale / k. Unchanged parts are not touched. The client interpolates over
 * render.interpolation-ticks, so per-tick updates look smooth.
 */
public final class VehicleRenderer {
    /**
     * The client draws an item display's item turned 180 degrees about Y (DisplayRenderer.ItemDisplayRenderer.submitInner:
     * mulPose(Axis.YP.rotation(PI)), 26.3). Without cancelling it every part was drawn rotated half a turn about its own
     * centre - invisible on symmetric boxes, scrambled on real models (0.1.0 / 0.2.0). The right rotation applies first.
     */
    static final Quaternionf ITEM_FLIP = new Quaternionf(0f, 1f, 0f, 0f);

    private final Vehicle v;
    private final List<ItemDisplay> parts = new ArrayList<>();
    private final List<Transformation> last = new ArrayList<>();
    private final List<Boolean> lastVisible = new ArrayList<>();
    private Rig rig;
    private String skin = "";
    private boolean passengers = true;
    private Location lastRootLoc;

    public VehicleRenderer(Vehicle v) { this.v = v; }

    public List<ItemDisplay> parts() { return parts; }

    /** (Re)create the part displays for the current rig and skin. */
    public void rebuild() {
        clear();
        rig = v.rig();
        skin = v.skin;
        passengers = !v.plugin.getConfig().getString("render.mode", "passengers").equalsIgnoreCase("teleport");
        if (rig == null || v.root == null || !v.root.isValid()) return;
        Location at = v.root.getLocation();
        float viewRange = (float) v.plugin.cfg("render.view-range", 2.0);
        int interp = (int) v.plugin.cfg("render.interpolation-ticks", 1), tele = (int) v.plugin.cfg("render.teleport-ticks", 1);
        boolean shadow = v.plugin.flag("render.shadow", false);
        for (Rig.Part part : rig.parts) {
            String key = part.itemModel.getOrDefault(skin, part.itemModel.get("default"));
            ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.FIXED);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setInterpolationDuration(interp);
                e.setInterpolationDelay(0);
                e.setTeleportDuration(tele);
                e.setViewRange(viewRange);
                e.setShadowRadius(shadow ? 0.5f : 0f);
                e.setRotation(0f, 0f);
                e.addScoreboardTag(Keys.TAG_ENTITY); e.addScoreboardTag(Keys.TAG_PART);
                e.getPersistentDataContainer().set(v.plugin.keys().vehicleId, PersistentDataType.STRING, v.id.toString());
                e.setItemStack(item(key));
            });
            if (passengers) v.root.addPassenger(d);
            parts.add(d); last.add(null); lastVisible.add(true);
        }
        lastRootLoc = null;
        update(true);
    }

    public void clear() {
        for (ItemDisplay d : parts) { if (d.isValid()) { if (d.getVehicle() != null) d.leaveVehicle(); d.remove(); } }
        parts.clear(); last.clear(); lastVisible.clear();
    }

    public void setSkin(String skin) {
        if (rig == null) return;
        this.skin = skin;
        for (int i = 0; i < parts.size() && i < rig.parts.size(); i++) {
            Rig.Part part = rig.parts.get(i);
            parts.get(i).setItemStack(item(part.itemModel.getOrDefault(skin, part.itemModel.get("default"))));
        }
    }

    private ItemStack item(String modelKey) {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta meta = it.getItemMeta();
        if (modelKey != null) meta.setItemModel(NamespacedKey.fromString(modelKey));
        it.setItemMeta(meta);
        return it;
    }

    /** Move the root (and in teleport mode the parts) and re-send changed transformations. */
    public void update(boolean force) {
        if (v.root == null || !v.root.isValid()) return;
        Location loc = v.pose.location(v.world);
        loc.setYaw(0); loc.setPitch(0);
        boolean moved = lastRootLoc == null || lastRootLoc.distanceSquared(loc) > 1e-8 || lastRootLoc.getWorld() != loc.getWorld();
        if (moved) {
            if (v.root.getPassengers().isEmpty()) v.root.teleport(loc); else v.root.teleport(loc, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            if (!passengers) for (ItemDisplay d : parts) if (d.isValid()) d.teleport(loc);
            lastRootLoc = loc;
        }
        if (rig == null || parts.isEmpty()) return;
        double[][][] M = rig.matrices(v.deltas);
        Matrix3d R = v.pose.rotation();
        double scale = v.type.scale;
        float yOff = passengers ? (float) v.plugin.cfg("render.passenger-offset", 0.0) : 0f;
        for (int i = 0; i < parts.size() && i < M.length; i++) {
            ItemDisplay d = parts.get(i);
            if (!d.isValid()) continue;
            Rig.Part part = rig.parts.get(i);
            double[][] m = M[i];
            boolean visible = m != null;
            if (!visible) {
                if (lastVisible.get(i) || force) { d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0, 0, 0), new Quaternionf())); lastVisible.set(i, false); last.set(i, null); }
                continue;
            }
            Transformation t = transformation(R, scale, part, m, yOff);
            Transformation prev = last.get(i);
            if (force || prev == null || !lastVisible.get(i) || changed(prev, t)) {
                d.setInterpolationDelay(0);
                d.setTransformation(t);
                last.set(i, t); lastVisible.set(i, true);
            }
        }
    }

    /**
     * The display transformation of one part: translation = R_vehicle * (M * pivot) / 16 * scale, left rotation =
     * R_vehicle * rot(M), scale = column scale of M * scale / k, right rotation = {@link #ITEM_FLIP}. The client then draws
     * the item model centred (px / 16 - 0.5), turned 180 degrees about Y, through this transformation.
     */
    static Transformation transformation(Matrix3d R, double scale, Rig.Part part, double[][] m, float yOff) {
        double[] pw = Mat.apply(m, part.pivot);
        Vector3d tr = R.transform(new Vector3d(pw[0] / 16 * scale, pw[1] / 16 * scale, pw[2] / 16 * scale));
        double[][] m3 = Mat.upper3(m);
        double[] sc = Mat.columnScale(m3);
        double[][] rot = new double[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) rot[r][c] = m3[r][c] / sc[c];
        double[][] Rv = {{R.m00, R.m10, R.m20}, {R.m01, R.m11, R.m21}, {R.m02, R.m12, R.m22}};   // JOML is column-major: m01 = row 1 of column 0
        float[] q = Mat.quaternion(Mat.mul3(Rv, rot));
        return new Transformation(new Vector3f((float) tr.x, (float) tr.y + yOff, (float) tr.z), new Quaternionf(q[0], q[1], q[2], q[3]),
            new Vector3f((float) (sc[0] * scale / part.k), (float) (sc[1] * scale / part.k), (float) (sc[2] * scale / part.k)), new Quaternionf(ITEM_FLIP));
    }

    private static boolean changed(Transformation a, Transformation b) {
        return a.getTranslation().distanceSquared(b.getTranslation()) > 1e-8 || !a.getLeftRotation().equals(b.getLeftRotation(), 1e-5f) || a.getScale().distanceSquared(b.getScale()) > 1e-8;
    }

    /** All entities this renderer owns. */
    public List<Entity> entities() { return new ArrayList<>(parts); }

    public static ArmorStand spawnRoot(Vehicle v, Location at) {
        return at.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setInvisible(true); a.setMarker(true); a.setGravity(false); a.setSilent(true); a.setInvulnerable(true); a.setPersistent(false);
            a.setBasePlate(false); a.setSmall(true); a.setCollidable(false);
            a.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.values());
            a.addScoreboardTag(Keys.TAG_ENTITY); a.addScoreboardTag(Keys.TAG_ROOT);
            a.getPersistentDataContainer().set(v.plugin.keys().vehicleId, PersistentDataType.STRING, v.id.toString());
            a.getPersistentDataContainer().set(v.plugin.keys().typeId, PersistentDataType.STRING, v.type.id);
        });
    }
}
