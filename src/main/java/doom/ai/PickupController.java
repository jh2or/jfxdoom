package doom.ai;

import doom.play.*;
import doom.system.Doomdef;
import java.util.IdentityHashMap;

/** Short local pickup detours with a deadline and cooldown for failed approaches. */
final class PickupController {
    private Mobj target;
    private final boolean survival;
    private int windowStart, spent, lastTic;
    PickupController() { this(false); }
    PickupController(boolean survival) { this.survival = survival; }
    private int deadline;
    private final IdentityHashMap<Mobj, Integer> avoidUntil = new IdentityHashMap<>();
    void reset() { target = null; avoidUntil.clear(); windowStart = spent = lastTic = 0; }

    Mobj choose(Observation o, MotionProbe probe, double tx, double ty, int tic) {
        if (survival) {
            if (tic - windowStart >= 2100) { windowStart = tic; spent = 0; }
            if (target != null && !key(target)) spent += Math.max(0, Math.min(4, tic - lastTic));
            lastTic = tic;
            // Do not pursue supplies through an active fight. Limit non-key detours to 12 s/minute.
            if (o.enemy() != null) { target = null; return null; }
        }
        avoidUntil.entrySet().removeIf(e -> e.getValue() <= tic || !o.pickups().contains(e.getKey()));
        if (target != null && (!o.pickups().contains(target) || benefit(o, target) <= 0)) target = null;
        if (target != null && tic >= deadline) { avoidUntil.put(target, tic + 210); target = null; }
        if (survival && spent >= 420 && target != null && !key(target)) target = null;
        if (target != null && reachable(o, probe, target)) return target;
        target = null;
        double best = 0, routeDistance = Math.hypot(tx - o.x(), ty - o.y());
        for (Mobj item : o.pickups()) {
            double benefit = benefit(o, item);
            if (survival && spent >= 420 && !key(item)) continue;
            if (benefit <= 0 || avoidUntil.containsKey(item) || !reachable(o, probe, item)) continue;
            double x = item.x / 65536.0, y = item.y / 65536.0;
            double d = Math.hypot(x - o.x(), y - o.y());
            double detour = d + Math.hypot(tx - x, ty - y) - routeDistance;
            if (detour > (key(item) ? 1024 : weaponUpgrade(o, item) ? 384 : survival && usefulReserve(o, item) ? 192 : 128) || ((item.sprite == Info.SPR_BON2 || item.sprite == Info.SPR_BON1) && d > 32)) continue;
            double score = benefit / (24 + d + detour);
            if (score > best) { best = score; target = item; }
        }
        if (target != null) deadline = tic + 105;
        return target;
    }

    private boolean reachable(Observation o, MotionProbe probe, Mobj item) {
        double x = item.x / 65536.0, y = item.y / 65536.0;
        double d = Math.hypot(x - o.x(), y - o.y());
        double local = Math.min(d, 256), scale = d < .001 ? 0 : local / d;
        return d <= (key(item) ? 512 : weaponUpgrade(o, item) || survival && usefulReserve(o, item) ? 192 : 128) && Math.abs(item.z / 65536.0 - o.z()) <= 24
                && Observation.clearance(o.x(), o.y(), x, y, false) > .99
                && probe.distance(o.x() + (x - o.x()) * scale, o.y() + (y - o.y()) * scale)
                    >= local - (d <= 256 ? o.radius() + item.radius / 65536.0 - 4 : .01);
    }

    private boolean usefulReserve(Observation o, Mobj item) {
        return switch (item.sprite) {
            case Info.SPR_STIM, Info.SPR_MEDI -> o.health() < 85;
            case Info.SPR_SOUL -> o.health() < 150;
            case Info.SPR_ARM1, Info.SPR_ARM2 -> o.inventory().armor() < 100 && value(o, item) > 0;
            case Info.SPR_SHEL, Info.SPR_SBOX -> o.inventory().shells() < 12 && value(o, item) > 0;
            default -> false;
        };
    }

    private double benefit(Observation o, Mobj item) {
        if (survival && item.sprite == Info.SPR_BON1 && (item.flags & Mobj.MF_SPECIAL) != 0)
            return o.health() < 100 ? 2 : 0;
        double value = value(o, item);
        return survival && usefulReserve(o, item) ? value * 1.5 : value;
    }

    static boolean key(Mobj item) {
        return switch (item.sprite) {
            case Info.SPR_BKEY, Info.SPR_YKEY, Info.SPR_RKEY, Info.SPR_BSKU, Info.SPR_YSKU, Info.SPR_RSKU -> true;
            default -> false;
        };
    }

    private static boolean weaponUpgrade(Observation o, Mobj item) {
        return switch (item.sprite) {
            case Info.SPR_SHOT -> !Inventory.owns(o.weapons(), Doomdef.wp_shotgun);
            case Info.SPR_MGUN -> !Inventory.owns(o.weapons(), Doomdef.wp_chaingun);
            case Info.SPR_PLAS -> !Inventory.owns(o.weapons(), Doomdef.wp_plasma);
            default -> false;
        };
    }

    static double value(Observation o, Mobj item) {
        if ((item.flags & Mobj.MF_SPECIAL) == 0) return 0;
        Inventory inv = o.inventory();
        boolean shotgun = Inventory.owns(o.weapons(), Doomdef.wp_shotgun);
        return switch (item.sprite) {
            case Info.SPR_BKEY -> (o.keys() & (1 << Doomdef.it_bluecard)) == 0 ? 250 : 0;
            case Info.SPR_YKEY -> (o.keys() & (1 << Doomdef.it_yellowcard)) == 0 ? 250 : 0;
            case Info.SPR_RKEY -> (o.keys() & (1 << Doomdef.it_redcard)) == 0 ? 250 : 0;
            case Info.SPR_BSKU -> (o.keys() & (1 << Doomdef.it_blueskull)) == 0 ? 250 : 0;
            case Info.SPR_YSKU -> (o.keys() & (1 << Doomdef.it_yellowskull)) == 0 ? 250 : 0;
            case Info.SPR_RSKU -> (o.keys() & (1 << Doomdef.it_redskull)) == 0 ? 250 : 0;
            case Info.SPR_STIM -> o.health() < 100 ? 40 + (100 - o.health()) : 0;
            case Info.SPR_MEDI -> o.health() < 100 ? 50 + (100 - o.health()) : 0;
            case Info.SPR_SOUL -> o.health() < 200 ? 120 : 0;
            case Info.SPR_ARM1 -> inv.armor() < 100 && (inv.armorType() < 2 || inv.armor() < 50) ? 100 - inv.armor() : 0;
            case Info.SPR_ARM2 -> inv.armor() < 200 ? 160 - inv.armor() * .5 : 0;
            case Info.SPR_BON2 -> inv.armor() < 200 ? 2 : 0;
            case Info.SPR_SHOT -> !shotgun ? 180 : inv.shells() < 12 ? 60 : 0;
            case Info.SPR_MGUN -> !Inventory.owns(o.weapons(), Doomdef.wp_chaingun) ? 100 : inv.bullets() < 20 ? 40 : 0;
            case Info.SPR_PLAS -> !Inventory.owns(o.weapons(), Doomdef.wp_plasma) ? 150 : inv.cells() < 20 ? 60 : 0;
            case Info.SPR_SHEL, Info.SPR_SBOX -> shotgun && inv.shells() < 12 ? 70 : 0;
            case Info.SPR_CLIP, Info.SPR_AMMO -> inv.bullets() < 20 ? 40 : 0;
            case Info.SPR_CELL, Info.SPR_CELP -> Inventory.owns(o.weapons(), Doomdef.wp_plasma) && inv.cells() < 20 ? 60 : 0;
            default -> 0;
        };
    }
}
