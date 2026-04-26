// Port of linuxdoom-1.10/p_maputl.c — partial.
//
// p_maputl.c contains:
//   - Pure-math movement/collision helpers (P_AproxDistance,
//     P_PointOnLineSide, P_BoxOnLineSide, P_PointOnDivlineSide,
//     P_MakeDivline, P_InterceptVector, P_LineOpening).
//   - Mobj position linking (P_SetThingPosition / P_UnsetThingPosition).
//   - Blockmap iterators (P_BlockLinesIterator, P_BlockThingsIterator)
//     and the path-traversal scaffolding (PIT_AddLineIntercepts,
//     PIT_AddThingIntercepts, P_TraverseIntercepts, P_PathTraverse).
//
// Pure-math + blockmap-iterator layers ported. P_SetThingPosition /
// P_UnsetThingPosition wait on R_PointInSubsector (r_bsp.c) and on
// Mobj's snext/sprev/subsector/flags fields. The intercept machinery
// (PIT_*Intercepts, PathTraverse) waits on intercept_t + the trace
// globals.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RMain;
import doom.render.Sector;

import java.util.function.Predicate;

public final class PMaputl {

    // Globals set by P_LineOpening (p_maputl.c lines 294–297).
    public static int opentop;     // fixed_t
    public static int openbottom;  // fixed_t
    public static int openrange;   // fixed_t
    public static int lowfloor;    // fixed_t

    private PMaputl() {
    }

    //
    // P_AproxDistance
    // Gives an estimation of distance (not exact).
    //
    public static int pAproxDistance(int dx, int dy) {
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        if (dx < dy) {
            return dx + dy - (dx >> 1);
        }
        return dx + dy - (dy >> 1);
    }

    //
    // P_PointOnLineSide
    // Returns 0 or 1.
    //
    public static int pPointOnLineSide(int x, int y, Line line) {
        int dx;
        int dy;
        int left;
        int right;

        if (line.dx == 0) {
            if (x <= line.v1.x) {
                return line.dy > 0 ? 1 : 0;
            }
            return line.dy < 0 ? 1 : 0;
        }
        if (line.dy == 0) {
            if (y <= line.v1.y) {
                return line.dx < 0 ? 1 : 0;
            }
            return line.dx > 0 ? 1 : 0;
        }

        dx = (x - line.v1.x);
        dy = (y - line.v1.y);

        left = Fixed.mul(line.dy >> Fixed.FRACBITS, dx);
        right = Fixed.mul(dy, line.dx >> Fixed.FRACBITS);

        if (right < left) {
            return 0; // front side
        }
        return 1;     // back side
    }

    //
    // P_BoxOnLineSide
    // Considers the line to be infinite.
    // Returns side 0 or 1, -1 if box crosses the line.
    //
    public static int pBoxOnLineSide(int[] tmbox, Line ld) {
        int p1 = 0;
        int p2 = 0;

        switch (ld.slopetype) {
            case ST_HORIZONTAL:
                p1 = tmbox[MBbox.BOXTOP] > ld.v1.y ? 1 : 0;
                p2 = tmbox[MBbox.BOXBOTTOM] > ld.v1.y ? 1 : 0;
                if (ld.dx < 0) {
                    p1 ^= 1;
                    p2 ^= 1;
                }
                break;

            case ST_VERTICAL:
                p1 = tmbox[MBbox.BOXRIGHT] < ld.v1.x ? 1 : 0;
                p2 = tmbox[MBbox.BOXLEFT] < ld.v1.x ? 1 : 0;
                if (ld.dy < 0) {
                    p1 ^= 1;
                    p2 ^= 1;
                }
                break;

            case ST_POSITIVE:
                p1 = pPointOnLineSide(tmbox[MBbox.BOXLEFT], tmbox[MBbox.BOXTOP], ld);
                p2 = pPointOnLineSide(tmbox[MBbox.BOXRIGHT], tmbox[MBbox.BOXBOTTOM], ld);
                break;

            case ST_NEGATIVE:
                p1 = pPointOnLineSide(tmbox[MBbox.BOXRIGHT], tmbox[MBbox.BOXTOP], ld);
                p2 = pPointOnLineSide(tmbox[MBbox.BOXLEFT], tmbox[MBbox.BOXBOTTOM], ld);
                break;
        }

        if (p1 == p2) {
            return p1;
        }
        return -1;
    }

    //
    // P_PointOnDivlineSide
    // Returns 0 or 1.
    //
    public static int pPointOnDivlineSide(int x, int y, Divline line) {
        int dx;
        int dy;
        int left;
        int right;

        if (line.dx == 0) {
            if (x <= line.x) {
                return line.dy > 0 ? 1 : 0;
            }
            return line.dy < 0 ? 1 : 0;
        }
        if (line.dy == 0) {
            if (y <= line.y) {
                return line.dx < 0 ? 1 : 0;
            }
            return line.dx > 0 ? 1 : 0;
        }

        dx = (x - line.x);
        dy = (y - line.y);

        // try to quickly decide by looking at sign bits
        if (((line.dy ^ line.dx ^ dx ^ dy) & 0x80000000) != 0) {
            if (((line.dy ^ dx) & 0x80000000) != 0) {
                return 1; // (left is negative)
            }
            return 0;
        }

        left = Fixed.mul(line.dy >> 8, dx >> 8);
        right = Fixed.mul(dy >> 8, line.dx >> 8);

        if (right < left) {
            return 0; // front side
        }
        return 1;     // back side
    }

    //
    // P_MakeDivline
    //
    public static void pMakeDivline(Line li, Divline dl) {
        dl.x = li.v1.x;
        dl.y = li.v1.y;
        dl.dx = li.dx;
        dl.dy = li.dy;
    }

    //
    // P_InterceptVector
    // Returns the fractional intercept point along the first divline.
    // This is only called by the addthings and addlines traversers.
    // (The C has an `#if 1` integer path and an `#else` UNUSED float
    // debug path; only the active integer path is ported.)
    //
    public static int pInterceptVector(Divline v2, Divline v1) {
        int frac;
        int num;
        int den;

        den = Fixed.mul(v1.dy >> 8, v2.dx) - Fixed.mul(v1.dx >> 8, v2.dy);

        if (den == 0) {
            return 0;
        }
        // I_Error ("P_InterceptVector: parallel");

        num = Fixed.mul((v1.x - v2.x) >> 8, v1.dy)
            + Fixed.mul((v2.y - v1.y) >> 8, v1.dx);

        frac = Fixed.div(num, den);

        return frac;
    }

    //
    // BLOCK MAP ITERATORS
    // For each line/thing in the given mapblock,
    // call the passed PIT_* function.
    // If the function returns false,
    // exit with false without checking anything else.
    //

    //
    // P_BlockLinesIterator
    // The validcount flags are used to avoid checking lines
    // that are marked in multiple mapblocks,
    // so increment validcount before the first call
    // to P_BlockLinesIterator, then make one or more calls
    // to it.
    //
    // p_maputl.c lines 471–506. The C `boolean(*func)(line_t*)`
    // becomes a `Predicate<Line>`; same return-false-to-stop contract.
    public static boolean pBlockLinesIterator(int x, int y, Predicate<Line> func) {
        if (x < 0
                || y < 0
                || x >= PSetup.bmapwidth
                || y >= PSetup.bmapheight) {
            return true;
        }

        int offset = y * PSetup.bmapwidth + x;

        // C: `offset = *(blockmap+offset)` — i.e. the per-block list
        // pointer. blockmap aliases blockmaplump+4, so reading is via
        // PSetup.blockmaplump[blockmapOffset + offset].
        offset = PSetup.blockmaplump[PSetup.blockmapOffset + offset];

        // Walk the per-block list. Each entry is a line index, list
        // is terminated with -1.
        for (int li = offset; PSetup.blockmaplump[li] != -1; li++) {
            Line ld = PSetup.lines[PSetup.blockmaplump[li]];

            if (ld.validcount == RMain.validcount) {
                continue; // line has already been checked
            }

            ld.validcount = RMain.validcount;

            if (!func.test(ld)) {
                return false;
            }
        }
        return true; // everything was checked
    }

    //
    // P_BlockThingsIterator
    //
    // p_maputl.c lines 510–537.
    public static boolean pBlockThingsIterator(int x, int y, Predicate<Mobj> func) {
        if (x < 0
                || y < 0
                || x >= PSetup.bmapwidth
                || y >= PSetup.bmapheight) {
            return true;
        }

        for (Mobj mobj = PSetup.blocklinks[y * PSetup.bmapwidth + x];
                mobj != null;
                mobj = mobj.bnext) {
            if (!func.test(mobj)) {
                return false;
            }
        }
        return true;
    }

    //
    // P_LineOpening
    // Sets opentop and openbottom to the window through a two sided line.
    // OPTIMIZE: keep this precalculated.
    //
    public static void pLineOpening(Line linedef) {
        Sector front;
        Sector back;

        if (linedef.sidenum[1] == -1) {
            // single sided line
            openrange = 0;
            return;
        }

        front = linedef.frontsector;
        back = linedef.backsector;

        if (front.ceilingheight < back.ceilingheight) {
            opentop = front.ceilingheight;
        } else {
            opentop = back.ceilingheight;
        }

        if (front.floorheight > back.floorheight) {
            openbottom = front.floorheight;
            lowfloor = back.floorheight;
        } else {
            openbottom = back.floorheight;
            lowfloor = front.floorheight;
        }

        openrange = opentop - openbottom;
    }
}
