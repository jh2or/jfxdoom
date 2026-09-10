package doom.ai;

import doom.system.Doomdef;

/** Short feedback-controlled motions, with inertia-aware braking near obstacles and targets. */
final class LocalController {
    static double difference(double a, double b) { return Math.atan2(Math.sin(a - b), Math.cos(a - b)); }

    static Explorer.Burst towards(Observation o, MotionProbe probe, double tx, double ty, int tic, boolean fight) {
        double distance = Math.hypot(tx - o.x(), ty - o.y());
        double desired = Math.atan2(ty - o.y(), tx - o.x());
        double reachable = probe.distance(o.x() + Math.cos(desired) * Math.min(distance, 128),
                o.y() + Math.sin(desired) * Math.min(distance, 128));
        if (reachable < Math.min(24, distance * .75) && probe.door(64) == null) {
            // A visible waypoint can require a small bend around a corner: do not idle forever facing it.
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 32; i++) {
                double a = i * Math.PI / 16;
                double clear = probe.distance(o.x() + 64 * Math.cos(a), o.y() + 64 * Math.sin(a));
                if (clear < 8) continue;
                double step = Math.min(32, clear);
                double gain = distance - Math.hypot(tx - o.x() - step * Math.cos(a), ty - o.y() - step * Math.sin(a));
                double value = gain + .15 * clear + .5 * Math.cos(a - o.angle());
                if (value > best) { best = value; desired = a; reachable = clear; }
            }
        }
        double angle = difference(desired, o.angle());
        int duration = reachable < 40 || Math.abs(angle) > .4 ? 2 : 4;
        int turn = (int) Math.round(angle * 65536 / (2 * Math.PI) / duration / 256) * 256;
        turn = Math.max(-2048, Math.min(2048, turn));
        int forward = Math.abs(angle) > .65 ? 0 : (reachable < 32 || distance < 40 ? 25 : 50);
        int side = 0;
        double along = o.momx() * Math.cos(desired) + o.momy() * Math.sin(desired);
        if (along > 4 && Math.min(distance, reachable) < along * 4) forward = -25;
        if (reachable < 4 && along < 2) forward = 0;
        // Repeated use can reverse a moving Doom door. Pulse, release, then let it open.
        int buttons = tic % 70 < 4 ? Doomdef.BT_USE : 0;
        if (o.enemy() != null) {
            double enemyAngle = Math.atan2(o.enemy().y / 65536.0 - o.y(), o.enemy().x / 65536.0 - o.x());
            if (Math.abs(difference(enemyAngle, o.angle())) < .22) buttons |= Doomdef.BT_ATTACK;
            if (fight && Math.abs(difference(enemyAngle, desired)) < .8) {
                turn = (int) Math.round(difference(enemyAngle, o.angle()) * 65536 / (2 * Math.PI) / duration / 256) * 256;
                turn = Math.max(-2048, Math.min(2048, turn));
            }
            buttons |= Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT);
        }
        return new Explorer.Burst(new Action(forward, side, turn, buttons), duration);
    }
}
