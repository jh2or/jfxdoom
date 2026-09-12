package doom.ai;

import doom.fixed.Fixed;
import doom.play.*;
import doom.render.RMain;
import doom.render.Tables;
import java.util.ArrayList;
import java.util.List;

/** A bounded, read-only forecast of ordinary inputs across a short hazard or shallow basin. */
final class TimedCrossing {
    static final int MARGIN = 4;
    record Frame(int tic, double x, double y, double vx, double vy, double angle, double z, Action action) {
        boolean matches(Observation o, int now) {
            return now == tic && Math.abs(o.z() - z) < .01 && Math.hypot(o.x() - x, o.y() - y) < .5
                    && Math.hypot(o.momx() - vx, o.momy() - vy) < .5
                    && Math.abs(LocalController.difference(o.angle(), angle)) < .001;
        }
    }
    record Plan(List<Frame> frames, int firstExposedTic, int dryTic) {}
    private TimedCrossing() {}

    static Plan plan(Observation o, MotionProbe probe, double tx, double ty, int levelTic) {
        double distance = Math.hypot(tx - o.x(), ty - o.y());
        if (distance < 32 || distance > 192 || FloorSafety.hazardous(o.x(), o.y())
                || !dryLanding(tx, ty) || FloorSafety.exposure(o.x(), o.y(), tx, ty) == 0
                || o.enemy() != null || !o.threats().isEmpty()
                || Math.abs(o.z() - probe.supportFloor(o.x(), o.y())) > .01
                || Math.abs(o.z() - probe.supportFloor(tx, ty)) > .01)
            return null;
        int available = 32 - (levelTic & 31) - MARGIN;
        if (available < 3 || probe.distance(tx, ty) < distance - .01) return null;
        double dx = (tx - o.x()) / distance, dy = (ty - o.y()) / distance;
        // Reject substantial sideways inertia; there is no collision/slide or air-control speculation.
        if (o.momx() * dx + o.momy() * dy < 0 || Math.abs(o.momx() * dy - o.momy() * dx) > 1)
            return null;
        for (Thinker t = PTick.thinkercap.next; t != null && t != PTick.thinkercap; t = t.next) {
            if (!(t instanceof Mobj object)) continue;
            if (object.player != null && object.x / 65536. == o.x() && object.y / 65536. == o.y()) {
                if (object.reactiontime != 0 || (object.flags & Mobj.MF_JUSTATTACKED) != 0) return null;
                continue;
            }
            double ox = object.x / 65536., oy = object.y / 65536.;
            if ((object.flags & Mobj.MF_MISSILE) != 0 && Math.hypot(ox - o.x(), oy - o.y()) < 192) return null;
            if ((object.flags & Mobj.MF_SOLID) == 0) continue;
            double along = Math.clamp((ox - o.x()) * dx + (oy - o.y()) * dy, 0, distance);
            if (Math.hypot(ox - o.x() - dx * along, oy - o.y() - dy * along)
                    < o.radius() + object.radius / 65536. + 8) return null;
        }
        double c = Math.cos(o.angle()), s = Math.sin(o.angle());
        double forward = dx * c + dy * s, side = dx * s - dy * c;
        double thrust = Math.min(50 / Math.max(.001, Math.abs(forward)), 40 / Math.max(.001, Math.abs(side)));
        Action action = new Action((int) Math.round(thrust * forward), (int) Math.round(thrust * side), 0, 0);
        int angle = (int) (long) Math.round(o.angle() * (4294967296. / (2 * Math.PI)));
        int fine = (angle >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        int strafe = ((angle - Tables.ANG90) >>> Tables.ANGLETOFINESHIFT) & Tables.FINEMASK;
        int ax = Fixed.mul(action.forward() * 2048, Tables.finecosine(fine))
                + Fixed.mul(action.side() * 2048, Tables.finecosine(strafe));
        int ay = Fixed.mul(action.forward() * 2048, Tables.finesine[fine])
                + Fixed.mul(action.side() * 2048, Tables.finesine[strafe]);
        int x = (int) Math.round(o.x() * 65536), y = (int) Math.round(o.y() * 65536);
        int vx = (int) Math.round(o.momx() * 65536), vy = (int) Math.round(o.momy() * 65536);
        int z = (int) Math.round(o.z() * 65536), vz = 0;
        int support = (int) Math.round(probe.supportFloor(o.x(), o.y()) * 65536);
        List<Frame> frames = new ArrayList<>();
        int first = -1;
        for (int i = 0; i < available; i++) {
            frames.add(new Frame(levelTic + i, x / 65536., y / 65536., vx / 65536., vy / 65536., o.angle(), z / 65536., action));
            boolean grounded = z <= support;
            vx = Math.clamp(vx + (grounded ? ax : 0), -PMobj.MAXMOVE, PMobj.MAXMOVE);
            vy = Math.clamp(vy + (grounded ? ay : 0), -PMobj.MAXMOVE, PMobj.MAXMOVE);
            // Preserve the engine's asymmetric split-move rounding on positive large motion.
            boolean split = vx > PMobj.MAXMOVE / 2 || vy > PMobj.MAXMOVE / 2;
            int nx = x + (split ? vx / 2 + (vx >> 1) : vx);
            int ny = y + (split ? vy / 2 + (vy >> 1) : vy);
            if (!basinSegment(o, x / 65536., y / 65536., nx / 65536., ny / 65536.)) return null;
            double length = Math.hypot(nx / 65536. - o.x(), ny / 65536. - o.y());
            if (length > 256 || probe.distance(nx / 65536., ny / 65536.) < length - .01) return null;
            if (FloorSafety.exposure(x / 65536., y / 65536., nx / 65536., ny / 65536.) > 0 && first < 0)
                first = levelTic + i + 1;
            support = (int) Math.round(probe.supportFloor(nx / 65536., ny / 65536.) * 65536);
            if (support - (long) z > 24L * 65536) return null;
            x = nx; y = ny;
            // P_XYMovement applies friction using the new support floor, before P_ZMovement.
            if (z <= support) { vx = Fixed.mul(vx, PMobj.FRICTION); vy = Fixed.mul(vy, PMobj.FRICTION); }
            z += vz;
            if (z <= support) { z = support; vz = 0; }
            else vz = vz == 0 ? -2 * PMobj.GRAVITY : vz - PMobj.GRAVITY;
            if ((x / 65536. - o.x()) * dx + (y / 65536. - o.y()) * dy >= distance - 8
                    && z == support && Math.abs(z / 65536. - o.z()) < .01 && dryLanding(x / 65536., y / 65536.)) {
                // Floor damage is checked BEFORE XY movement. The next check sees this dry position.
                if (first < 0) return null;
                // Leave room for the ordinary controller to brake after the timed sequence finishes.
                double ex = x / 65536. + 4 * vx / 65536., ey = y / 65536. + 4 * vy / 65536.;
                double endDistance = Math.hypot(ex - o.x(), ey - o.y());
                if (endDistance > 256 || probe.distance(ex, ey) < endDistance - .01
                        || FloorSafety.exposure(x / 65536., y / 65536., ex, ey) > 0
                        || !basinSegment(o, x / 65536., y / 65536., ex, ey)) return null;
                return new Plan(List.copyOf(frames), first, levelTic + i + 1);
            }
        }
        return null;
    }

    private static boolean dryLanding(double x, double y) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            if (FloorSafety.hazardous(x + dx * 16, y + dy * 16)) return false;
        return true;
    }

    private static boolean basinSegment(Observation o, double x, double y, double tx, double ty) {
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(tx - x, ty - y) / 4));
        for (int i = 0; i <= samples; i++) {
            double px = x + (tx - x) * i / samples, py = y + (ty - y) * i / samples;
            var sector = RMain.rPointInSubsector((int) (px * 65536), (int) (py * 65536)).sector;
            if (sector.floorheight / 65536. > o.z() + .01 || sector.floorheight / 65536. < o.z() - 24 - .01
                    || sector.ceilingheight / 65536. - o.z() < o.height()
                    || sector.special == 11
                    || FloorSafety.hazardous(px, py) && !FloorSafety.damaging(sector)) return false;
        }
        return true;
    }
}
