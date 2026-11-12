package fi.alavesa.superbvehicles.pack;

/**
 * Small row-major matrix helpers shared by the pack builder and the renderer: 4x4 affine transforms in Blockbench
 * pixel space, 3x3 rotations, Blockbench's ZYX Euler order for bones and Minecraft's XYZ order for display
 * transforms, plus the test "can a Java element hold this rotation as it is" (one axis at 0, +-22.5 or +-45).
 */
public final class Mat {
    private Mat() { }

    public static double[][] identity() { return new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}}; }
    public static double[][] translate(double x, double y, double z) { double[][] m = identity(); m[0][3] = x; m[1][3] = y; m[2][3] = z; return m; }
    public static double[][] scale4(double[] s) { return new double[][]{{s[0],0,0,0},{0,s[1],0,0},{0,0,s[2],0},{0,0,0,1}}; }

    /** Blockbench bone rotation: Rz * Ry * Rx (degrees). */
    public static double[][] rotZYX(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0,0},{0,Math.cos(x),-Math.sin(x),0},{0,Math.sin(x),Math.cos(x),0},{0,0,0,1}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y),0},{0,1,0,0},{-Math.sin(y),0,Math.cos(y),0},{0,0,0,1}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0,0},{Math.sin(z),Math.cos(z),0,0},{0,0,1,0},{0,0,0,1}};
        return mul(mul(rz, ry), rx);
    }

    public static double[][] rot3(double[] deg) { return upper3(rotZYX(deg)); }

    public static double[][] upper3(double[][] m) { return new double[][]{{m[0][0], m[0][1], m[0][2]}, {m[1][0], m[1][1], m[1][2]}, {m[2][0], m[2][1], m[2][2]}}; }

    public static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double s = 0; for (int k = 0; k < 4; k++) s += a[i][k] * b[k][j]; r[i][j] = s; }
        return r;
    }
    public static double[][] mul3(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { double x = 0; for (int k = 0; k < 3; k++) x += a[i][k] * b[k][j]; r[i][j] = x; }
        return r;
    }
    public static double[] apply(double[][] m, double[] v) {
        return new double[]{ m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3] };
    }
    public static double[] apply3(double[][] m, double[] v) {
        return new double[]{ m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2], m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2], m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2] };
    }
    public static double[][] transpose3(double[][] a) { double[][] r = new double[3][3]; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = a[j][i]; return r; }

    /** Inverse of an affine 4x4 (rotation x scale + translation); identity when singular. */
    public static double[][] invertAffine(double[][] m) {
        double[][] a = upper3(m);
        double det = a[0][0]*(a[1][1]*a[2][2]-a[1][2]*a[2][1]) - a[0][1]*(a[1][0]*a[2][2]-a[1][2]*a[2][0]) + a[0][2]*(a[1][0]*a[2][1]-a[1][1]*a[2][0]);
        if (Math.abs(det) < 1e-12) return identity();
        double[][] inv = new double[3][3];
        inv[0][0] = (a[1][1]*a[2][2]-a[1][2]*a[2][1])/det; inv[0][1] = (a[0][2]*a[2][1]-a[0][1]*a[2][2])/det; inv[0][2] = (a[0][1]*a[1][2]-a[0][2]*a[1][1])/det;
        inv[1][0] = (a[1][2]*a[2][0]-a[1][0]*a[2][2])/det; inv[1][1] = (a[0][0]*a[2][2]-a[0][2]*a[2][0])/det; inv[1][2] = (a[0][2]*a[1][0]-a[0][0]*a[1][2])/det;
        inv[2][0] = (a[1][0]*a[2][1]-a[1][1]*a[2][0])/det; inv[2][1] = (a[0][1]*a[2][0]-a[0][0]*a[2][1])/det; inv[2][2] = (a[0][0]*a[1][1]-a[0][1]*a[1][0])/det;
        double[] t = {m[0][3], m[1][3], m[2][3]};
        double[][] r = identity();
        for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) r[i][j] = inv[i][j]; r[i][3] = -(inv[i][0]*t[0] + inv[i][1]*t[1] + inv[i][2]*t[2]); }
        return r;
    }

    public static double[] solve3(double[][] B, double[] d) {
        double[][] m4 = {{B[0][0], B[0][1], B[0][2], 0}, {B[1][0], B[1][1], B[1][2], 0}, {B[2][0], B[2][1], B[2][2], 0}, {0, 0, 0, 1}};
        return apply3(upper3(invertAffine(m4)), d);
    }

    /** {axis, angle} when R is exactly one axis at 0, +-22.5 or +-45 degrees (what a Java element can hold), else null. */
    public static double[] plainAxisAngle(double[][] R) {
        for (int axis = 0; axis < 3; axis++) for (double ang : new double[]{0, 22.5, -22.5, 45, -45}) {
            double[] deg = {0, 0, 0}; deg[axis] = ang;
            double[][] C = rot3(deg);
            boolean same = true;
            for (int i = 0; i < 3 && same; i++) for (int j = 0; j < 3; j++) if (Math.abs(C[i][j] - R[i][j]) > 1e-6) { same = false; break; }
            if (same) return new double[]{axis, ang};
        }
        return null;
    }

    /** Column lengths of a 3x3 (the scale of R*S). */
    public static double[] columnScale(double[][] M) {
        double[] sc = new double[3];
        for (int j = 0; j < 3; j++) sc[j] = Math.sqrt(M[0][j]*M[0][j] + M[1][j]*M[1][j] + M[2][j]*M[2][j]);
        return sc;
    }

    /** Minecraft display rotation order (Rx * Ry * Rz), degrees. */
    public static double[][] rotXYZ(double[] deg) {
        double x = Math.toRadians(deg[0]), y = Math.toRadians(deg[1]), z = Math.toRadians(deg[2]);
        double[][] rx = {{1,0,0},{0,Math.cos(x),-Math.sin(x)},{0,Math.sin(x),Math.cos(x)}};
        double[][] ry = {{Math.cos(y),0,Math.sin(y)},{0,1,0},{-Math.sin(y),0,Math.cos(y)}};
        double[][] rz = {{Math.cos(z),-Math.sin(z),0},{Math.sin(z),Math.cos(z),0},{0,0,1}};
        return mul3(mul3(rx, ry), rz);
    }
    public static double[] eulerXYZ(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, m[0][2])));
        double x = Math.atan2(-m[1][2], m[2][2]);
        double z = Math.atan2(-m[0][1], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }
    public static double[] eulerZYX(double[][] m) {
        double y = Math.asin(Math.max(-1, Math.min(1, -m[2][0])));
        double x = Math.atan2(m[2][1], m[2][2]);
        double z = Math.atan2(m[1][0], m[0][0]);
        return new double[]{ Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z) };
    }

    /** Quaternion (x,y,z,w) of a pure 3x3 rotation. */
    public static float[] quaternion(double[][] R) {
        double tr = R[0][0] + R[1][1] + R[2][2];
        double x, y, z, w;
        if (tr > 0) { double s = Math.sqrt(tr + 1.0) * 2; w = 0.25 * s; x = (R[2][1] - R[1][2]) / s; y = (R[0][2] - R[2][0]) / s; z = (R[1][0] - R[0][1]) / s; }
        else if (R[0][0] > R[1][1] && R[0][0] > R[2][2]) { double s = Math.sqrt(1.0 + R[0][0] - R[1][1] - R[2][2]) * 2; w = (R[2][1] - R[1][2]) / s; x = 0.25 * s; y = (R[0][1] + R[1][0]) / s; z = (R[0][2] + R[2][0]) / s; }
        else if (R[1][1] > R[2][2]) { double s = Math.sqrt(1.0 + R[1][1] - R[0][0] - R[2][2]) * 2; w = (R[0][2] - R[2][0]) / s; x = (R[0][1] + R[1][0]) / s; y = 0.25 * s; z = (R[1][2] + R[2][1]) / s; }
        else { double s = Math.sqrt(1.0 + R[2][2] - R[0][0] - R[1][1]) * 2; w = (R[1][0] - R[0][1]) / s; x = (R[0][2] + R[2][0]) / s; y = (R[1][2] + R[2][1]) / s; z = 0.25 * s; }
        return new float[]{(float) x, (float) y, (float) z, (float) w};
    }

    /** True when a 4x4 is (numerically) the identity - a bone part with no extra rotation. */
    public static boolean plainAxisAngleIdentity(double[][] m) { for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) if (Math.abs(m[i][j] - (i == j ? 1 : 0)) > 1e-9) return false; return true; }

    public static boolean finite(double[][] m) { for (double[] r : m) for (double v : r) if (!Double.isFinite(v)) return false; return true; }
}
