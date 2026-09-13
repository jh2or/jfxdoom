package doom.render3d;

import java.util.ArrayList;
import java.util.List;

/** Clips wall quads against a protected player region in orthographic view space.
 * Pure geometry: does not query or change collision, sight, or map flags. */
public final class Fx3IsoVisibility {
    private Fx3IsoVisibility() {}

    public static boolean obstructs(double x1, double y1, double x2, double y2,
                                    double bottom, double top,
                                    double px, double py, double pz, double height,
                                    boolean alreadyCut) {
        double margin = alreadyCut ? 12 : 0;
        double centerZ = pz + height / 2;
        double u1 = Fx3IsoCamera.right(x1-px, y1-py);
        double u2 = Fx3IsoCamera.right(x2-px, y2-py);
        if (Math.min(u1, u2) > 24 + margin || Math.max(u1, u2) < -24-margin) return false;
        double v1 = Fx3IsoCamera.down(x1-px, y1-py, 0);
        double v2 = Fx3IsoCamera.down(x2-px, y2-py, 0);
        double minV = Math.min(v1, v2) - Fx3IsoCamera.C*(top-centerZ);
        double maxV = Math.max(v1, v2) - Fx3IsoCamera.C*(bottom-centerZ);
        if (minV > Fx3IsoCamera.C*height/2 + 8 + margin
                || maxV < -Fx3IsoCamera.C*height/2 - 12 - margin) return false;
        if (Math.min(Fx3IsoCamera.depth(x1-px, y1-py, top-centerZ),
                Fx3IsoCamera.depth(x2-px, y2-py, top-centerZ)) >= -1) return false;
        List<double[]> poly = new ArrayList<>(List.of(
                project(x1-px, y1-py, bottom-centerZ),
                project(x2-px, y2-py, bottom-centerZ),
                project(x2-px, y2-py, top-centerZ),
                project(x1-px, y1-py, top-centerZ)));
        // A wall must overlap the player and be in front of its center plane.
        poly = clip(poly, 0, 1, 24 + margin);
        poly = clip(poly, 0, -1, 24 + margin);
        poly = clip(poly, 1, 1, Fx3IsoCamera.C * height / 2 + 8 + margin);
        poly = clip(poly, 1, -1, Fx3IsoCamera.C * height / 2 + 12 + margin);
        poly = clip(poly, 2, 1, -1);
        return !poly.isEmpty();
    }

    static double retainedTop(double floor, double bottom, double top) {
        return Math.max(bottom, Math.min(top, floor + 24));
    }

    private static double[] project(double x, double y, double z) {
        return new double[] { Fx3IsoCamera.right(x, y), Fx3IsoCamera.down(x, y, z),
                Fx3IsoCamera.depth(x, y, z) };
    }

    private static List<double[]> clip(List<double[]> input, int axis, int sign, double limit) {
        List<double[]> out = new ArrayList<>();
        if (input.isEmpty()) return out;
        double[] prev = input.getLast();
        double pd = sign * prev[axis] - limit;
        for (double[] cur : input) {
            double cd = sign * cur[axis] - limit;
            if ((cd <= 0) != (pd <= 0)) {
                double t = pd / (pd - cd);
                out.add(new double[] { prev[0] + t*(cur[0]-prev[0]),
                        prev[1] + t*(cur[1]-prev[1]), prev[2] + t*(cur[2]-prev[2]) });
            }
            if (cd <= 0) out.add(cur);
            prev = cur;
            pd = cd;
        }
        return out;
    }
}
