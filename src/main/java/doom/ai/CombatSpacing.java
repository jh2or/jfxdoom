package doom.ai;

import doom.play.*;
import doom.system.Doomdef;

/** Desired engagement ranges, measured in map units, with enemy safety taking priority over gun range. */
final class CombatSpacing {
    record Band(double minimum, double maximum) {}
    static Band band(Observation o, Mobj enemy) {
        if (o.preferredWeapon() == Doomdef.wp_missile || o.inventory().ready() == Doomdef.wp_missile)
            return new Band(384, 512);
        double minimum = enemy.type == Info.MT_SHOTGUY ? 288 : SurvivalCombat.hitscan(enemy) ? 224 : 160;
        double maximum = enemy.type == Info.MT_SHOTGUY ? 448 : 384;
        if (o.preferredWeapon() == Doomdef.wp_shotgun && enemy.type != Info.MT_SHOTGUY) maximum = 320;
        if (o.health() < 40) { minimum += 64; maximum = Math.max(maximum, minimum + 64); }
        return new Band(minimum, Math.min(512, maximum));
    }

    /** Continue respecting spacing when a bounded fight hands control back to navigation. */
    static Explorer.Burst protect(Observation o, Explorer.Burst proposed) {
        if (o.preferredWeapon() == Doomdef.wp_fist || o.preferredWeapon() == Doomdef.wp_chainsaw) return proposed;
        Mobj close = null; double deficit = 0;
        for (Mobj enemy : o.threats()) {
            if (enemy.health <= 0) continue;
            double missing = band(o, enemy).minimum() - Math.hypot(enemy.x / 65536.0 - o.x(), enemy.y / 65536.0 - o.y());
            if (missing > deficit) { deficit = missing; close = enemy; }
        }
        if (close == null) return proposed;
        Action action = proposed.action();
        double[] next = endpoint(o, action);
        double ex = close.x / 65536.0, ey = close.y / 65536.0;
        if (Math.hypot(next[0] - ex, next[1] - ey) > Math.hypot(o.x() - ex, o.y() - ey) + .1)
            return new Explorer.Burst(action, Math.min(2, proposed.duration()));
        double away = Math.atan2(o.y() - ey, o.x() - ex);
        double facing = o.angle() + action.turn() * 2 * Math.PI / 65536;
        MotionProbe probe = new MotionProbe(o, true);
        Action best = brake(o, action.turn(), action.buttons()); double bestScore = Double.NEGATIVE_INFINITY;
        for (double offset : new double[]{0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
            double travel = away + offset - facing;
            Action escape = new Action((int) Math.round(40 * Math.cos(travel)), (int) Math.round(-40 * Math.sin(travel)), action.turn(), action.buttons());
            if (!LocalController.safeMotion(o, probe, escape, 2)) continue;
            double[] end = endpoint(o, escape);
            double score = Math.hypot(end[0] - ex, end[1] - ey);
            for (Mobj other : o.threats()) if (other != close && other.health > 0)
                score -= Math.max(0, band(o, other).minimum() - Math.hypot(end[0] - other.x / 65536.0, end[1] - other.y / 65536.0));
            if (score > bestScore) { bestScore = score; best = escape; }
        }
        return new Explorer.Burst(best, 1);
    }

    static Action brake(Observation o, int turn, int buttons) {
        double c = Math.cos(o.angle()), s = Math.sin(o.angle());
        return new Action((int) Math.round(Math.max(-50, Math.min(50, -8 * (o.momx() * c + o.momy() * s)))),
                (int) Math.round(Math.max(-40, Math.min(40, -8 * (o.momx() * s - o.momy() * c)))), turn, buttons);
    }
    private static double[] endpoint(Observation o, Action action) {
        double x = o.x(), y = o.y(), vx = o.momx(), vy = o.momy(), angle = o.angle();
        for (int i = 0; i < 4; i++) {
            angle += action.turn() * 2 * Math.PI / 65536;
            vx += (action.forward() * Math.cos(angle) + action.side() * Math.sin(angle)) / 32;
            vy += (action.forward() * Math.sin(angle) - action.side() * Math.cos(angle)) / 32;
            x += vx; y += vy; vx *= .90625; vy *= .90625;
        }
        return new double[]{x, y};
    }
}
