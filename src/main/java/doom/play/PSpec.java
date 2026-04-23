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

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;

public final class PSpec {

    // p_spec.h lines 602–608 — result_e.
    // Return code for T_MovePlane and its callers. `ok` = move
    // completed normally, `crushed` = move blocked by a mobj, `pastdest`
    // = destination reached this tic.
    public enum Result { ok, crushed, pastdest }

    // p_spec.h line 600 — FLOORSPEED.
    public static final int FLOORSPEED = Fixed.FRACUNIT;

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

    // p_spec.c lines 270–289 — P_FindLowestFloorSurrounding.
    // Lowest floor height among two-sided neighbours, seeded with
    // `sec`'s own floor — so the result is at most `sec->floorheight`.
    public static int pFindLowestFloorSurrounding(Sector sec) {
        int floor = sec.floorheight;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight < floor) {
                floor = other.floorheight;
            }
        }
        return floor;
    }

    // p_spec.c lines 297–316 — P_FindHighestFloorSurrounding.
    // Highest floor height among two-sided neighbours. The seed is
    // -500 * FRACUNIT (well below any legal map floor), so isolated
    // sectors report that sentinel back to the caller. Callers
    // (e.g. EV_DoFloor lowerFloor) treat the sentinel specially.
    public static int pFindHighestFloorSurrounding(Sector sec) {
        int floor = -500 * Fixed.FRACUNIT;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight > floor) {
                floor = other.floorheight;
            }
        }
        return floor;
    }

    // p_spec.c line 326 — MAX_ADJOINING_SECTORS.
    public static final int MAX_ADJOINING_SECTORS = 20;

    // p_spec.c lines 328–375 — P_FindNextHighestFloor.
    // Next floor height strictly above `currentheight` among
    // two-sided neighbours. The C collects candidates into a local
    // fixed array, caps at MAX_ADJOINING_SECTORS with a stderr
    // warning, and returns the min of the list (or `currentheight`
    // when empty — note the C returns the unchanged seed, not -1).
    public static int pFindNextHighestFloor(Sector sec, int currentheight) {
        int[] heightlist = new int[MAX_ADJOINING_SECTORS];
        int h = 0;

        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.floorheight > currentheight) {
                heightlist[h++] = other.floorheight;
            }

            // Check for overflow. Exit.
            if (h >= MAX_ADJOINING_SECTORS) {
                System.err.println("Sector with more than 20 adjoining sectors");
                break;
            }
        }

        // Find lowest height in list
        if (h == 0) {
            return currentheight;
        }

        int min = heightlist[0];

        // Range checking?
        for (int i = 1; i < h; i++) {
            if (heightlist[i] < min) {
                min = heightlist[i];
            }
        }

        return min;
    }

    // p_spec.c lines 381–401 — P_FindLowestCeilingSurrounding.
    // Lowest ceiling among two-sided neighbours. Seeded with MAXINT
    // so that isolated sectors return the sentinel — callers check.
    public static int pFindLowestCeilingSurrounding(Sector sec) {
        int height = Integer.MAX_VALUE;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.ceilingheight < height) {
                height = other.ceilingheight;
            }
        }
        return height;
    }

    // p_spec.c lines 407–426 — P_FindHighestCeilingSurrounding.
    // Highest ceiling among two-sided neighbours. Seeded with 0 —
    // isolated sectors return 0 (floor of the map).
    public static int pFindHighestCeilingSurrounding(Sector sec) {
        int height = 0;
        for (int i = 0; i < sec.linecount; i++) {
            Line check = sec.lines[i];
            Sector other = getNextSector(check, sec);

            if (other == null) {
                continue;
            }

            if (other.ceilingheight > height) {
                height = other.ceilingheight;
            }
        }
        return height;
    }

    private PSpec() {
    }
}
