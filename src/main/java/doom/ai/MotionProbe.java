package doom.ai;

import doom.play.PSetup;
import doom.render.Line;
import java.util.*;

/** Read-only local swept-square clearance. Doom collides a player bounding box, not a point. */
final class MotionProbe {
    private final List<Line> lines = new ArrayList<>();
    private final double x, y, z, height, radius;

    MotionProbe(Observation o) {
        x = o.x(); y = o.y(); z = o.z(); height = o.height(); radius = o.radius();
        for (Line l : PSetup.lines) {
            if (Math.max(l.v1.x, l.v2.x) / 65536.0 < x - 300 || Math.min(l.v1.x, l.v2.x) / 65536.0 > x + 300
                    || Math.max(l.v1.y, l.v2.y) / 65536.0 < y - 300 || Math.min(l.v1.y, l.v2.y) / 65536.0 > y + 300) continue;
            lines.add(l);
        }
    }

    boolean blocks(Line l) {
        if (l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0) return true;
        double floor = Math.max(l.frontsector.floorheight, l.backsector.floorheight) / 65536.0;
        double ceiling = Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight) / 65536.0;
        return ceiling - floor < height || ceiling - z < height || floor - z > 24;
    }

    /** Reachable distance along a ray, capped at a local 256 units. Contact can be escaped. */
    double distance(double tx, double ty) {
        double length = Math.hypot(tx - x, ty - y);
        if (length > 256.001) throw new IllegalArgumentException("Probe must remain local");
        if (length < .001) return 0;
        int steps = Math.max(1, (int) Math.ceil(length / 4));
        for (int i = 1; i <= steps; i++) {
            double px = x + (tx - x) * i / steps, py = y + (ty - y) * i / steps;
            for (Line l : lines) {
                if (!blocks(l) || !intersectsSquare(px, py, radius, l)) continue;
                double before = Observation.distanceToLine(x, y, l);
                // Permit movement out of an existing contact, but not deeper into one.
                if (intersectsSquare(x, y, radius, l)
                        && Observation.distanceToLine(px, py, l) > before + .01) continue;
                return length * (i - 1) / steps;
            }
        }
        return length;
    }

    /** Closed local two-sided passage, without examining special/exit identifiers. */
    Line door(double maxDistance) {
        Line best = null; double nearest = maxDistance;
        for (Line l : lines) {
            if (l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0) continue;
            int gap = Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight)
                    - Math.max(l.frontsector.floorheight, l.backsector.floorheight);
            double d = Observation.distanceToLine(x, y, l);
            if (gap < height * 65536 && d < nearest) { nearest = d; best = l; }
        }
        return best;
    }

    static boolean intersectsSquare(double x, double y, double radius, Line l) {
        double ax = l.v1.x / 65536.0, ay = l.v1.y / 65536.0;
        double bx = l.v2.x / 65536.0, by = l.v2.y / 65536.0;
        if (Math.max(ax, bx) <= x - radius || Math.min(ax, bx) >= x + radius
                || Math.max(ay, by) <= y - radius || Math.min(ay, by) >= y + radius) return false;
        double dx = bx - ax, dy = by - ay;
        double center = dx * (y - ay) - dy * (x - ax);
        double extent = radius * (Math.abs(dx) + Math.abs(dy));
        return Math.abs(center) < extent;
    }
}
