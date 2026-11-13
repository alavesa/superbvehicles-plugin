package fi.alavesa.superbvehicles.physics;

/**
 * The ONE place where configuration units become per-tick values. Every config value is in blocks, blocks/second,
 * blocks/second^2, degrees, degrees/second, kilograms or newtons (1 block = 1 m, so 1 N on 1 kg = 1 block/s^2).
 * Physics state velocities are kept in blocks/second; only the position integration uses per-tick distances.
 */
public final class Units {
    private Units() { }
    public static final double TPS = 20.0;
    public static final double DT = 1.0 / TPS;                 // seconds per tick

    /** blocks/second -> blocks/tick (position integration only). */
    public static double perTick(double perSecond) { return perSecond / TPS; }
    /** blocks/tick -> blocks/second. */
    public static double perSecond(double perTick) { return perTick * TPS; }
    /** blocks/second -> km/h (1 block = 1 m). */
    public static double kmh(double bps) { return bps * 3.6; }
    /** blocks/second -> mph. */
    public static double mph(double bps) { return bps * 2.2369363; }
    /** acceleration (blocks/s^2) from a force (N) on a mass (kg). */
    public static double accel(double newtons, double kg) { return newtons / Math.max(1.0, kg); }
}
