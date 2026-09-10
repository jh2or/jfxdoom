package doom.ai;

import doom.system.Doomdef;

/** Short feedback-controlled motions, with inertia-aware braking near obstacles and targets. */
final class LocalController {
    static double difference(double a, double b) { return Math.atan2(Math.sin(a - b), Math.cos(a - b)); }

    private final PickupController pickups = new PickupController();
    private final UseController use = new UseController();
    private doom.play.Mobj combatEnemy;
    private int combatUntil;
    void reset() { use.reset(); pickups.reset(); combatEnemy = null; combatUntil = 0; }

    Explorer.Burst towards(Observation o, MotionProbe probe, double tx, double ty, int tic, boolean fight) {
        var pickup = pickups.choose(o, probe, tx, ty, tic);
        if (pickup != null) { tx = pickup.x / 65536.0; ty = pickup.y / 65536.0; }
        double distance = Math.hypot(tx - o.x(), ty - o.y());
        double desired = Math.atan2(ty - o.y(), tx - o.x());
        double reachable = probe.distance(o.x() + Math.cos(desired) * Math.min(distance, 128),
                o.y() + Math.sin(desired) * Math.min(distance, 128));
        var obstruction = probe.useTarget(desired, java.util.Set.of());
        boolean approachDoor = obstruction != null && obstruction.backsector != null
                && probe.blocks(obstruction);
        if (reachable < Math.min(24, distance * .75) && !approachDoor) {
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
        int buttons = o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending()
                ? Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT) : 0;
        boolean engaging = false;
        if (o.enemy() != null) {
            double enemyAngle = Math.atan2(o.enemy().y / 65536.0 - o.y(), o.enemy().x / 65536.0 - o.x());
            if (Math.abs(difference(enemyAngle, o.angle())) < .22) buttons |= Doomdef.BT_ATTACK;
            double enemyDistance = Math.hypot(o.enemy().x / 65536.0 - o.x(), o.enemy().y / 65536.0 - o.y());
            boolean acquireShotgun = !Inventory.owns(o.weapons(), Doomdef.wp_shotgun)
                    && o.enemy().type == doom.play.Info.MT_SHOTGUY && o.inventory().bullets() > 0;
            if ((fight || acquireShotgun) && enemyDistance < 192
                    && (acquireShotgun || enemyDistance < 64 || Math.abs(difference(enemyAngle, desired)) < .9)) {
                if (combatEnemy != o.enemy()) { combatEnemy = o.enemy(); combatUntil = tic + 140; }
                if (tic < combatUntil) {
                    // Give a nearby obstructing enemy a bounded fighting interval before resuming the route.
                    engaging = true; duration = 2; forward = side = 0;
                    turn = turnTowards(enemyAngle, o.angle(), duration);
                }
            }
        }
        if (!engaging && reachable >= 80 && distance >= 96 && Math.abs(angle) < 1.4 && probe.door(96) == null) {
            // Offset the view from the travel direction to combine vanilla forward/run and strafe thrust.
            double diagonal = Math.atan2(40, 50);
            double facing = desired + (difference(o.angle(), desired) >= 0 ? diagonal : -diagonal);
            int strafeTurn = fight && o.enemy() != null ? turn : turnTowards(facing, o.angle(), 2);
            double travel = difference(desired, o.angle() + strafeTurn * (2 * Math.PI / 65536));
            double c = Math.cos(travel), s = Math.sin(travel);
            double thrust = Math.min(50 / Math.max(.001, Math.abs(c)), 40 / Math.max(.001, Math.abs(s)));
            Action diagonalMove = new Action((int) Math.round(thrust * c), (int) Math.round(-thrust * s), strafeTurn, 0);
            if (safeMotion(o, probe, diagonalMove, 2)) {
                forward = diagonalMove.forward(); side = diagonalMove.side(); turn = strafeTurn; duration = 2;
            }
        }
        if (probe.avoidsDrops() && !safeMotion(o, probe, new Action(forward, side, turn, 0), duration)) {
            // Turning does not remove inertia: brake both world-space momentum components before a cliff.
            double c = Math.cos(o.angle()), s = Math.sin(o.angle());
            forward = (int) Math.round(Math.max(-50, Math.min(50, -8 * (o.momx() * c + o.momy() * s))));
            side = (int) Math.round(Math.max(-40, Math.min(40, -8 * (o.momx() * s - o.momy() * c))));
            duration = 1;
        }
        // Check interaction every tic near a door or wall; use is edge-triggered in Doom.
        if (probe.door(80) != null || reachable < 64) duration = 1;
        if (use.press(probe, o.angle() + turn * (2 * Math.PI / 65536), tic)) {
            buttons |= Doomdef.BT_USE;
            duration = 1;
        }
        return new Explorer.Burst(new Action(forward, side, turn, buttons), duration);
    }

    private static int turnTowards(double target, double facing, int duration) {
        int turn = (int) Math.round(difference(target, facing) * 65536 / (2 * Math.PI) / duration / 256) * 256;
        return Math.max(-2048, Math.min(2048, turn));
    }

    /** Short inertial prediction, including both thrust axes and turning, checked with the player footprint. */
    static boolean safeMotion(Observation o, MotionProbe probe, Action action, int duration) {
        double x = o.x(), y = o.y(), vx = o.momx(), vy = o.momy(), facing = o.angle();
        for (int i = 0; i < duration + 2; i++) {
            if (i < duration) facing += action.turn() * (2 * Math.PI / 65536);
            vx += (action.forward() * Math.cos(facing) + action.side() * Math.sin(facing)) / 32;
            vy += (action.forward() * Math.sin(facing) - action.side() * Math.cos(facing)) / 32;
            x += vx; y += vy; vx *= .90625; vy *= .90625;
            double d = Math.hypot(x - o.x(), y - o.y());
            if (d > 256 || probe.distance(x, y) < d - .01) return false;
        }
        return true;
    }
}
