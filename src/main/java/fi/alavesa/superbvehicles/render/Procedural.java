package fi.alavesa.superbvehicles.render;

import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.BoneRole;
import fi.alavesa.superbvehicles.bbmodel.Rig;
import fi.alavesa.superbvehicles.core.Vehicle;
import fi.alavesa.superbvehicles.core.VehicleType;
import fi.alavesa.superbvehicles.physics.PhysicsState;
import fi.alavesa.superbvehicles.weapon.MountedWeapon;

import java.util.Locale;
import java.util.Map;

/**
 * Procedural bone motion from the physics state (0.3.0: wheels, steering and tracks are NOT moved by the plugin any more -
 * animate them with the model's own drive/reverse/left/right clips, which play at a speed-proportional rate): turret yaw and
 * barrel pitch follow the gunner, rotors and propellers spin with the engine, gear and flaps fold, control surfaces
 * deflect, damage-state groups appear and disappear with health. Written as bone deltas after the clips.
 */
public final class Procedural {
    private Procedural() { }

    public static void apply(Vehicle v, Rig rig, Map<String, Rig.BoneDelta> deltas) {
        PhysicsState s = v.phys;
        VehicleType t = v.type;
        double hp = v.health / v.maxHealth();
        for (BbModel.Bone b : rig.model.bones.values()) {
            BoneRole role = t.boneRoles.getOrDefault(b.name.toLowerCase(Locale.ROOT), BoneRole.of(b.name));
            switch (role) {
                case ROTOR_MAIN -> delta(deltas, b).rot[1] += s.rotorAngle;
                case ROTOR_TAIL -> delta(deltas, b).rot[0] += s.rotorAngle * 3;
                case PROPELLER -> delta(deltas, b).rot[2] += s.propAngle;
                case GEAR -> { Rig.BoneDelta d = delta(deltas, b); double up = 1 - s.gearPos; d.rot[0] += 90 * up * (b.name.toLowerCase(Locale.ROOT).contains("front") ? -1 : 1); }
                case FLAP -> delta(deltas, b).rot[0] += -35 * s.flapPos;
                case AILERON -> { int side = BoneRole.side(b.name); delta(deltas, b).rot[0] += side * s.cyclicRoll * 25; }
                case ELEVATOR -> delta(deltas, b).rot[0] += -s.cyclicPitch * 25;
                case RUDDER -> delta(deltas, b).rot[1] += s.pedal * 25;
                case DAMAGE1 -> delta(deltas, b).hidden = hp > 0.66;
                case DAMAGE2 -> delta(deltas, b).hidden = hp > 0.33;
                case WRECK -> delta(deltas, b).hidden = !v.wreck;
                case INTACT -> delta(deltas, b).hidden = v.wreck || hp <= 0.33;
                default -> { }
            }
        }
        // turrets and guns: every weapon turns its own bones (several weapons may share a turret)
        for (MountedWeapon w : v.weapons.values()) {
            BbModel.Bone turret = rig.model.bone(w.type.turretBone), gun = rig.model.bone(w.type.gunBone);
            if (turret != null) { Rig.BoneDelta d = delta(deltas, turret); d.rot[1] = -w.turretYaw; }
            if (gun != null) { Rig.BoneDelta d = delta(deltas, gun); d.rot[0] = w.gunPitch; if (turret == null) d.rot[1] = -w.turretYaw; }
            if (w.recoilTicks > 0 && gun != null) { Rig.BoneDelta d = delta(deltas, gun); d.pos[2] += 2.0 * w.recoilTicks / Math.max(1, w.recoilMax); }
        }
    }

    private static Rig.BoneDelta delta(Map<String, Rig.BoneDelta> deltas, BbModel.Bone b) { return deltas.computeIfAbsent(b.uuid, k -> new Rig.BoneDelta()); }
}
