package doom.ai;

import doom.play.PSetup;
import doom.render.Line;
import java.util.*;

/** Read-only local swept-square clearance. Doom collides a player bounding box, not a point. */
final class MotionProbe {
    private final List<Line> lines = new ArrayList<>();
    private final double x, y, z, height, radius;

    private final boolean avoidDrops;
    private final Set<Line> lockedDoors;

    MotionProbe(Observation o) { this(o, false); }

    MotionProbe(Observation o, boolean avoidDrops) {
        this.avoidDrops = avoidDrops;
        lockedDoors = o.lockedDoors();
        x = o.x(); y = o.y(); z = o.z(); height = o.height(); radius = o.radius();
        for (Line l : PSetup.lines) {
            if (Math.max(l.v1.x, l.v2.x) / 65536.0 < x - 300 || Math.min(l.v1.x, l.v2.x) / 65536.0 > x + 300
                    || Math.max(l.v1.y, l.v2.y) / 65536.0 < y - 300 || Math.min(l.v1.y, l.v2.y) / 65536.0 > y + 300) continue;
            lines.add(l);
        }
    }

    /** Effective support height under the swept player footprint, including a nearby step edge. */
    double supportFloor(double px, double py) {
        double floor = doom.render.RMain.rPointInSubsector((int) (px * 65536), (int) (py * 65536)).sector.floorheight / 65536.;
        for (Line line : lines) if (line.backsector != null && intersectsSquare(px, py, radius, line))
            floor = Math.max(floor, Math.max(line.frontsector.floorheight, line.backsector.floorheight) / 65536.);
        return floor;
    }

    boolean avoidsDrops() { return avoidDrops; }

    boolean blocks(Line l) {
        if (l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0) return true;
        double floor = Math.max(l.frontsector.floorheight, l.backsector.floorheight) / 65536.0;
        double ceiling = Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight) / 65536.0;
        return ceiling - floor < height || ceiling - z < height || floor - z > 24;
    }

    boolean locked(Line line) { return lockedDoors.contains(line); }

    /** A barrier at this boundary, rather than a distant stair above/below the player. */
    boolean needsUse(Line line) {
        return line.backsector != null && (opening(line) < height
                || Math.abs((long) line.frontsector.floorheight - line.backsector.floorheight) > 24L * 65536);
    }

    /** Reachable distance along a ray, capped at a local 256 units. Contact can be escaped. */
    double distance(double tx, double ty) {
        double length = Math.hypot(tx - x, ty - y);
        if (length > 256.001) throw new IllegalArgumentException("Probe must remain local");
        if (length < .001) return 0;
        int steps = Math.max(1, (int) Math.ceil(length / 4));
        double feet = z;
        for (int i = 1; i <= steps; i++) {
            double px = x + (tx - x) * i / steps, py = y + (ty - y) * i / steps;
            double floor = Double.NEGATIVE_INFINITY, ceiling = Double.POSITIVE_INFINITY;
            for (Line l : lines) {
                if (!intersectsSquare(px, py, radius, l)) continue;
                double before = Observation.distanceToLine(x, y, l);
                // Permit movement out of an existing contact, but not deeper into one.
                if (intersectsSquare(x, y, radius, l)
                        && Observation.distanceToLine(px, py, l) > before + .01
                        && side(x, y, l) * side(px, py, l) >= 0) continue;
                if (l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0) return length * (i - 1) / steps;
                double lowFloor = Math.min(l.frontsector.floorheight, l.backsector.floorheight) / 65536.0;
                double highFloor = Math.max(l.frontsector.floorheight, l.backsector.floorheight) / 65536.0;
                if (avoidDrops && highFloor - lowFloor > 24 && feet >= highFloor - 24)
                    return length * (i - 1) / steps;
                floor = Math.max(floor, Math.max(l.frontsector.floorheight, l.backsector.floorheight) / 65536.0);
                ceiling = Math.min(ceiling, Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight) / 65536.0);
            }
            if (floor - feet > 24 || ceiling - (Double.isFinite(floor) ? floor : feet) < height) return length * (i - 1) / steps;
            // Follow successive stair heights in either direction, including descending under a lower ceiling.
            if (Double.isFinite(floor)) feet = floor;
        }
        return length;
    }

    /** Blocked local two-sided passage (door or raised lift), without examining special/exit identifiers. */
    Line door(double maxDistance) { return door(maxDistance, Set.of()); }

    Line door(double maxDistance, Set<Line> ignored) { return door(maxDistance, ignored, true); }

    Line door(double maxDistance, Set<Line> ignored, boolean includeRaised) {
        Line best = null; double nearest = maxDistance;
        for (Line l : lines) {
            if (locked(l) || ignored.contains(l) || l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0) continue;
            double tx = ((long) l.v1.x + l.v2.x) / 131072.0;
            double ty = ((long) l.v1.y + l.v2.y) / 131072.0;
            double d = Math.hypot(tx - x, ty - y);
            if ((!includeRaised && opening(l) >= height)) continue;
            if (needsUse(l) && blocks(l) && d < nearest && Observation.clearance(x, y, tx, ty, false) >= .999) { nearest = d; best = l; }
        }
        return best;
    }

    /** Recognize a visible switch texture in either initial state, never its action/tag/exit identifier. */
    boolean switchPanel(Line line) {
        int face = side(x, y, line) <= 0 ? 0 : 1;
        if (face == 1 && line.backsector == null) return false;
        int index = line.sidenum[face];
        if (PSetup.sides == null || index < 0 || index >= PSetup.sides.length) return false;
        var wall = PSetup.sides[index];
        return wall != null && (switchTexture(wall.toptexture) || switchTexture(wall.midtexture) || switchTexture(wall.bottomtexture));
    }

    private static boolean switchTexture(int index) {
        var textures = doom.render.RData.textures;
        return textures != null && index >= 0 && index < textures.length && textures[index] != null
                && textures[index].name != null && (textures[index].name.startsWith("SW1") || textures[index].name.startsWith("SW2"));
    }

    Line switchPanel(double range) { return switchPanel(range, Set.of()); }

    Line switchPanel(double range, Set<Line> used) {
        Line best = null;
        // Visual range may exceed the short collision-probe cache. Still require a visible local surface.
        for (Line line : PSetup.lines) {
            double tx = ((long) line.v1.x + line.v2.x) / 131072.0;
            double ty = ((long) line.v1.y + line.v2.y) / 131072.0;
            double d = Math.hypot(tx - x, ty - y);
            if (d < range && !locked(line) && !used.contains(line) && switchPanel(line) && Observation.clearance(x, y, tx, ty, false) >= .999) { best = line; range = d; }
        }
        return best;
    }

    /** A usable face within the ordinary use range, with no intervening surface. */
    boolean canUse(Line line, double tx, double ty) {
        return switchPanel(line) && canInteract(line, tx, ty);
    }

    boolean canInteract(Line line, double tx, double ty) {
        return !locked(line) && (switchPanel(line) || needsUse(line)) && Math.hypot(tx - x, ty - y) <= 64
                && useTarget(Math.atan2(ty - y, tx - x), Set.of()) == line;
    }

    record Passage(double x, double y) {}

    /** Aim through local openings that a fixed set of compass headings can otherwise miss. */
    List<Passage> passages() {
        List<Passage> result = new ArrayList<>();
        for (Line line : lines) {
            if (line.backsector == null || line.frontsector == line.backsector || (line.flags & Line.ML_BLOCKING) != 0
                    || opening(line) < height) continue;
            double dx = line.dx / 65536.0, dy = line.dy / 65536.0, width = Math.hypot(dx, dy);
            if (width < 2 * radius + 8) continue;
            double mx = ((long) line.v1.x + line.v2.x) / 131072.0;
            double my = ((long) line.v1.y + line.v2.y) / 131072.0;
            if (Math.hypot(mx - x, my - y) > 224) continue;
            double direction = side(x, y, line) > 0 ? -1 : 1;
            double tx = mx - direction * dy / width * 64, ty = my + direction * dx / width * 64;
            double d = Math.hypot(tx - x, ty - y);
            if (d >= 24 && d <= 256 && distance(tx, ty) >= d - .01) result.add(new Passage(tx, ty));
        }
        return result;
    }

    static double opening(Line l) {
        return l.backsector == null ? 0 : (Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight)
                - (double) Math.max(l.frontsector.floorheight, l.backsector.floorheight)) / 65536.0;
    }

    /** First obstructing surface on the ordinary 64-unit use ray; remembered doors still intercept use when open. */
    Line useTarget(double angle, Set<Line> remembered) {
        double rx = 64 * Math.cos(angle), ry = 64 * Math.sin(angle), nearest = 1;
        Line target = null;
        for (Line l : lines) {
            if (l.backsector != null && !needsUse(l) && !switchPanel(l) && !remembered.contains(l)) continue;
            double ax = l.v1.x / 65536.0, ay = l.v1.y / 65536.0;
            double sx = l.dx / 65536.0, sy = l.dy / 65536.0;
            double cross = rx * sy - ry * sx;
            if (Math.abs(cross) < 1e-9) continue;
            double t = ((ax - x) * sy - (ay - y) * sx) / cross;
            double u = ((ax - x) * ry - (ay - y) * rx) / cross;
            if (t >= 0 && t <= nearest && u >= 0 && u <= 1) { nearest = t; target = l; }
        }
        return target;
    }

    private static double side(double x, double y, Line l) {
        return l.dx / 65536.0 * (y - l.v1.y / 65536.0) - l.dy / 65536.0 * (x - l.v1.x / 65536.0);
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
