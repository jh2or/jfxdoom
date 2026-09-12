package doom.ai;

import doom.play.*;
import doom.system.Doomdef;
import java.util.IdentityHashMap;

/** Clear visible local threats without chasing them into unexplored rooms. */
final class SurvivalCombat {
    private static final class Fight {
        int deadline, lastDamage, health, avoidUntil;
        Fight(Mobj enemy, int tic) { deadline = tic + 1050; lastDamage = tic; health = enemy.health; }
    }
    private final IdentityHashMap<Mobj, Fight> fights = new IdentityHashMap<>();
    private int side = 40;
    private boolean retreating;
    private Mobj spacingTarget;
    void reset() { fights.clear(); side = 40; releaseFocus(); }
    void releaseFocus() { retreating = false; spacingTarget = null; }

    static double priorityDistance(Mobj enemy, double distance) {
        // Close melee threats first; hitscan enemies are dangerous even at a distance.
        if (distance < 96) return distance * .25;
        return distance * (hitscan(enemy) ? .55 : 1);
    }
    static boolean hitscan(Mobj enemy) {
        return enemy.type == Info.MT_POSSESSED || enemy.type == Info.MT_SHOTGUY || enemy.type == Info.MT_CHAINGUY;
    }

    Action choose(Observation o, MotionProbe probe, int tic) {
        Mobj enemy = o.enemy();
        if (enemy == null || enemy.health <= 0 || !o.inventory().loadedRangedWeapon(o.weapons(), o.preferredWeapon())) return null;
        double distance = Math.hypot(enemy.x / 65536.0 - o.x(), enemy.y / 65536.0 - o.y());
        if (distance > 512) return null;
        fights.entrySet().removeIf(e -> e.getKey().health <= 0 || (e.getValue().avoidUntil > 0 && tic >= e.getValue().avoidUntil));
        if (fights.size() > 64) fights.clear();
        Fight fight = fights.computeIfAbsent(enemy, e -> new Fight(e, tic));
        if (fight.avoidUntil > tic) return null;
        if (enemy.health < fight.health) { fight.lastDamage = tic; fight.health = enemy.health; }
        if (tic >= fight.deadline || tic - fight.lastDamage >= 175) {
            fight.avoidUntil = tic + 210; return null;
        }
        MotionProbe cautious = probe.avoidsDrops() ? probe : new MotionProbe(o, true);
        double aim = Math.atan2(enemy.y / 65536.0 - o.y(), enemy.x / 65536.0 - o.x());
        int turn = (int) Math.round(LocalController.difference(aim, o.angle()) * 65536 / (4 * Math.PI) / 256) * 256;
        turn = Math.max(-2048, Math.min(2048, turn));
        double facing = o.angle() + turn * 2 * Math.PI / 65536;
        int buttons = Math.abs(LocalController.difference(aim, facing)) < .18 ? Doomdef.BT_ATTACK : 0;
        if (o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending())
            buttons |= Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT);
        CombatSpacing.Band band = CombatSpacing.band(o, enemy);
        if (spacingTarget != enemy) { spacingTarget = enemy; retreating = false; }
        if (distance < band.minimum()) retreating = true;
        if (distance >= band.minimum() + 48) retreating = false;
        // Hold a band instead of repeatedly crossing one retreat threshold. Only approach an isolated
        // non-hitscan target beyond the weapon's useful range; never close on a distant shotgunner.
        int forward = retreating ? -25 : distance > band.maximum() && !hitscan(enemy) && o.threats().size() <= 1 ? 15 : 0;
        Action best = null; double bestScore = Double.NEGATIVE_INFINITY;
        for (int strafe : new int[] {side, -side, 0}) {
            Action action = new Action(forward, strafe, turn, buttons);
            if (!LocalController.safeMotion(o, cautious, action, 2)) continue;
            double nx = o.x() + Math.cos(facing) * forward * .4 + Math.sin(facing) * strafe * .4;
            double ny = o.y() + Math.sin(facing) * forward * .4 - Math.cos(facing) * strafe * .4;
            double score = strafe == side ? 1 : 0;
            for (Mobj threat : o.threats()) {
                double tx = threat.x / 65536.0, ty = threat.y / 65536.0;
                double d = Math.hypot(tx - nx, ty - ny);
                score -= Math.max(0, CombatSpacing.band(o, threat).minimum() - d) * .2;
                if (hitscan(threat) && Observation.clearance(nx, ny, tx, ty, false) > .99) score -= 12;
            }
            if (strafe == 0) score -= 3;
            if (score > bestScore) { bestScore = score; best = action; }
        }
        if (best != null) { if (best.side() != 0) side = best.side(); return best; }
        // Brake at walls or ledges while aiming; movement never bypasses collision checks.
        double c = Math.cos(o.angle()), s = Math.sin(o.angle());
        return new Action((int) Math.max(-50, Math.min(50, -8 * (o.momx() * c + o.momy() * s))),
                (int) Math.max(-40, Math.min(40, -8 * (o.momx() * s - o.momy() * c))), turn, buttons);
    }
}
