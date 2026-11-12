package fi.alavesa.superbvehicles.bbmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One animated channel (rotation, position or scale) of one bone: its keyframes sorted by time, sampled the way
 * Blockbench plays them back (BoneAnimator.interpolate): linear, catmullrom (uniform spline through the neighbouring
 * keyframes, ends duplicated), step (hold) and bezier (cubic in time/value with the keyframe handles). Before the
 * first keyframe the first value holds, after the last the last one; an empty track is the channel's neutral value.
 */
public final class Track {
    public enum Mode { LINEAR, CATMULLROM, STEP, BEZIER }

    public static final class Key {
        public final double time;
        public final double[] value;
        public final Mode mode;
        public double[] leftTime, leftValue, rightTime, rightValue;
        public Key(double time, double[] value, Mode mode) { this.time = time; this.value = value.clone(); this.mode = mode; }
    }

    private final List<Key> keys;
    private final double neutral;

    public Track(List<Key> keys, double neutral) {
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Double.compare(a.time, b.time));
        this.keys = Collections.unmodifiableList(sorted);
        this.neutral = neutral;
    }

    public static Track empty(double neutral) { return new Track(List.of(), neutral); }
    public boolean isEmpty() { return keys.isEmpty(); }
    public List<Key> keys() { return keys; }
    public double lastTime() { return keys.isEmpty() ? 0 : keys.get(keys.size() - 1).time; }

    /** The channel value at time t (seconds). */
    public double[] at(double t) {
        if (keys.isEmpty()) return new double[]{neutral, neutral, neutral};
        final double eps = 1.0 / 1200;
        int lo = 0, hi = keys.size() - 1, idx = -1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (keys.get(mid).time <= t + eps) { idx = mid; lo = mid + 1; } else hi = mid - 1; }
        if (idx < 0) return keys.get(0).value.clone();
        Key before = keys.get(idx);
        if (idx == keys.size() - 1 || Math.abs(before.time - t) <= eps) return before.value.clone();
        Key after = keys.get(idx + 1);
        if (Math.abs(after.time - t) <= eps) return after.value.clone();
        if (before.mode == Mode.STEP) return before.value.clone();
        double alpha = (t - before.time) / (after.time - before.time);
        if (before.mode == Mode.CATMULLROM || after.mode == Mode.CATMULLROM) {
            Key bp = idx > 0 ? keys.get(idx - 1) : before;
            Key ap = idx + 2 < keys.size() ? keys.get(idx + 2) : after;
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) out[i] = catmullRom(alpha, bp.value[i], before.value[i], after.value[i], ap.value[i]);
            return out;
        }
        if (before.mode == Mode.BEZIER || after.mode == Mode.BEZIER) {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) out[i] = bezier(before, after, i, t);
            return out;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = before.value[i] + (after.value[i] - before.value[i]) * alpha;
        return out;
    }

    public static double catmullRom(double t, double p0, double p1, double p2, double p3) {
        double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5, t2 = t * t, t3 = t * t2;
        return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
    }

    private static double bezier(Key a, Key b, int axis, double t) {
        double gap = b.time - a.time;
        double h1t = a.rightTime != null ? Math.max(0, Math.min(gap, a.rightTime[axis])) : gap / 3;
        double h1v = a.rightValue != null ? a.rightValue[axis] : 0;
        double h2t = b.leftTime != null ? Math.max(-gap, Math.min(0, b.leftTime[axis])) : -gap / 3;
        double h2v = b.leftValue != null ? b.leftValue[axis] : 0;
        double x0 = a.time, y0 = a.value[axis], x1 = a.time + h1t, y1 = y0 + h1v, x2 = b.time + h2t, y2 = b.value[axis] + h2v, x3 = b.time, y3 = b.value[axis];
        double lo = 0, hi = 1;
        for (int i = 0; i < 40; i++) { double mid = (lo + hi) / 2; if (cubic(mid, x0, x1, x2, x3) < t) lo = mid; else hi = mid; }
        return cubic((lo + hi) / 2, y0, y1, y2, y3);
    }

    private static double cubic(double u, double p0, double p1, double p2, double p3) {
        double m = 1 - u;
        return m * m * m * p0 + 3 * m * m * u * p1 + 3 * m * u * u * p2 + u * u * u * p3;
    }

    public static Mode mode(String s) {
        if (s == null) return Mode.LINEAR;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "catmullrom", "smooth" -> Mode.CATMULLROM;
            case "step" -> Mode.STEP;
            case "bezier" -> Mode.BEZIER;
            default -> Mode.LINEAR;
        };
    }
}
