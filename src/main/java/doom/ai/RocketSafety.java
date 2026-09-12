package doom.ai;

import doom.play.*;
import doom.render.Line;
import doom.system.Doomdef;
import java.util.*;

/** Conservative, read-only firing envelope. Never steps the engine or edits its weapon states. */
final class RocketSafety {
    // Radius damage uses max(|dx|,|dy|), minus the victim radius, not Euclidean distance.
    static final double CLEARANCE = 256, ROCKET_RADIUS = 11, ACTOR_MARGIN = 64;
    private static final double AUTO_AIM = Math.PI / 32; // PMobj's +/- (1 << 26) angle search.
    private int holdUntil;
    private double heldAngle;
    void reset() { holdUntil = 0; }

    static List<Mobj> bodies(Observation o) {
        List<Mobj> bodies = new ArrayList<>(o.threats());
        for (Thinker t = PTick.thinkercap.next; t != null && t != PTick.thinkercap; t = t.next)
            if (t instanceof Mobj mo && !mo.removed && mo.player == null
                    && (mo.flags & (Mobj.MF_SOLID | Mobj.MF_SHOOTABLE)) != 0
                    && Math.hypot(mo.x / 65536.0 - o.x(), mo.y / 65536.0 - o.y()) < 1152 && !bodies.contains(mo)) bodies.add(mo);
        return bodies;
    }
    static boolean safeShot(Observation o, double angle) {
        return safeShot(o, angle, bodies(o), PSetup.lines, o.floor(), o.ceiling());
    }

    static boolean safeShot(Observation o, double angle, List<Mobj> bodies, Line[] lines) {
        return safeShot(o, angle, bodies, lines, o.floor(), o.ceiling());
    }

    private static boolean safeShot(Observation o, double angle, List<Mobj> bodies, Line[] lines, double knownFloor, double knownCeiling) {
        double lowSlope = 0, highSlope = 0, shootZ = o.z() + o.height() / 2 + 8;
        for (Mobj body : bodies) {
            double dx = body.x / 65536.0 - o.x(), dy = body.y / 65536.0 - o.y();
            if (body.type == Info.MT_BARREL && Math.max(Math.abs(dx), Math.abs(dy)) < CLEARANCE + 128) return false;
            double along = dx * Math.cos(angle) + dy * Math.sin(angle);
            double across = Math.abs(dx * Math.sin(angle) - dy * Math.cos(angle));
            double radius = body.radius / 65536.0 + ACTOR_MARGIN;
            if (along > 0 && along < 1100 && across <= radius + along * Math.sin(AUTO_AIM)) {
                // Include every possible vertical auto-aim target and the no-target, horizontal shot.
                lowSlope = Math.min(lowSlope, (body.z / 65536.0 - shootZ) / Math.max(1, along - radius));
                highSlope = Math.max(highSlope, ((body.z + (double) body.height) / 65536.0 - shootZ) / Math.max(1, along - radius));
            }
        }
        for (double offset : new double[]{0, -AUTO_AIM, AUTO_AIM}) {
            double facing = angle + offset;
            for (double d = 0; d <= CLEARANCE * Math.sqrt(2) + 8; d += 4) {
                double dx = d * Math.cos(facing), dy = d * Math.sin(facing);
                if (Math.max(Math.abs(dx), Math.abs(dy)) > CLEARANCE) break;
                double x = o.x() + dx, y = o.y() + dy;
                double bottom = o.z() + 32 + lowSlope * d;
                double top = o.z() + 40 + highSlope * d;
                // Current sector bounds plus every crossed opening bound possible floor/ceiling impacts.
                if (bottom < knownFloor || top > knownCeiling) return false;
                for (Line line : lines) if (MotionProbe.intersectsSquare(x, y, ROCKET_RADIUS + 4, line)) {
                    if (line.backsector == null || (line.flags & Line.ML_BLOCKING) != 0) return false;
                    if (line.frontsector.specialdata != null || line.backsector.specialdata != null) return false;
                    double floor = Math.max(line.frontsector.floorheight, line.backsector.floorheight) / 65536.0;
                    double ceiling = Math.min(line.frontsector.ceilingheight, line.backsector.ceilingheight) / 65536.0;
                    if (bottom < floor || top > ceiling) return false;
                }
                for (Mobj body : bodies) {
                    double extent = body.radius / 65536.0 + ROCKET_RADIUS + ACTOR_MARGIN;
                    if (Math.abs(body.x / 65536.0 - x) < extent && Math.abs(body.y / 65536.0 - y) < extent) return false;
                }
            }
        }
        return true;
    }

    static int preferredWeapon(Observation o) {
        int fallback = o.inventory().preferredWeapon(o.weapons());
        if (o.enemy() == null || o.inventory().rockets() <= 0 || !Inventory.owns(o.weapons(), Doomdef.wp_missile)) return fallback;
        double distance = Math.hypot(o.enemy().x / 65536.0 - o.x(), o.enemy().y / 65536.0 - o.y());
        if (distance < 384 || distance > 512 || (o.enemy().health < 150 && fallback != Doomdef.wp_fist && fallback != Doomdef.wp_chainsaw)) return fallback;
        double aim = Math.atan2(o.enemy().y / 65536.0 - o.y(), o.enemy().x / 65536.0 - o.x());
        return safeShot(o, aim) ? Doomdef.wp_missile : fallback;
    }

    Explorer.Burst protect(Observation o, Explorer.Burst proposed, int tic) {
        Inventory inv = o.inventory(); Action action = proposed.action();
        boolean requested = (action.buttons() & Doomdef.BT_CHANGE) != 0
                && ((action.buttons() & Doomdef.BT_WEAPONMASK) >> Doomdef.BT_WEAPONSHIFT) == Doomdef.wp_missile;
        if (inv.rocketWindupTics() > 0 && tic >= holdUntil) {
            heldAngle = o.angle(); holdUntil = tic + inv.rocketWindupTics() + 16;
        }
        if (tic < holdUntil) {
            // A launched attack cannot be cancelled by releasing fire. Keep its aim and brake through
            // the eight-tic windup and the first sixteen tics of flight; never repeat fire here.
            int turn = quantizedTurn(heldAngle - o.angle());
            if (inv.rocketWindupTics() > 0 && !safeShot(o, o.angle() + turn * 2 * Math.PI / 65536)) {
                for (int i = 0; i < 32; i++) {
                    int escapeTurn = quantizedTurn(i * Math.PI / 16 - o.angle());
                    double facing = o.angle() + escapeTurn * 2 * Math.PI / 65536;
                    if (safeShot(o, facing)) { heldAngle = facing; turn = escapeTurn; break; }
                }
            }
            return new Explorer.Burst(CombatSpacing.brake(o, turn, action.buttons() & ~(Doomdef.BT_ATTACK | Doomdef.BT_CHANGE | Doomdef.BT_WEAPONMASK)), 1);
        }
        if (inv.ready() != Doomdef.wp_missile && inv.pending() != Doomdef.wp_missile && !requested) return proposed;
        int buttons = action.buttons();
        double facing = o.angle() + action.turn() * 2 * Math.PI / 65536;
        boolean safe = safeShot(o, facing);
        if (!safe) {
            int fallback = inv.preferredWeapon(o.weapons());
            buttons = (buttons & ~(Doomdef.BT_ATTACK | Doomdef.BT_CHANGE | Doomdef.BT_WEAPONMASK))
                    | Doomdef.BT_CHANGE | (fallback << Doomdef.BT_WEAPONSHIFT);
        } else if (inv.ready() != Doomdef.wp_missile || !inv.rocketReady()) {
            buttons &= ~Doomdef.BT_ATTACK; // Includes automatic pickup switches and weapon raising.
        } else if ((buttons & Doomdef.BT_ATTACK) != 0) {
            if (inv.rockets() <= 0 || inv.pending() != Doomdef.wp_nochange || (buttons & Doomdef.BT_CHANGE) != 0
                    || Math.hypot(o.momx(), o.momy()) > .25) buttons &= ~Doomdef.BT_ATTACK;
            else { heldAngle = facing; holdUntil = tic + 25; }
            return new Explorer.Burst(CombatSpacing.brake(o, action.turn(), buttons), 1);
        }
        return new Explorer.Burst(new Action(action.forward(), action.side(), action.turn(), buttons), 1);
    }
    private static int quantizedTurn(double angle) {
        return Math.max(-32512, Math.min(32512, (int) Math.round(LocalController.difference(angle, 0) * 65536 / (2 * Math.PI) / 256) * 256));
    }
}
