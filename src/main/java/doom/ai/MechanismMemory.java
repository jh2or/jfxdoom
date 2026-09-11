package doom.ai;

import doom.play.PSetup;
import doom.render.Sector;
import java.util.*;

/** Remembers locally observed floor/ceiling movement, including walk-triggered mechanisms. */
final class MechanismMemory {
    private int[] floors, ceilings;
    private Set<Integer> observed = Set.of();

    void reset() {
        floors = new int[PSetup.sectors.length]; ceilings = new int[floors.length]; observed = Set.of();
        for (int i = 0; i < floors.length; i++) {
            floors[i] = PSetup.sectors[i].floorheight; ceilings[i] = PSetup.sectors[i].ceilingheight;
        }
    }
    Set<Integer> observed() { return observed; }
    void observe(double x, double y) {
        for (int i = 0; i < floors.length; i++) {
            Sector sector = PSetup.sectors[i];
            int df = Integer.compare(sector.floorheight, floors[i]), dc = Integer.compare(sector.ceilingheight, ceilings[i]);
            if ((df != 0 || dc != 0) && nearby(sector, x, y)) {
                if (df != 0) add(4 * i + (df > 0 ? 0 : 1));
                if (dc != 0) add(4 * i + (dc > 0 ? 2 : 3));
            }
            floors[i] = sector.floorheight; ceilings[i] = sector.ceilingheight;
        }
    }
    private void add(int token) {
        if (!observed.contains(token)) { Set<Integer> next = new HashSet<>(observed); next.add(token); observed = Set.copyOf(next); }
    }
    private static boolean nearby(Sector sector, double x, double y) {
        for (var line : sector.lines) if (Observation.distanceToLine(x, y, line) < 512) return true;
        return false;
    }
}
