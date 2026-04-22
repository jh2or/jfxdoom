// Port of linuxdoom-1.10/p_spec.c — partial.
//
// p_spec.c is the 1300-line "sector specials" module: it handles
// animated textures, line-triggered events (teleport, lights, doors,
// floors, ...), button timers, and per-tic special updates. The full
// body lands incrementally alongside its companion files (p_doors,
// p_floor, p_plats, ...). For now this carries only the tiny
// sector-graph walkers other ported modules need — p_lights.c spawn
// helpers in particular.
package doom.play;

import doom.render.Line;
import doom.render.Sector;

public final class PSpec {

    // p_spec.c lines 250–262 — getNextSector.
    // Return the sector on the other side of a two-sided line.
    // Null for one-sided lines (ML_TWOSIDED not set).
    public static Sector getNextSector(Line line, Sector sec) {
        if ((line.flags & Line.ML_TWOSIDED) == 0) {
            return null;
        }

        if (line.frontsector == sec) {
            return line.backsector;
        }

        return line.frontsector;
    }

    // p_spec.c lines 434–445 — P_FindSectorFromLineTag.
    // Returns the next sector index in `sectors[]` whose tag matches
    // line->tag, starting the search at start+1. Returns -1 when no
    // more matches remain.
    public static int pFindSectorFromLineTag(Line line, int start) {
        for (int i = start + 1; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i].tag == line.tag) {
                return i;
            }
        }

        return -1;
    }

    // p_spec.c lines 454–476 — P_FindMinSurroundingLight.
    // Minimum light level across `sector`'s adjacent sectors, or
    // `max` if none of them are darker.
    public static int pFindMinSurroundingLight(Sector sector, int max) {
        int min = max;
        for (int i = 0; i < sector.linecount; i++) {
            Line line = sector.lines[i];
            Sector check = getNextSector(line, sector);

            if (check == null) {
                continue;
            }

            if (check.lightlevel < min) {
                min = check.lightlevel;
            }
        }
        return min;
    }

    private PSpec() {
    }
}
