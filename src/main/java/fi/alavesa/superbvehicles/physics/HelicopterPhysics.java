package fi.alavesa.superbvehicles.physics;

import fi.alavesa.superbvehicles.core.Vehicle;
import org.bukkit.Particle;
import org.bukkit.block.Block;

/**
 * Helicopters: server adapter around {@link HelicopterModel}. Engine (F, or any flight key with auto-start) spools
 * the rotor.
 * <p>Simple flight model (default): W/S forward / back, A/D strafe, the helicopter faces where the pilot looks, jump
 * climbs, sneak descends; holding jump + sneak together in the air for bail-hold-seconds bails out.
 * <p>Realistic: W/S = collective, A/D = pedals, the pilot's view = cyclic (look down to fly forward, look aside to
 * bank), sneak in the air = level out. YAML keys: see {@link HelicopterModel.Params#of}.
 */
public final class HelicopterPhysics extends VehiclePhysics {
    private final HelicopterModel model;
    private HelicopterModel.Report last = new HelicopterModel.Report();
    private int bailHold;

    public HelicopterPhysics(Vehicle v) { super(v); model = new HelicopterModel(HelicopterModel.Params.of(t), v::hullAt); model.params().gravity = gravity(); }

    @Override public void tick(Controls c, BlockCollider col) {
        boolean simple = model.params().simple;
        engineControl(c, simple ? "helicopter-simple" : "helicopter", model.params().autoStartEngine);
        if (simple && !s.engineOn && c.present && c.jump && model.params().autoStartEngine && v.engineAvailable()) { s.engineOn = true; v.playSound(t.sound("start", "minecraft:block.piston.extend"), 0.8f, 0.6f); }
        HelicopterModel.Input in = new HelicopterModel.Input();
        in.driver = c.present; in.engineOn = s.engineOn;
        in.lookPitch = c.lookPitch; in.lookYaw = c.lookYaw;
        if (simple) {
            in.forward = c.throttle(); in.strafe = c.steer();
            in.vertical = (c.jump ? 1 : 0) - (c.sneak ? 1 : 0);
            bailOut(c);
        } else {
            in.collective = c.throttle(); in.pedal = c.steer(); in.levelOut = c.sneak;
        }
        in.powerFactor = v.powerFactor(); in.speedLimitFactor = chunkSpeedFactor(col);
        last = model.step(v.pose, s, in, col);
        afterMove(s.lastImpact);
        if (s.landingSpeed > 0) landing(s.landingSpeed);
        s.rotorAngle = (s.rotorAngle + s.spool * t.physics("rotor-speed", 40)) % 360;
        s.rpm = s.spool * s.maxRpm;
        if (s.spool > 0.5 && v.age % 2 == 0) {
            double g = col.groundAt(v.pose.x, v.pose.y, v.pose.z, 0.3, 4);
            Block ground = Double.isNaN(g) ? null : col.below(v.pose.x, g + 0.1, v.pose.z);
            if (ground != null && !ground.isPassable()) { Obb h = v.hull(); v.world.spawnParticle(Particle.BLOCK, v.pose.x, g + 0.1, v.pose.z, 4, h.half.x, 0.05, h.half.z, ground.getBlockData()); }
        }
    }

    /** Simple model: jump + sneak held together in the air for bail-hold-seconds = bail out (sneak alone descends). */
    private void bailOut(Controls c) {
        if (!c.present || !c.jump || !c.sneak || s.onGround) { bailHold = 0; return; }
        int need = (int) Math.max(1, Math.round(cfg("seats.bail-hold-seconds", 1.0) * Units.TPS));
        org.bukkit.entity.Player d = v.driver();
        if (d == null) return;
        if (++bailHold >= need) { bailHold = 0; plugin.seats().bailOutNow(d, v); }
        else if (bailHold % 5 == 1) plugin.messages().actionBar(d, "bail-hold", fi.alavesa.superbvehicles.core.Messages.num("percent", Math.round(100.0 * bailHold / need)));
    }

    @Override public String debugLine() { return (model.params().simple ? "[simple] " : "[realistic] ") + last.line(s); }

    @Override public String animationState() {
        if (v.wreck) return "wreck";
        if (!s.engineOn && s.spool <= 0.05) return "idle";
        if (s.spool < 0.95) return "start";
        if (s.onGround) return "hover";
        if (s.collective > 0.2) return "ascend"; if (s.collective < -0.2) return "descend";
        if (s.airspeed > 3) return "fly";
        return "hover";
    }
}
