package doom.render3d;

/** Pure map-space projection for a fixed 45-degree isometric view.
 * Coordinates are Doom map units, with Z up. Increasing depth is farther away. */
public final class Fx3IsoCamera {
    public static final double WORLD_WIDTH = 640;
    public static final double A = 1 / Math.sqrt(2);
    public static final double B = 1 / Math.sqrt(6);
    public static final double C = Math.sqrt(2.0 / 3);
    public static final double D = 1 / Math.sqrt(3);
    public static final double YAW = 45;

    private Fx3IsoCamera() {}

    public static double right(double x, double y) { return A * (x - y); }
    public static double down(double x, double y, double z) {
        return -B * (x + y) - C * z;
    }
    public static double depth(double x, double y, double z) {
        return D * (x + y - z);
    }
    public static double scale(double viewportWidth) { return viewportWidth / WORLD_WIDTH; }

    /** Same BAM convention as R_ProjectSprite; all orthographic rays share ANG45. */
    public static int rotation(int facing) {
        return (0x20000000 - facing + 9 * 0x10000000) >>> 29;
    }
}
