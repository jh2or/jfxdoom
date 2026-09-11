package doom.ai;

import doom.play.Mobj;
import doom.system.Doomdef;
import java.util.IdentityHashMap;

/** Aim while circling durable enemies; stop stalled fights rather than pushing into their bodies. */
final class DurableCombat {
    private final IdentityHashMap<Mobj, Integer> cooldown = new IdentityHashMap<>();
    private final boolean ordinary;
    DurableCombat() { this(false); }
    DurableCombat(boolean ordinary) { this.ordinary = ordinary; }
    private Mobj target;
    private int deadline, lastDamage, lastHealth, side = 40;

    void reset() { target = null; cooldown.clear(); side = 40; }

    Action choose(Observation o, MotionProbe probe, int tic) {
        Mobj enemy = o.enemy();
        cooldown.entrySet().removeIf(e -> e.getValue() <= tic || e.getKey().health <= 0);
        if (enemy == null || enemy.health <= 0 || o.ammo() <= 0
                || (!ordinary && (enemy.info == null ? enemy.health : enemy.info.spawnhealth) < 300)
                || cooldown.containsKey(enemy)) { target = null; return null; }
        double dx = enemy.x / 65536.0 - o.x(), dy = enemy.y / 65536.0 - o.y();
        double distance = Math.hypot(dx, dy);
        if (distance > (ordinary && (enemy.info == null ? enemy.health : enemy.info.spawnhealth) < 300 ? 384 : 512)) { target = null; return null; }
        if (target != enemy) {
            target = enemy; deadline = tic + 35 * 45; lastDamage = tic; lastHealth = enemy.health;
        }
        if (enemy.health < lastHealth) { lastHealth = enemy.health; lastDamage = tic; }
        if (tic >= deadline || tic - lastDamage >= 175) {
            if (cooldown.size() >= 16) cooldown.clear();
            cooldown.put(enemy, tic + 350); target = null; return null;
        }
        if (!probe.avoidsDrops()) probe = new MotionProbe(o, true);
        double aim = Math.atan2(dy, dx);
        int turn = (int) Math.round(LocalController.difference(aim, o.angle()) * 65536 / (2 * Math.PI) / 2 / 256) * 256;
        turn = Math.max(-2048, Math.min(2048, turn));
        int buttons = Math.abs(LocalController.difference(aim, o.angle() + turn * 2 * Math.PI / 65536)) < .22
                ? Doomdef.BT_ATTACK : 0;
        if (o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending())
            buttons |= Doomdef.BT_CHANGE | (o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT);
        // Retreat before melee range; approach only when too far for effective shotgun fire.
        int forward = distance < 192 ? -25 : distance > 320 ? 25 : 0;
        for (int direction : new int[] {side, -side}) {
            Action action = new Action(forward, direction, turn, buttons);
            if (LocalController.safeMotion(o, probe, action, 2)) { side = direction; return action; }
        }
        // A wall or drop can prevent strafing. Brake momentum while continuing to aim/fire.
        double c = Math.cos(o.angle()), s = Math.sin(o.angle());
        int brakeForward = (int) Math.round(Math.max(-50, Math.min(50, -8 * (o.momx() * c + o.momy() * s))));
        int brakeSide = (int) Math.round(Math.max(-40, Math.min(40, -8 * (o.momx() * s - o.momy() * c))));
        return new Action(brakeForward, brakeSide, turn, buttons);
    }
}
