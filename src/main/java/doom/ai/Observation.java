package doom.ai;

import doom.play.*;
import doom.render.*;

/** Read-only local observations. No line specials, exit coordinates, or global route are exposed. */
record Observation(double x, double y, double angle, int health, int ammo, int weapons,
                   int localOpening, Mobj enemy, double z, double height, double radius,
                   double momx, double momy, int preferredWeapon) {
    static Observation read(Episode episode) {
        Player p = episode.player();
        Mobj mo = p.mo;
        int weapons = 0;
        for (int i = 0; i < p.weaponowned.length; i++) if (p.weaponowned[i]) weapons |= 1 << i;
        int ammo = p.ammo[0] + 4 * p.ammo[1];
        Sector s = mo.subsector.sector;
        int opening = (s.ceilingheight - s.floorheight) >> 20;
        // Local two-sided openings distinguish a closed passage from a previously opened one.
        for (Line l : s.lines) {
            if (l.backsector != null && distanceToLine(mo.x / 65536.0, mo.y / 65536.0, l) < 128) {
                opening = 31 * opening + ((Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight)
                        - Math.max(l.frontsector.floorheight, l.backsector.floorheight)) >> 20);
            }
        }
        double x = mo.x / 65536.0, y = mo.y / 65536.0;
        Mobj enemy = null;
        double nearest = 512;
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (!(t instanceof Mobj other) || other == mo || other.health <= 0
                    || (other.flags & Mobj.MF_COUNTKILL) == 0) continue;
            double ox = other.x / 65536.0, oy = other.y / 65536.0;
            double distance = Math.hypot(ox - x, oy - y);
            if (distance < nearest && clearance(x, y, ox, oy, false) > .99) {
                nearest = distance; enemy = other;
            }
        }
        int preferred = p.weaponowned[doom.system.Doomdef.wp_shotgun] && p.ammo[1] > 0
                ? doom.system.Doomdef.wp_shotgun : p.ammo[0] > 0 ? doom.system.Doomdef.wp_pistol : doom.system.Doomdef.wp_fist;
        return new Observation(x, y, Integer.toUnsignedLong(mo.angle) * (Math.PI * 2 / 4294967296.0),
                p.health, ammo, weapons, opening, enemy, mo.z / 65536.0, mo.height / 65536.0,
                mo.radius / 65536.0, mo.momx / 65536.0, mo.momy / 65536.0, preferred);
    }

    Archive.Cell cell(int size) {
        return new Archive.Cell((int) Math.floor(x / size), (int) Math.floor(y / size),
                ((int) Math.floor(angle / (Math.PI / 4))) & 7,
                health / 25, ammo / 20, weapons, localOpening);
    }

    static double distanceToLine(double x, double y, Line l) {
        double ax = l.v1.x / 65536.0, ay = l.v1.y / 65536.0;
        double dx = l.dx / 65536.0, dy = l.dy / 65536.0;
        double t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / Math.max(1, dx * dx + dy * dy)));
        return Math.hypot(x - ax - t * dx, y - ay - t * dy);
    }

    /** Fraction of a local ray before an obstruction. Closed two-sided passages can be approached. */
    static double clearance(double x, double y, double tx, double ty, boolean approachDoors) {
        double nearest = 1, rx = tx - x, ry = ty - y;
        for (Line l : PSetup.lines) {
            double ax = l.v1.x / 65536.0, ay = l.v1.y / 65536.0;
            double sx = l.dx / 65536.0, sy = l.dy / 65536.0;
            double cross = rx * sy - ry * sx;
            if (Math.abs(cross) < 1e-9) continue;
            double t = ((ax - x) * sy - (ay - y) * sx) / cross;
            double u = ((ax - x) * ry - (ay - y) * rx) / cross;
            if (t < 0 || t > nearest || u < 0 || u > 1) continue;
            boolean blocks = l.backsector == null || (l.flags & Line.ML_BLOCKING) != 0;
            if (!blocks) {
                int gap = Math.min(l.frontsector.ceilingheight, l.backsector.ceilingheight)
                        - Math.max(l.frontsector.floorheight, l.backsector.floorheight);
                blocks = !approachDoors && gap < 56 * 65536;
                if (approachDoors && Math.abs((long) l.frontsector.floorheight - l.backsector.floorheight) > 24L * 65536)
                    blocks = true;
            }
            if (blocks) nearest = t;
        }
        return nearest;
    }
}
