package doom.ai;

import doom.play.PSetup;
import doom.render.RMain;
import doom.render.Sector;

/** Read-only floor risk and a short inertial check, shared by navigation and combat. */
final class FloorSafety {
    private FloorSafety() {}

    static boolean damaging(Sector sector) {
        // These are damage properties only. No switch/exit target is inferred from them.
        return switch (sector.special) {
            case 4, 5, 7, 11, 16 -> true;
            default -> false;
        };
    }

    static boolean hazardous(double x, double y) {
        if (PSetup.subsectors == null || PSetup.subsectors.length == 0) return false;
        Sector sector = RMain.rPointInSubsector((int) (x * 65536), (int) (y * 65536)).sector;
        if (damaging(sector)) return true;
        int index = doom.render.RData.firstflat + sector.floorpic;
        var lumps = doom.wad.WWad.lumpinfo;
        if (index < 0 || index >= lumps.length) return false;
        String name = lumps[index].name;
        return name != null && (name.startsWith("NUKAGE") || name.startsWith("LAVA"));
    }

    /** Approximate distance over risky floor; endpoints alone miss narrow strips. */
    static double exposure(double x, double y, double tx, double ty) {
        double length = Math.hypot(tx - x, ty - y);
        int samples = Math.max(1, (int) Math.ceil(length / 4));
        int unsafe = 0;
        for (int i = 1; i <= samples; i++)
            if (hazardous(x + (tx - x) * i / samples, y + (ty - y) * i / samples)) unsafe++;
        return length * unsafe / samples;
    }

    // Each path is sampled in at most four-unit intervals. Comparing a direct
    // segment with two joined segments can differ by one sample near a boundary.
    // Only an already hazardous route receives this tolerance; dry routes stay dry.
    static boolean exceedsBudget(double exposed, double budget) {
        return exposed > budget + (budget > 0 ? 4.01 : .01);
    }

    static boolean crossing(Observation o, double tx, double ty) {
        return hazardous(o.x(), o.y()) || exposure(o.x(), o.y(), tx, ty) > 0;
    }

    static Explorer.Burst protect(Observation o, Explorer.Burst burst, boolean crossing, double tx, double ty) {
        // Once on hazardous ground, keep moving toward the route's exit rather than trapping the player there.
        if (hazardous(o.x(), o.y())) return burst;
        Action action = burst.action();
        if (action.forward() == 0 && action.side() == 0 && o.momx() == 0 && o.momy() == 0) return burst;
        double x = o.x(), y = o.y(), vx = o.momx(), vy = o.momy(), facing = o.angle();
        double exposed = 0, travelled = 0;
        for (int i = 0; i < burst.duration() + 4; i++) {
            // The tail predicts coasting after this burst, not four more input tics.
            if (i < burst.duration()) {
                facing += action.turn() * (2 * Math.PI / 65536);
                vx += (action.forward() * Math.cos(facing) + action.side() * Math.sin(facing)) / 32;
                vy += (action.forward() * Math.sin(facing) - action.side() * Math.cos(facing)) / 32;
            }
            exposed += exposure(x, y, x + vx, y + vy);
            travelled += Math.hypot(vx, vy);
            x += vx; y += vy; vx *= .90625; vy *= .90625;
        }
        if (exposed == 0) return burst;
        double distance = Math.hypot(tx - o.x(), ty - o.y());
        if (crossing && distance > .01 && (x - o.x()) * (tx - o.x()) + (y - o.y()) * (ty - o.y()) > 0) {
            double scale = Math.min(1, travelled / distance);
            double intended = exposure(o.x(), o.y(), o.x() + (tx - o.x()) * scale, o.y() + (ty - o.y()) * scale);
            if (exposed <= intended + 4) return burst;
        }
        // Counter both world-space momentum components; merely releasing forward still slides off an edge.
        facing = o.angle() + action.turn() * (2 * Math.PI / 65536);
        double c = Math.cos(facing), s = Math.sin(facing);
        int forward = (int) Math.round(Math.clamp(-8 * (o.momx() * c + o.momy() * s), -50, 50));
        int side = (int) Math.round(Math.clamp(-8 * (o.momx() * s - o.momy() * c), -40, 40));
        return new Explorer.Burst(new Action(forward, side, action.turn(), action.buttons()), 1);
    }
}
