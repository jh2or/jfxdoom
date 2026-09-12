package doom.ai;

import doom.system.Doomdef;

/** Short feedback-controlled motions, with inertia-aware braking near obstacles and targets. */
final class LocalController {
    static double difference(double a, double b) { return Math.atan2(Math.sin(a - b), Math.cos(a - b)); }

    private final int turnLimit;
    private final LearningStrategy strategy;
    private final SurvivalCombat survivalCombat;
    private final RocketSafety rockets = new RocketSafety();
    private final DurableCombat durableCombat;
    private final BarrelController barrels;
    LocalController() { this(false); }
    LocalController(boolean explore) { this(explore, 2048); }
    /** Faster mouse-style turning remains quantized to ordinary demo inputs. */
    LocalController(boolean explore, int turnLimit) { this(explore, turnLimit, LearningStrategy.SPEED); }
    LocalController(boolean explore, int turnLimit, LearningStrategy strategy) {
        if (turnLimit < 256 || turnLimit > 32512 || (turnLimit & 255) != 0)
            throw new IllegalArgumentException("Turn limit must be positive and demo-quantized");
        this.turnLimit = turnLimit;
        this.strategy = strategy;
        pickups = new PickupController(strategy == LearningStrategy.SURVIVAL);
        survivalCombat = strategy == LearningStrategy.SURVIVAL ? new SurvivalCombat() : null;
        durableCombat = new DurableCombat(explore);
        barrels = explore ? new BarrelController() : null;
    }
    private final PickupController pickups;
    private final UseController use = new UseController();
    private doom.play.Mobj combatEnemy;
    private int combatUntil;
    private int quietSince = -1;
    private boolean waypointPriority;
    private TimedCrossing.Plan timedCrossing;
    private int timedFrame;

    private void observeQuiet(Observation o, int tic) {
        if (o.enemy() != null || o.threats().stream().anyMatch(e -> e.health > 0)) {
            quietSince = -1; waypointPriority = false;
        } else {
            if (quietSince < 0) quietSince = tic;
            if (!waypointPriority && tic - quietSince >= 70) {
                waypointPriority = true;
                if (survivalCombat != null) survivalCombat.releaseFocus();
                durableCombat.reset(); combatEnemy = null; combatUntil = 0;
            }
        }
    }
    void reset() { timedCrossing = null; timedFrame = 0; quietSince = -1; waypointPriority = false; rockets.reset(); if (survivalCombat != null) survivalCombat.reset(); if (barrels != null) barrels.reset(); durableCombat.reset(); use.reset(); pickups.reset(); combatEnemy = null; combatUntil = 0; }

    Explorer.Burst towards(Observation o, MotionProbe probe, double tx, double ty, int tic, boolean fight) {
        if (strategy == LearningStrategy.SURVIVAL) {
            observeQuiet(o, tic);
            o = o.withPreferredWeapon(RocketSafety.preferredWeapon(o));
        }
        boolean crossing = FloorSafety.crossing(o, tx, ty);
        Explorer.Burst timed = timedMotion(o, probe, tx, ty, crossing);
        if (timed != null) return timed;
        return protect(o, propose(o, probe, tx, ty, tic, fight, crossing), tic, crossing, tx, ty);
    }

    private Explorer.Burst timedMotion(Observation o, MotionProbe probe, double tx, double ty, boolean crossing) {
        int now = doom.play.PTick.leveltime;
        if (timedCrossing != null && (timedFrame >= timedCrossing.frames().size()
                || !timedCrossing.frames().get(timedFrame).matches(o, now))) {
            // A collision, knockback, teleport or intervening input invalidates the forecast.
            // Ordinary floor safety then brakes on dry ground or keeps escaping a hazard.
            timedCrossing = null;
        }
        if (timedCrossing == null && !crossing) return null;
        if (timedCrossing == null) {
            timedCrossing = TimedCrossing.plan(o, probe, tx, ty, now);
            timedFrame = 0;
        }
        if (timedCrossing == null) return null;
        Action move = timedCrossing.frames().get(timedFrame++).action();
        int buttons = o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending()
                ? Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT) : 0;
        // One ordinary input at a time, checked against the forecast on the next observation.
        // Keep the committed crossing moving even if navigation selects another nearby waypoint.
        return new Explorer.Burst(new Action(move.forward(), move.side(), move.turn(), buttons), 1);
    }

    Explorer.Burst protect(Observation o, Explorer.Burst proposed, int tic) {
        return protect(o, proposed, tic, false, o.x(), o.y());
    }

    private Explorer.Burst protect(Observation o, Explorer.Burst proposed, int tic, boolean crossing, double tx, double ty) {
        if (strategy == LearningStrategy.SURVIVAL && !crossing) proposed = CombatSpacing.protect(o, proposed);
        proposed = rockets.protect(o, proposed, tic);
        // Covers navigation, recovery, and automatic weapon switches.
        // Never authorize punching/chainsaw attacks while waiting for a loaded gun to equip.
        if (!o.inventory().loadedRangedWeapon(o.weapons(), o.preferredWeapon())
                || !o.inventory().loadedRangedWeapon(o.weapons(), o.inventory().ready())) {
            Action action = proposed.action();
            proposed = new Explorer.Burst(new Action(action.forward(), action.side(), action.turn(),
                    action.buttons() & ~Doomdef.BT_ATTACK), proposed.duration());
        }
        return FloorSafety.protect(o, proposed, crossing, tx, ty);
    }

    private Explorer.Burst propose(Observation o, MotionProbe probe, double tx, double ty, int tic, boolean fight, boolean crossing) {
        boolean canEngage = o.inventory().loadedRangedWeapon(o.weapons(), o.preferredWeapon());
        if (!crossing && survivalCombat != null) {
            Action combat = survivalCombat.choose(o, probe, tic);
            if (combat != null) return new Explorer.Burst(combat, 2);
        }
        if (barrels != null && canEngage) {
            Action clear = barrels.choose(o, tx, ty, tic);
            if (clear != null) return new Explorer.Burst(clear, 2);
        }
        var pickup = crossing ? null : pickups.choose(o, probe, tx, ty, tic);
        if (pickup != null) { tx = pickup.x / 65536.0; ty = pickup.y / 65536.0; }
        else {
            Action combat = !crossing && strategy == LearningStrategy.SPEED && canEngage ? durableCombat.choose(o, probe, tic) : null;
            if (combat != null) return new Explorer.Burst(combat, 2);
        }
        double distance = Math.hypot(tx - o.x(), ty - o.y());
        double desired = Math.atan2(ty - o.y(), tx - o.x());
        double reachable = probe.distance(o.x() + Math.cos(desired) * Math.min(distance, 128),
                o.y() + Math.sin(desired) * Math.min(distance, 128));
        var obstruction = probe.useTarget(desired, java.util.Set.of());
        boolean approachDoor = obstruction != null && (probe.switchPanel(obstruction)
                || probe.needsUse(obstruction) && !probe.locked(obstruction));
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
        turn = Math.max(-turnLimit, Math.min(turnLimit, turn));
        int forward = Math.abs(angle) > .65 ? 0 : (reachable < 32 || distance < 40 ? 25 : 50);
        int side = 0;
        double along = o.momx() * Math.cos(desired) + o.momy() * Math.sin(desired);
        if (along > 4 && Math.min(distance, reachable) < along * 4) forward = -25;
        if (reachable < 4 && along < 2) forward = 0;
        int buttons = o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending()
                ? Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT) : 0;
        boolean engaging = false;
        if (canEngage && o.enemy() != null) {
            double enemyAngle = Math.atan2(o.enemy().y / 65536.0 - o.y(), o.enemy().x / 65536.0 - o.x());
            if (Math.abs(difference(enemyAngle, o.angle())) < .22) buttons |= Doomdef.BT_ATTACK;
            double enemyDistance = Math.hypot(o.enemy().x / 65536.0 - o.x(), o.enemy().y / 65536.0 - o.y());
            boolean acquireShotgun = !Inventory.owns(o.weapons(), Doomdef.wp_shotgun)
                    && o.enemy().type == doom.play.Info.MT_SHOTGUY && o.inventory().bullets() > 0;
            if (!crossing && (fight || acquireShotgun) && enemyDistance < 192
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
            int strafeTurn = fight && canEngage && o.enemy() != null ? turn : turnTowards(facing, o.angle(), 2);
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

    private int turnTowards(double target, double facing, int duration) {
        int turn = (int) Math.round(difference(target, facing) * 65536 / (2 * Math.PI) / duration / 256) * 256;
        return Math.max(-turnLimit, Math.min(turnLimit, turn));
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
