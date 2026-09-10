package doom.ai;

import doom.play.*;
import doom.render.*;
import doom.system.GGame;
import java.util.IdentityHashMap;

/** Diagnostic hash of simulation state, not a serialization or a proof of engine equivalence. */
public final class Fingerprint {
    private long hash = 0xcbf29ce484222325L;
    private static final IdentityHashMap<State, Integer> STATES = new IdentityHashMap<>();
    static { for (int i = 0; i < Info.states.length; i++) STATES.put(Info.states[i], i); }
    private static final ClassValue<java.lang.reflect.Field[]> MOVER_FIELDS = new ClassValue<>() {
        @Override protected java.lang.reflect.Field[] computeValue(Class<?> type) {
            return java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && (f.getType().isPrimitive() || f.getType() == Sector.class || f.getType().isEnum()))
                    .sorted(java.util.Comparator.comparing(java.lang.reflect.Field::getName))
                    .toArray(java.lang.reflect.Field[]::new);
        }
    };
    private void add(long value) { hash = (hash ^ value) * 0x100000001b3L; }

    public static long capture() {
        Fingerprint h = new Fingerprint();
        h.add(PTick.gametic); h.add(PTick.leveltime); h.add(MRandom.prndindex);
        // Presentation uses the other RNG; renderer-dependent UI randomness is excluded.
        h.add(GGame.gameaction);
        Player p = GGame.players[0];
        h.add(p.health); h.add(p.armorpoints); h.add(p.armortype); h.add(p.playerstate);
        h.add(p.readyweapon); h.add(p.pendingweapon); h.add(p.attackdown); h.add(p.usedown);
        h.add(p.refire); h.add(p.killcount); h.add(p.itemcount); h.add(p.secretcount);
        for (int a : p.ammo) h.add(a);
        for (int a : p.powers) h.add(a);
        for (boolean a : p.cards) h.add(a ? 1 : 0);
        for (boolean a : p.weaponowned) h.add(a ? 1 : 0);
        for (Pspdef ps : p.psprites) {
            h.add(STATES.getOrDefault(ps.state, -1)); h.add(ps.tics); h.add(ps.sx); h.add(ps.sy);
        }
        IdentityHashMap<Mobj, Integer> actors = new IdentityHashMap<>();
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            if (t instanceof Mobj mo) actors.put(mo, actors.size());
        }
        IdentityHashMap<Sector, Integer> sectors = new IdentityHashMap<>();
        for (int i = 0; i < PSetup.sectors.length; i++) sectors.put(PSetup.sectors[i], i);
        for (Thinker t = PTick.thinkercap.next; t != PTick.thinkercap; t = t.next) {
            h.add(t.getClass().getName().hashCode()); h.add(t.removed ? 1 : 0);
            if (t instanceof Mobj mo) {
                h.add(mo.type); h.add(mo.x); h.add(mo.y); h.add(mo.z); h.add(mo.angle);
                h.add(mo.momx); h.add(mo.momy); h.add(mo.momz); h.add(mo.health);
                h.add(mo.flags); h.add(mo.tics); h.add(STATES.getOrDefault(mo.state, -1));
                h.add(mo.reactiontime); h.add(mo.threshold); h.add(mo.movedir); h.add(mo.movecount);
                h.add(mo.lastlook); h.add(actors.getOrDefault(mo.target, -1));
                h.add(actors.getOrDefault(mo.tracer, -1));
            } else {
                // Movers and lights have countdown/direction state that sector heights alone miss.
                for (java.lang.reflect.Field field : MOVER_FIELDS.get(t.getClass())) {
                    try {
                        Object value = field.get(t);
                        if (value instanceof Number number) h.add(number.longValue());
                        else if (value instanceof Boolean flag) h.add(flag ? 1 : 0);
                        else if (value instanceof Sector sector) h.add(sectors.getOrDefault(sector, -1));
                        else if (value instanceof Enum<?> enumeration) h.add(enumeration.ordinal());
                        else h.add(-1);
                    } catch (IllegalAccessException e) { throw new IllegalStateException("Unreadable thinker state", e); }
                }
            }
        }
        for (Sector s : PSetup.sectors) {
            h.add(s.floorheight); h.add(s.ceilingheight); h.add(s.special); h.add(s.lightlevel);
            h.add(actors.getOrDefault(s.soundtarget, -1));
        }
        for (Line l : PSetup.lines) h.add(l.special);
        for (Side s : PSetup.sides) { h.add(s.toptexture); h.add(s.midtexture); h.add(s.bottomtexture); }
        return h.hash;
    }
}
