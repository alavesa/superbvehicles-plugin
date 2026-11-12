package fi.alavesa.superbvehicles.render;

import fi.alavesa.superbvehicles.bbmodel.BbModel;
import fi.alavesa.superbvehicles.bbmodel.Rig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Plays .bbmodel animation clips on a vehicle: a looping STATE clip chosen by the physics state (idle, drive,
 * reverse, left, right, hover, fly, ...; names mapped in the YAML {@code animations:} table, else matched by
 * name), crossfaded when the state changes, plus one-shot clips (fire, hit, open) layered on top. Writes bone deltas.
 */
public final class AnimationPlayer {
    private static final class Playing {
        BbModel.Animation clip; double time; boolean loop; double weight = 1, fade; boolean fadingOut; double speed = 1;
        Playing(BbModel.Animation c, boolean loop) { this.clip = c; this.loop = loop; }
    }

    private final List<Playing> layers = new ArrayList<>();
    private Playing state;
    private String stateName = "";
    private double fadeTicks = 6;
    private BbModel model;

    public void bind(BbModel model) { if (this.model != model) { this.model = model; layers.clear(); state = null; stateName = ""; } }
    public String stateName() { return stateName; }
    public void setFadeTicks(double t) { fadeTicks = Math.max(0, t); }

    /** Switch the looping state clip (null = none). `speed` 1 = Blockbench speed. */
    public void state(BbModel.Animation clip, String name, double speed) {
        if (model == null) return;
        if (name.equals(stateName) && (state == null || state.clip == clip)) { if (state != null) state.speed = speed; return; }
        stateName = name;
        if (state != null) { state.fadingOut = true; layers.add(state); }
        state = clip == null ? null : new Playing(clip, true);
        if (state != null) { state.speed = speed; state.weight = fadeTicks > 0 ? 0 : 1; }
    }

    /** Fire a one-shot clip (plays once, then is dropped). */
    public void play(BbModel.Animation clip, double speed) {
        if (clip == null || model == null) return;
        Playing p = new Playing(clip, clip.loop);
        p.speed = speed;
        layers.add(p);
    }

    public boolean playing(String clipName) {
        if (state != null && state.clip.name.equals(clipName)) return true;
        for (Playing p : layers) if (p.clip.name.equals(clipName) && !p.fadingOut) return true;
        return false;
    }

    /** Advance one tick and add the clips' channels to the deltas. */
    public void tick(Rig rig, Map<String, Rig.BoneDelta> deltas) {
        if (model == null || rig == null) return;
        if (state != null) { state.weight = fadeTicks > 0 ? Math.min(1, state.weight + 1 / fadeTicks) : 1; advance(state); apply(rig, state, deltas); }
        Iterator<Playing> it = layers.iterator();
        while (it.hasNext()) {
            Playing p = it.next();
            if (p.fadingOut) { p.weight -= fadeTicks > 0 ? 1 / fadeTicks : 1; if (p.weight <= 0) { it.remove(); continue; } }
            boolean ended = advance(p);
            apply(rig, p, deltas);
            if (ended && !p.loop && !p.clip.hold) it.remove();
        }
    }

    /** Returns true when a non-looping clip has reached its end. */
    private boolean advance(Playing p) {
        p.time += p.speed / 20.0;
        if (p.time >= p.clip.length) {
            if (p.loop && p.clip.length > 0) { p.time %= p.clip.length; return false; }
            p.time = p.clip.length;
            return true;
        }
        return false;
    }

    private void apply(Rig rig, Playing p, Map<String, Rig.BoneDelta> deltas) {
        double w = Math.max(0, Math.min(1, p.weight));
        if (w <= 0) return;
        double[] rs = model.animRotSign, ps = model.animPosSign;
        for (var e : p.clip.channels.entrySet()) {
            if (!rig.model.bones.containsKey(e.getKey())) continue;
            BbModel.Channels ch = e.getValue();
            Rig.BoneDelta d = deltas.computeIfAbsent(e.getKey(), k -> new Rig.BoneDelta());
            double[] r = ch.rot.at(p.time), po = ch.pos.at(p.time), s = ch.scale.at(p.time);
            for (int i = 0; i < 3; i++) {
                d.rot[i] += r[i] * rs[i] * w;
                d.pos[i] += po[i] * ps[i] * w;
                d.scale[i] *= 1 + (s[i] - 1) * w;
            }
        }
    }
}
