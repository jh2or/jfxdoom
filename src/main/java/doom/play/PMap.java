// Port of linuxdoom-1.10/p_map.c — partial.
//
// Movement-and-collision core: P_CheckPosition + P_TryMove + the two
// PIT_* iterator callbacks they drive.
//
// Deferred (each waits on its named module):
//   - PIT_StompThing / P_TeleportMove (telept)        → p_telept full
//   - PIT_CheckThing skull/missile/pickup branches     → p_inter
//   - P_ThingHeightClip / P_HitSlideLine / P_SlideMove → slide port
//   - P_AimLineAttack / P_LineAttack / P_UseLines     → combat port
//   - PIT_RadiusAttack / P_RadiusAttack                → combat port
//   - P_ChangeSector / PIT_ChangeSector real bodies   → keep stub
//
// PIT_CheckThing's skull/missile/pickup paths read fields not yet
// declared on Mobj (info, target) and call P_DamageMobj /
// P_SetMobjState / P_TouchSpecialThing — none ported. The minimal
// implementation here handles only the basic body-collision case
// (radius vs. radius, plus MF_SOLID / MF_SHOOTABLE / MF_SPECIAL).
// Everything else is captured in TODO comments at the deferred
// branches.
//
// The C `tmthing`, `tmflags`, `tmx`, `tmy`, `tmbbox[4]`, `tmfloorz`,
// `tmceilingz`, `tmdropoffz`, `floatok`, `ceilingline`, `spechit[8]`,
// `numspechit` globals all live as `public static` fields here. The
// playsim only ever has one P_CheckPosition / P_TryMove call in
// flight, so the global-state pattern carries over verbatim.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RMain;
import doom.render.Sector;
import doom.render.Subsector;

public final class PMap {

    // p_map.c lines 46–70 — movement-clip globals.
    public static int[] tmbbox = new int[4];
    public static Mobj tmthing;
    public static int tmflags;
    public static int tmx; // fixed_t
    public static int tmy; // fixed_t

    // If `floatok` true, move would be ok if within
    // `tmfloorz - tmceilingz`.
    public static boolean floatok;

    public static int tmfloorz;   // fixed_t
    public static int tmceilingz; // fixed_t
    public static int tmdropoffz; // fixed_t

    // keep track of the line that lowers the ceiling, so missiles
    // don't explode against sky hack walls.
    public static Line ceilingline;

    // p_map.c line 67 — keep track of special lines as they are hit,
    // but don't process them until the move is proven valid.
    public static final int MAXSPECIALCROSS = 8;
    public static final Line[] spechit = new Line[MAXSPECIALCROSS];
    public static int numspechit;

    // ----- LEGACY P_ChangeSector STUB (deviation 21) -----
    //
    // The real P_ChangeSector drives PIT_ChangeSector which depends
    // on P_DamageMobj + P_SpawnMobj + mobj.health/info — all unported.
    // T_MovePlane (PFloor) reads pChangeSector(); the test override
    // pattern stays in place until the real body lands.
    public static boolean changeSectorResult = false;

    public static boolean pChangeSector(Sector sector, boolean crunch) {
        return changeSectorResult;
    }

    private PMap() {
    }

    //
    // PIT_CheckLine
    // Adjusts tmfloorz and tmceilingz as lines are contacted.
    //
    // p_map.c lines 189–247.
    public static boolean pitCheckLine(Line ld) {
        if (tmbbox[MBbox.BOXRIGHT] <= ld.bbox[MBbox.BOXLEFT]
                || tmbbox[MBbox.BOXLEFT] >= ld.bbox[MBbox.BOXRIGHT]
                || tmbbox[MBbox.BOXTOP] <= ld.bbox[MBbox.BOXBOTTOM]
                || tmbbox[MBbox.BOXBOTTOM] >= ld.bbox[MBbox.BOXTOP]) {
            return true;
        }

        if (PMaputl.pBoxOnLineSide(tmbbox, ld) != -1) {
            return true;
        }

        // A line has been hit.
        // The moving thing's destination position will cross the
        // given line. If this should not be allowed, return false.
        // If the line is special, keep track of it to process later
        // if the move is proven ok.
        // NOTE: specials are NOT sorted by order, so two special
        // lines that are only 8 pixels apart could be crossed in
        // either order.

        if (ld.backsector == null) {
            return false; // one sided line
        }

        if ((tmthing.flags & Mobj.MF_MISSILE) == 0) {
            if ((ld.flags & Line.ML_BLOCKING) != 0) {
                return false; // explicitly blocking everything
            }

            if (tmthing.player == null
                    && (ld.flags & Line.ML_BLOCKMONSTERS) != 0) {
                return false; // block monsters only
            }
        }

        // set openrange, opentop, openbottom
        PMaputl.pLineOpening(ld);

        // adjust floor / ceiling heights
        if (PMaputl.opentop < tmceilingz) {
            tmceilingz = PMaputl.opentop;
            ceilingline = ld;
        }

        if (PMaputl.openbottom > tmfloorz) {
            tmfloorz = PMaputl.openbottom;
        }

        if (PMaputl.lowfloor < tmdropoffz) {
            tmdropoffz = PMaputl.lowfloor;
        }

        // if contacted a special line, add it to the list
        if (ld.special != 0) {
            spechit[numspechit] = ld;
            numspechit++;
        }

        return true;
    }

    //
    // PIT_CheckThing
    //
    // p_map.c lines 252–343. Minimal port: only the early-out (no
    // collision interest), the radius/self short-circuits, and the
    // final MF_SOLID return. The skull/missile/pickup branches need
    // p_inter.c (P_DamageMobj, P_TouchSpecialThing) and p_mobj.c
    // (P_SetMobjState, mobj.info, mobj.target) — deferred.
    public static boolean pitCheckThing(Mobj thing) {
        int blockdist;

        if ((thing.flags & (Mobj.MF_SOLID | Mobj.MF_SPECIAL | Mobj.MF_SHOOTABLE)) == 0) {
            return true;
        }

        blockdist = thing.radius + tmthing.radius;

        if (Math.abs(thing.x - tmx) >= blockdist
                || Math.abs(thing.y - tmy) >= blockdist) {
            // didn't hit it
            return true;
        }

        // don't clip against self
        if (thing == tmthing) {
            return true;
        }

        // TODO(p_inter): SKULLFLY/MISSILE/SPECIAL branches. Those
        // need P_DamageMobj / P_TouchSpecialThing / P_SetMobjState
        // and the mobj.info/target fields. The skipped logic from
        // p_map.c lines 274–340 lands when those modules port.

        return (thing.flags & Mobj.MF_SOLID) == 0;
    }

    //
    // P_CheckPosition
    // This is purely informative, nothing is modified (except
    // things picked up — which won't happen until the deferred
    // PIT_CheckThing branches port).
    //
    // p_map.c lines 374–442.
    public static boolean pCheckPosition(Mobj thing, int x, int y) {
        int xl;
        int xh;
        int yl;
        int yh;
        int bx;
        int by;
        Subsector newsubsec;

        tmthing = thing;
        tmflags = thing.flags;

        tmx = x;
        tmy = y;

        tmbbox[MBbox.BOXTOP] = y + tmthing.radius;
        tmbbox[MBbox.BOXBOTTOM] = y - tmthing.radius;
        tmbbox[MBbox.BOXRIGHT] = x + tmthing.radius;
        tmbbox[MBbox.BOXLEFT] = x - tmthing.radius;

        newsubsec = RMain.rPointInSubsector(x, y);
        ceilingline = null;

        // The base floor / ceiling is from the subsector that
        // contains the point. Any contacted lines the step closer
        // together will adjust them.
        tmfloorz = tmdropoffz = newsubsec.sector.floorheight;
        tmceilingz = newsubsec.sector.ceilingheight;

        RMain.validcount++;
        numspechit = 0;

        if ((tmflags & Mobj.MF_NOCLIP) != 0) {
            return true;
        }

        // p_local.h: MAXRADIUS = 32*FRACUNIT, MAPBLOCKSHIFT = FRACBITS+7.
        final int MAXRADIUS = 32 * Fixed.FRACUNIT;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;

        // Check things first, possibly picking things up. The
        // bounding box is extended by MAXRADIUS because mobj_ts are
        // grouped into mapblocks based on their origin point, and
        // can overlap into adjacent blocks by up to MAXRADIUS units.
        xl = (tmbbox[MBbox.BOXLEFT] - PSetup.bmaporgx - MAXRADIUS) >> MAPBLOCKSHIFT;
        xh = (tmbbox[MBbox.BOXRIGHT] - PSetup.bmaporgx + MAXRADIUS) >> MAPBLOCKSHIFT;
        yl = (tmbbox[MBbox.BOXBOTTOM] - PSetup.bmaporgy - MAXRADIUS) >> MAPBLOCKSHIFT;
        yh = (tmbbox[MBbox.BOXTOP] - PSetup.bmaporgy + MAXRADIUS) >> MAPBLOCKSHIFT;

        for (bx = xl; bx <= xh; bx++) {
            for (by = yl; by <= yh; by++) {
                if (!PMaputl.pBlockThingsIterator(bx, by, PMap::pitCheckThing)) {
                    return false;
                }
            }
        }

        // check lines
        xl = (tmbbox[MBbox.BOXLEFT] - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        xh = (tmbbox[MBbox.BOXRIGHT] - PSetup.bmaporgx) >> MAPBLOCKSHIFT;
        yl = (tmbbox[MBbox.BOXBOTTOM] - PSetup.bmaporgy) >> MAPBLOCKSHIFT;
        yh = (tmbbox[MBbox.BOXTOP] - PSetup.bmaporgy) >> MAPBLOCKSHIFT;

        for (bx = xl; bx <= xh; bx++) {
            for (by = yl; by <= yh; by++) {
                if (!PMaputl.pBlockLinesIterator(bx, by, PMap::pitCheckLine)) {
                    return false;
                }
            }
        }

        return true;
    }

    //
    // P_TryMove
    // Attempt to move to a new position, crossing special lines
    // unless MF_TELEPORT is set.
    //
    // p_map.c lines 451–517.
    public static boolean pTryMove(Mobj thing, int x, int y) {
        int oldx;
        int oldy;
        int side;
        int oldside;
        Line ld;

        floatok = false;
        if (!pCheckPosition(thing, x, y)) {
            return false; // solid wall or thing
        }

        if ((thing.flags & Mobj.MF_NOCLIP) == 0) {
            if (tmceilingz - tmfloorz < thing.height) {
                return false; // doesn't fit
            }

            floatok = true;

            if ((thing.flags & Mobj.MF_TELEPORT) == 0
                    && tmceilingz - thing.z < thing.height) {
                return false; // mobj must lower itself to fit
            }

            if ((thing.flags & Mobj.MF_TELEPORT) == 0
                    && tmfloorz - thing.z > 24 * Fixed.FRACUNIT) {
                return false; // too big a step up
            }

            if ((thing.flags & (Mobj.MF_DROPOFF | Mobj.MF_FLOAT)) == 0
                    && tmfloorz - tmdropoffz > 24 * Fixed.FRACUNIT) {
                return false; // don't stand over a dropoff
            }
        }

        // the move is ok, so link the thing into its new position
        PMaputl.pUnsetThingPosition(thing);

        oldx = thing.x;
        oldy = thing.y;
        thing.floorz = tmfloorz;
        thing.ceilingz = tmceilingz;
        thing.x = x;
        thing.y = y;

        PMaputl.pSetThingPosition(thing);

        // if any special lines were hit, do the effect
        if ((thing.flags & (Mobj.MF_TELEPORT | Mobj.MF_NOCLIP)) == 0) {
            while (numspechit > 0) {
                numspechit--;
                // see if the line was crossed
                ld = spechit[numspechit];
                side = PMaputl.pPointOnLineSide(thing.x, thing.y, ld);
                oldside = PMaputl.pPointOnLineSide(oldx, oldy, ld);
                if (side != oldside) {
                    if (ld.special != 0) {
                        PSpec.pCrossSpecialLine(lineIndex(ld), oldside, thing);
                    }
                }
            }
        }

        return true;
    }

    // The C uses pointer arithmetic `ld - lines` to recover the line
    // index. PSetup.lines is a typed array so we walk it. Linear scan
    // is acceptable (numspechit ≤ 8 per move, numlines a few hundred).
    private static int lineIndex(Line ld) {
        for (int i = 0; i < PSetup.numlines; i++) {
            if (PSetup.lines[i] == ld) {
                return i;
            }
        }
        return -1;
    }
}
