package doom.ai;

import doom.play.PSetup;
import java.util.*;

/** Monotonic memory of switch texture changes actually observed after a local Use press. */
final class InteractionMemory {
    private Set<Integer> activated = Set.of();
    private int hash;
    private int lastUsed = -1;

    void reset() { activated = Set.of(); hash = 0; lastUsed = -1; }
    Set<Integer> activated() { return activated; }
    int hash() { return hash; }
    int lastUsed() { return lastUsed; }

    Map<Integer, Long> before(double x, double y, boolean freshUse) {
        lastUsed = -1;
        if (!freshUse) return Map.of();
        Map<Integer, Long> before = new LinkedHashMap<>();
        for (int i = 0; i < PSetup.lines.length; i++)
            if (Observation.distanceToLine(x, y, PSetup.lines[i]) <= 80) before.put(i, texture(i));
        return before;
    }

    void after(Map<Integer, Long> before) {
        for (var old : before.entrySet()) if (old.getValue() != texture(old.getKey())) {
            int line = old.getKey();
            if (lastUsed < 0) lastUsed = line;
            if (!activated.contains(line)) {
                Set<Integer> next = new HashSet<>(activated); next.add(line); activated = Set.copyOf(next);
                int mixed = line + 0x9e3779b9;
                mixed = (mixed ^ (mixed >>> 16)) * 0x85ebca6b;
                mixed = (mixed ^ (mixed >>> 13)) * 0xc2b2ae35;
                hash ^= mixed ^ (mixed >>> 16);
            }
        }
    }

    private static long texture(int line) {
        long h = 0;
        for (int side : PSetup.lines[line].sidenum) if (side >= 0 && side < PSetup.sides.length) {
            var s = PSetup.sides[side];
            h = 31 * h + s.toptexture; h = 31 * h + s.midtexture; h = 31 * h + s.bottomtexture;
        }
        return h;
    }
}
