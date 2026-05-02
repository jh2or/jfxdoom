// Port of linuxdoom-1.10/p_sight.c — full.
//
// Line-of-sight check between two mobjs, used by AI (P_LookForPlayers
// in p_enemy.c) and by some special-line activations. Walks the BSP
// from the root with a divline running t1.origin → t2.origin and asks
// every two-sided segment along the way whether the open vertical
// interval is wide enough to see through; the per-segment slope clip
// narrows topslope/bottomslope until they cross, at which point the
// view is occluded.
//
// REJECT short-circuit: each entry in the bit-packed sector × sector
// rejectmatrix marks a pair of sectors that can never see each other
// (precomputed by the node builder). A set bit returns false without
// ever descending the BSP — handy for cross-room visibility cuts.
//
// Faithfulness notes:
//   - The C reinterprets `node_t*` as `divline_t*` to call
//     P_DivlineSide on a partition node (the first four fields of
//     node_t and divline_t are the same: x/y/dx/dy fixed_t). Java
//     can't reinterpret; the helper `pDivlineSideNode(x, y, Node)`
//     reads the four fields directly with the same arithmetic.
//   - `validcount`/`sectors`/`subsectors`/`segs`/`nodes`/`numsectors`/
//     `rejectmatrix` come from RMain / PSetup as already exported.
//   - `P_DivlineSide` here is independent of `P_PointOnDivlineSide`
//     in PMaputl: the sight version returns 0/1/2 (0=front, 1=back,
//     2=on), while PMaputl returns just 0/1. Side semantics also
//     differ — sight uses dy>0 / dx<0 in the axis-aligned cases, the
//     intercept version uses different cases. They're not
//     interchangeable; both stay verbatim.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Node;
import doom.render.Line;
import doom.render.Seg;
import doom.render.Sector;
import doom.render.Subsector;
import doom.render.RMain;
import doom.render.Vertex;
import doom.system.ISystem;

public final class PSight {

    // p_sight.c lines 39–47 — globals shared across the recursive
    // BSP walk. Java keeps them as static fields (deviation 11 — the
    // C globals → public static convention).
    public static int sightzstart;          // fixed_t — eye z of looker
    public static int topslope;             // fixed_t
    public static int bottomslope;          // fixed_t — slopes to top/bottom of target

    public static final Divline strace = new Divline(); // from t1 to t2
    public static int t2x;                  // fixed_t
    public static int t2y;                  // fixed_t

    // p_sight.c line 47 — sightcounts[0]: rejected by REJECT,
    // sightcounts[1]: walked the BSP. The C never reads these for
    // game state; they're profiling counters. Preserved for parity.
    public static final int[] sightcounts = new int[2];

    private PSight() {
    }

    //
    // P_DivlineSide
    // Returns side 0 (front), 1 (back), or 2 (on).
    //
    // p_sight.c lines 54–99.
    public static int pDivlineSide(int x, int y, Divline node) {
        int dx;
        int dy;
        int left;
        int right;

        if (node.dx == 0) {
            if (x == node.x) {
                return 2;
            }

            if (x <= node.x) {
                return node.dy > 0 ? 1 : 0;
            }

            return node.dy < 0 ? 1 : 0;
        }

        if (node.dy == 0) {
            // Faithful to the C: the bug `if (x==node->y)` (note the
            // y on the RHS) is preserved. CLAUDE.md §2 — preserve
            // bugs. In practice this branch is dead because dy==0
            // and the call sites are unlikely to hit y==node.y == 0
            // simultaneously for the equality to have any effect.
            if (x == node.y) {
                return 2;
            }

            if (y <= node.y) {
                return node.dx < 0 ? 1 : 0;
            }

            return node.dx > 0 ? 1 : 0;
        }

        dx = (x - node.x);
        dy = (y - node.y);

        left = (node.dy >> Fixed.FRACBITS) * (dx >> Fixed.FRACBITS);
        right = (dy >> Fixed.FRACBITS) * (node.dx >> Fixed.FRACBITS);

        if (right < left) {
            return 0;       // front side
        }

        if (left == right) {
            return 2;
        }
        return 1;           // back side
    }

    // p_sight.c P_CrossBSPNode reinterprets a Node as a Divline; in
    // Java we call pDivlineSide with the first four Node fields
    // (x/y/dx/dy) directly. Same arithmetic.
    public static int pDivlineSideNode(int x, int y, Node node) {
        int dx;
        int dy;
        int left;
        int right;

        if (node.dx == 0) {
            if (x == node.x) {
                return 2;
            }
            if (x <= node.x) {
                return node.dy > 0 ? 1 : 0;
            }
            return node.dy < 0 ? 1 : 0;
        }

        if (node.dy == 0) {
            if (x == node.y) {
                return 2;
            }
            if (y <= node.y) {
                return node.dx < 0 ? 1 : 0;
            }
            return node.dx > 0 ? 1 : 0;
        }

        dx = (x - node.x);
        dy = (y - node.y);

        left = (node.dy >> Fixed.FRACBITS) * (dx >> Fixed.FRACBITS);
        right = (dy >> Fixed.FRACBITS) * (node.dx >> Fixed.FRACBITS);

        if (right < left) {
            return 0;
        }
        if (left == right) {
            return 2;
        }
        return 1;
    }

    //
    // P_InterceptVector2
    // Returns the fractional intercept point along the first divline.
    // This is only called by the addthings and addlines traversers.
    //
    // p_sight.c lines 108–128.
    public static int pInterceptVector2(Divline v2, Divline v1) {
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
    // P_CrossSubsector
    // Returns true if strace crosses the given subsector successfully.
    //
    // p_sight.c lines 135–248.
    public static boolean pCrossSubsector(int num) {
        Seg seg;
        Line line;
        int s1;
        int s2;
        int count;
        Subsector sub;
        Sector front;
        Sector back;
        int opentop;
        int openbottom;
        Divline divl = new Divline();
        Vertex v1;
        Vertex v2;
        int frac;
        int slope;

        if (num >= PSetup.numsubsectors) {
            ISystem.iError("P_CrossSubsector: ss " + num
                    + " with numss = " + PSetup.numsubsectors);
        }

        sub = PSetup.subsectors[num];

        // check lines
        count = sub.numlines;
        // C: seg = &segs[sub->firstline];
        int segIdx = sub.firstline;

        for (; count != 0; segIdx++, count--) {
            seg = PSetup.segs[segIdx];
            line = seg.linedef;

            // allready checked other side?
            if (line.validcount == RMain.validcount) {
                continue;
            }

            line.validcount = RMain.validcount;

            v1 = line.v1;
            v2 = line.v2;
            s1 = pDivlineSide(v1.x, v1.y, strace);
            s2 = pDivlineSide(v2.x, v2.y, strace);

            // line isn't crossed?
            if (s1 == s2) {
                continue;
            }

            divl.x = v1.x;
            divl.y = v1.y;
            divl.dx = v2.x - v1.x;
            divl.dy = v2.y - v1.y;
            s1 = pDivlineSide(strace.x, strace.y, divl);
            s2 = pDivlineSide(t2x, t2y, divl);

            // line isn't crossed?
            if (s1 == s2) {
                continue;
            }

            // stop because it is not two sided anyway
            // might do this after updating validcount?
            if ((line.flags & Line.ML_TWOSIDED) == 0) {
                return false;
            }

            // crosses a two sided line
            front = seg.frontsector;
            back = seg.backsector;

            // no wall to block sight with?
            if (front.floorheight == back.floorheight
                    && front.ceilingheight == back.ceilingheight) {
                continue;
            }

            // possible occluder
            // because of ceiling height differences
            if (front.ceilingheight < back.ceilingheight) {
                opentop = front.ceilingheight;
            } else {
                opentop = back.ceilingheight;
            }

            // because of ceiling height differences
            // (sic — comment in C says "ceiling" but code reads floor)
            if (front.floorheight > back.floorheight) {
                openbottom = front.floorheight;
            } else {
                openbottom = back.floorheight;
            }

            // quick test for totally closed doors
            if (openbottom >= opentop) {
                return false;       // stop
            }

            frac = pInterceptVector2(strace, divl);

            if (front.floorheight != back.floorheight) {
                slope = Fixed.div(openbottom - sightzstart, frac);
                if (slope > bottomslope) {
                    bottomslope = slope;
                }
            }

            if (front.ceilingheight != back.ceilingheight) {
                slope = Fixed.div(opentop - sightzstart, frac);
                if (slope < topslope) {
                    topslope = slope;
                }
            }

            if (topslope <= bottomslope) {
                return false;       // stop
            }
        }
        // passed the subsector ok
        return true;
    }

    //
    // P_CrossBSPNode
    // Returns true if strace crosses the given node successfully.
    //
    // p_sight.c lines 257–290.
    public static boolean pCrossBSPNode(int bspnum) {
        Node bsp;
        int side;

        if ((bspnum & Node.NF_SUBSECTOR) != 0) {
            if (bspnum == -1) {
                return pCrossSubsector(0);
            } else {
                return pCrossSubsector(bspnum & ~Node.NF_SUBSECTOR);
            }
        }

        bsp = PSetup.nodes[bspnum];

        // decide which side the start point is on
        side = pDivlineSideNode(strace.x, strace.y, bsp);
        if (side == 2) {
            side = 0;       // an "on" should cross both sides
        }

        // cross the starting side
        if (!pCrossBSPNode(bsp.children[side])) {
            return false;
        }

        // the partition plane is crossed here
        if (side == pDivlineSideNode(t2x, t2y, bsp)) {
            // the line doesn't touch the other side
            return true;
        }

        // cross the ending side
        return pCrossBSPNode(bsp.children[side ^ 1]);
    }

    //
    // P_CheckSight
    // Returns true if a straight line between t1 and t2 is unobstructed.
    // Uses REJECT.
    //
    // p_sight.c lines 299–347.
    public static boolean pCheckSight(Mobj t1, Mobj t2) {
        int s1;
        int s2;
        int pnum;
        int bytenum;
        int bitnum;

        // First check for trivial rejection.

        // Determine subsector entries in REJECT table.
        // C: s1 = (t1->subsector->sector - sectors); pointer
        // arithmetic. Java needs a linear scan.
        s1 = sectorIndex(t1.subsector.sector);
        s2 = sectorIndex(t2.subsector.sector);
        pnum = s1 * PSetup.numsectors + s2;
        bytenum = pnum >> 3;
        bitnum = 1 << (pnum & 7);

        // Check in REJECT table.
        if ((PSetup.rejectmatrix[bytenum] & bitnum) != 0) {
            sightcounts[0]++;

            // can't possibly be connected
            return false;
        }

        // An unobstructed LOS is possible.
        // Now look from eyes of t1 to any part of t2.
        sightcounts[1]++;

        RMain.validcount++;

        sightzstart = t1.z + t1.height - (t1.height >> 2);
        topslope = (t2.z + t2.height) - sightzstart;
        bottomslope = (t2.z) - sightzstart;

        strace.x = t1.x;
        strace.y = t1.y;
        t2x = t2.x;
        t2y = t2.y;
        strace.dx = t2.x - t1.x;
        strace.dy = t2.y - t1.y;

        // the head node is the last node output
        return pCrossBSPNode(PSetup.numnodes - 1);
    }

    // The C does `t1->subsector->sector - sectors` to get an index.
    // Java needs a linear scan; numsectors is at most a few hundred
    // per level so the cost is negligible compared to the BSP walk.
    private static int sectorIndex(Sector s) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            if (PSetup.sectors[i] == s) {
                return i;
            }
        }
        return -1;
    }
}
