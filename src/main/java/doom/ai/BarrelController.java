package doom.ai;

import doom.play.*;
import doom.system.Doomdef;

/** Clear a locally observed explosive obstruction only after retreating outside its blast radius. */
final class BarrelController {
    private Mobj target;
    private int deadline, retry;
    void reset() { target = null; retry = 0; }

    Action choose(Observation o, double tx, double ty, int tic) {
        if (target != null && (target.health <= 0 || target.removed || tic >= deadline)) {
            if (target.health > 0) retry = tic + 350;
            target = null;
        }
        if (o.ammo() <= 0 || tic < retry) return null;
        if (target == null) {
            double dx = tx - o.x(), dy = ty - o.y(), length = Math.hypot(dx, dy), nearest = 256;
            if (length < 16) return null;
            for (Thinker thinker = PTick.thinkercap.next; thinker != PTick.thinkercap; thinker = thinker.next)
                if (thinker instanceof Mobj barrel && barrel.type == Info.MT_BARREL && barrel.health > 0) {
                    double bx = barrel.x / 65536. - o.x(), by = barrel.y / 65536. - o.y();
                    double along = (bx * dx + by * dy) / length, across = Math.abs(bx * dy - by * dx) / length;
                    double distance = Math.hypot(bx, by);
                    if (distance < nearest && along > 0 && along < Math.min(length + 32, 224)
                            && across < o.radius() + barrel.radius / 65536. + 8
                            && Math.abs(barrel.z / 65536. - o.z()) < o.height()
                            && Observation.clearance(o.x(), o.y(), barrel.x / 65536., barrel.y / 65536., false) >= .999) {
                        nearest = distance; target = barrel;
                    }
                }
            if (target == null) return null;
            deadline = tic + 280;
        }
        double dx = target.x / 65536. - o.x(), dy = target.y / 65536. - o.y(), distance = Math.hypot(dx, dy);
        double aim = Math.atan2(dy, dx);
        int turn = Math.max(-2048, Math.min(2048, (int) Math.round(LocalController.difference(aim, o.angle()) * 65536 / (4 * Math.PI) / 256) * 256));
        int buttons = distance >= 176 && Math.abs(LocalController.difference(aim, o.angle() + turn * 2 * Math.PI / 65536)) < .15
                ? Doomdef.BT_ATTACK : 0;
        if (o.preferredWeapon() != o.inventory().ready() && o.preferredWeapon() != o.inventory().pending())
            buttons |= Doomdef.BT_CHANGE | o.preferredWeapon() << Doomdef.BT_WEAPONSHIFT;
        MotionProbe probe = new MotionProbe(o, true);
        if (distance < 192) {
            double away = LocalController.difference(aim + Math.PI, o.angle() + turn * 2 * Math.PI / 65536);
            int forward = (int) Math.round(50 * Math.cos(away)), sideways = (int) Math.round(-40 * Math.sin(away));
            for (Action retreat : new Action[]{new Action(forward, sideways, turn, buttons & ~Doomdef.BT_ATTACK),
                    new Action(0, 40, turn, buttons & ~Doomdef.BT_ATTACK), new Action(0, -40, turn, buttons & ~Doomdef.BT_ATTACK)}) {
                if (LocalController.safeMotion(o, probe, retreat, 2)) return retreat;
            }
        }
        return new Action(0, 0, turn, buttons);
    }
}
