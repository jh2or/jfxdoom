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
// Only the pure-math layer lands here. The thing-position / blockmap
// layer needs Mobj.{snext,sprev,bnext,bprev,subsector,flags,radius},
// the Subsector type, and the blockmap globals (bmaporgx, bmaporgy,
// bmapwidth, bmapheight, blocklinks, blockmap, blockmaplump) from
// p_setup.c — none ported yet. Same partial-port pattern as PFloor /
// PSwitch / PDoors.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;

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
