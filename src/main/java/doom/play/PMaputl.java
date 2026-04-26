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

    //
    // INTERCEPT ROUTINES
    //
    // p_local.h MAXINTERCEPTS = 128.
    public static final int MAXINTERCEPTS = 128;

    // p_local.h PT_* path-traverse flag bits.
    public static final int PT_ADDLINES   = 1;
    public static final int PT_ADDTHINGS  = 2;
    public static final int PT_EARLYOUT   = 4;

    // p_maputl.c lines 544–549.
    // Pre-allocated to avoid per-call array churn; reused across
    // PathTraverse calls. intercept_p tracks the next free slot
    // (insertion index, in C the next-free pointer).
    public static final Intercept[] intercepts = new Intercept[MAXINTERCEPTS];
    static {
        for (int i = 0; i < MAXINTERCEPTS; i++) {
            intercepts[i] = new Intercept();
        }
    }
    public static int intercept_p;

    // p_maputl.c lines 547–549 — PathTraverse globals.
    public static final Divline trace = new Divline();
    public static boolean earlyout;
    public static int ptflags;

    //
    // PIT_AddLineIntercepts.
    // Looks for lines in the given block that intercept the given
    // trace, adding them to the intercepts list. A line is crossed if
    // its endpoints are on opposite sides of the trace. Returns true
    // if earlyout and a solid line hit.
    //
    // p_maputl.c lines 561–609.
    public static boolean pitAddLineIntercepts(Line ld) {
        int s1;
        int s2;
        int frac;
        Divline dl = new Divline();

        // avoid precision problems with two routines
        if (trace.dx > Fixed.FRACUNIT * 16
                || trace.dy > Fixed.FRACUNIT * 16
                || trace.dx < -Fixed.FRACUNIT * 16
                || trace.dy < -Fixed.FRACUNIT * 16) {
            s1 = pPointOnDivlineSide(ld.v1.x, ld.v1.y, trace);
            s2 = pPointOnDivlineSide(ld.v2.x, ld.v2.y, trace);
        } else {
            s1 = pPointOnLineSide(trace.x, trace.y, ld);
            s2 = pPointOnLineSide(trace.x + trace.dx, trace.y + trace.dy, ld);
        }

        if (s1 == s2) {
            return true; // line isn't crossed
        }

        // hit the line
        pMakeDivline(ld, dl);
        frac = pInterceptVector(trace, dl);

        if (frac < 0) {
            return true; // behind source
        }

        // try to early out the check
        if (earlyout
                && frac < Fixed.FRACUNIT
                && ld.backsector == null) {
            return false; // stop checking
        }

        Intercept slot = intercepts[intercept_p];
        slot.frac = frac;
        slot.isaline = true;
        slot.line = ld;
        slot.thing = null;
        intercept_p++;

        return true; // continue
    }

    //
    // PIT_AddThingIntercepts
    //
    // p_maputl.c lines 616–674.
    public static boolean pitAddThingIntercepts(Mobj thing) {
        int x1;
        int y1;
        int x2;
        int y2;

        int s1;
        int s2;

        boolean tracepositive;

        Divline dl = new Divline();

        int frac;

        tracepositive = (trace.dx ^ trace.dy) > 0;

        // check a corner to corner crossection for hit
        if (tracepositive) {
            x1 = thing.x - thing.radius;
            y1 = thing.y + thing.radius;

            x2 = thing.x + thing.radius;
            y2 = thing.y - thing.radius;
        } else {
            x1 = thing.x - thing.radius;
            y1 = thing.y - thing.radius;

            x2 = thing.x + thing.radius;
            y2 = thing.y + thing.radius;
        }

        s1 = pPointOnDivlineSide(x1, y1, trace);
        s2 = pPointOnDivlineSide(x2, y2, trace);

        if (s1 == s2) {
            return true; // line isn't crossed
        }

        dl.x = x1;
        dl.y = y1;
        dl.dx = x2 - x1;
        dl.dy = y2 - y1;

        frac = pInterceptVector(trace, dl);

        if (frac < 0) {
            return true; // behind source
        }

        Intercept slot = intercepts[intercept_p];
        slot.frac = frac;
        slot.isaline = false;
        slot.thing = thing;
        slot.line = null;
        intercept_p++;

        return true; // keep going
    }

    //
    // P_TraverseIntercepts
    // Returns true if the traverser function returns true for all
    // intercepts in range.
    //
    // p_maputl.c lines 682–730.
    public static boolean pTraverseIntercepts(Predicate<Intercept> func, int maxfrac) {
        int count;
        int dist;
        Intercept in;

        count = intercept_p;

        in = null; // shut up compiler warning

        while (count-- > 0) {
            dist = Integer.MAX_VALUE;
            for (int s = 0; s < intercept_p; s++) {
                Intercept scan = intercepts[s];
                if (scan.frac < dist) {
                    dist = scan.frac;
                    in = scan;
                }
            }

            if (dist > maxfrac) {
                return true; // checked everything in range
            }

            // #if 0  // UNUSED
            //   {
            //     // don't check these yet, there may be others inserted
            //     ...
            //   }
            // #endif

            if (!func.test(in)) {
                return false; // don't bother going farther
            }

            in.frac = Integer.MAX_VALUE;
        }

        return true; // everything was traversed
    }

    //
    // P_PathTraverse
    // Traces a line from x1,y1 to x2,y2, calling the traverser
    // function for each intercept. Returns true if the traverser
    // function returns true for all lines.
    //
    // p_maputl.c lines 742–880.
    public static boolean pPathTraverse(int x1, int y1, int x2, int y2,
                                        int flags, Predicate<Intercept> trav) {
        int xt1;
        int yt1;
        int xt2;
        int yt2;

        int xstep;
        int ystep;

        int partial;

        int xintercept;
        int yintercept;

        int mapx;
        int mapy;

        int mapxstep;
        int mapystep;

        int count;

        // p_local.h: MAPBLOCKSIZE = MAPBLOCKUNITS<<FRACBITS = 128<<16,
        // MAPBLOCKSHIFT = FRACBITS+7, MAPBTOFRAC = MAPBLOCKSHIFT-FRACBITS.
        final int MAPBLOCKSIZE = 128 << Fixed.FRACBITS;
        final int MAPBLOCKSHIFT = Fixed.FRACBITS + 7;
        final int MAPBTOFRAC = MAPBLOCKSHIFT - Fixed.FRACBITS;

        earlyout = (flags & PT_EARLYOUT) != 0;

        doom.render.RMain.validcount++;
        intercept_p = 0;

        if (((x1 - PSetup.bmaporgx) & (MAPBLOCKSIZE - 1)) == 0) {
            x1 += Fixed.FRACUNIT; // don't side exactly on a line
        }

        if (((y1 - PSetup.bmaporgy) & (MAPBLOCKSIZE - 1)) == 0) {
            y1 += Fixed.FRACUNIT; // don't side exactly on a line
        }

        trace.x = x1;
        trace.y = y1;
        trace.dx = x2 - x1;
        trace.dy = y2 - y1;

        x1 -= PSetup.bmaporgx;
        y1 -= PSetup.bmaporgy;
        xt1 = x1 >> MAPBLOCKSHIFT;
        yt1 = y1 >> MAPBLOCKSHIFT;

        x2 -= PSetup.bmaporgx;
        y2 -= PSetup.bmaporgy;
        xt2 = x2 >> MAPBLOCKSHIFT;
        yt2 = y2 >> MAPBLOCKSHIFT;

        if (xt2 > xt1) {
            mapxstep = 1;
            partial = Fixed.FRACUNIT - ((x1 >> MAPBTOFRAC) & (Fixed.FRACUNIT - 1));
            ystep = Fixed.div(y2 - y1, Math.abs(x2 - x1));
        } else if (xt2 < xt1) {
            mapxstep = -1;
            partial = (x1 >> MAPBTOFRAC) & (Fixed.FRACUNIT - 1);
            ystep = Fixed.div(y2 - y1, Math.abs(x2 - x1));
        } else {
            mapxstep = 0;
            partial = Fixed.FRACUNIT;
            ystep = 256 * Fixed.FRACUNIT;
        }

        yintercept = (y1 >> MAPBTOFRAC) + Fixed.mul(partial, ystep);

        if (yt2 > yt1) {
            mapystep = 1;
            partial = Fixed.FRACUNIT - ((y1 >> MAPBTOFRAC) & (Fixed.FRACUNIT - 1));
            xstep = Fixed.div(x2 - x1, Math.abs(y2 - y1));
        } else if (yt2 < yt1) {
            mapystep = -1;
            partial = (y1 >> MAPBTOFRAC) & (Fixed.FRACUNIT - 1);
            xstep = Fixed.div(x2 - x1, Math.abs(y2 - y1));
        } else {
            mapystep = 0;
            partial = Fixed.FRACUNIT;
            xstep = 256 * Fixed.FRACUNIT;
        }
        xintercept = (x1 >> MAPBTOFRAC) + Fixed.mul(partial, xstep);

        // Step through map blocks. Count is present to prevent a
        // round-off error from skipping the break.
        mapx = xt1;
        mapy = yt1;

        for (count = 0; count < 64; count++) {
            if ((flags & PT_ADDLINES) != 0) {
                if (!pBlockLinesIterator(mapx, mapy, PMaputl::pitAddLineIntercepts)) {
                    return false; // early out
                }
            }

            if ((flags & PT_ADDTHINGS) != 0) {
                if (!pBlockThingsIterator(mapx, mapy, PMaputl::pitAddThingIntercepts)) {
                    return false; // early out
                }
            }

            if (mapx == xt2 && mapy == yt2) {
                break;
            }

            if ((yintercept >> Fixed.FRACBITS) == mapy) {
                yintercept += ystep;
                mapx += mapxstep;
            } else if ((xintercept >> Fixed.FRACBITS) == mapx) {
                xintercept += xstep;
                mapy += mapystep;
            }
        }
        // go through the sorted list
        return pTraverseIntercepts(trav, Fixed.FRACUNIT);
    }
}
