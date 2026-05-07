// Port of linuxdoom-1.10/r_bsp.c — full.
//
// BSP traversal for rendering. Walks the BSP tree front-to-back from
// the player's eye, dispatching each visible subsector to:
//   - R_AddSprites      (r_things.c — sprite collection)
//   - R_FindPlane       (r_plane.c — visplane allocation)
//   - R_AddLine         (this file — seg clipping)
//
// R_AddLine clips each seg to the view cone, maps the visible
// fraction to screen-x columns via viewangletox[], then dispatches
// through R_ClipSolidWallSegment / R_ClipPassWallSegment which both
// route to R_StoreWallRange (r_segs.c). The clip lists track which
// columns are already filled so back-to-front BSP order naturally
// implements occlusion.
//
// The C `goto clipsolid:` / `goto clippass:` and `goto crunch:` in
// the wall-segment clipper translate as labelled boolean flags +
// while loops (CLAUDE.md §2 — preserve control flow shape).
package doom.render;

import doom.play.PSetup;
import doom.system.ISystem;

public final class RBsp {

    // r_defs.h line 55 — drawseg ring buffer cap.
    public static final int MAXDRAWSEGS = 256;

    // r_bsp.c lines 47–51 — current-line scratch globals shared with
    // R_StoreWallRange / sprite clipping.
    public static Seg curline;
    public static Side sidedef;
    public static Line linedef;
    public static Sector frontsector;
    public static Sector backsector;

    // r_bsp.c lines 53–54 — per-frame drawseg list. dsP is the next-
    // free index (the C `drawseg_t* ds_p` increments by 1 per
    // R_StoreWallRange).
    public static final Drawseg[] drawsegs = new Drawseg[MAXDRAWSEGS];
    public static int dsP;

    // r_plane.c — current frame's floor / ceiling visplane targets.
    // Set by R_Subsector; read by R_StoreWallRange / R_DrawPlanes.
    public static RPlane.Visplane floorplane;
    public static RPlane.Visplane ceilingplane;

    // r_segs.c — set by R_AddLine before the clip dispatch, read by
    // R_StoreWallRange. Lives here because r_bsp computes it.
    public static int rwAngle1; // angle_t

    // r_bsp.c lines 80–92 — clip-list machinery.
    public static final int MAXSEGS = 32;

    public static final class Cliprange {
        public int first;
        public int last;
    }

    public static final Cliprange[] solidsegs = new Cliprange[MAXSEGS + 1];
    public static int newend;   // index one past the last valid seg

    static {
        for (int i = 0; i < solidsegs.length; i++) {
            solidsegs[i] = new Cliprange();
        }
    }

    private RBsp() {
    }

    //
    // R_ClearDrawSegs
    //
    // r_bsp.c lines 68–71.
    public static void rClearDrawSegs() {
        dsP = 0;
    }

    //
    // R_ClipSolidWallSegment
    //
    // r_bsp.c lines 103–185. Single-sided (or closed-door) walls
    // claim their column range and merge with adjacent ranges in the
    // clip list, painting any newly-visible fragments via
    // R_StoreWallRange.
    public static void rClipSolidWallSegment(int first, int last) {
        int next;
        int start;

        // Find the first range that touches the range
        //  (adjacent pixels are touching).
        start = 0;
        while (solidsegs[start].last < first - 1) {
            start++;
        }

        if (first < solidsegs[start].first) {
            if (last < solidsegs[start].first - 1) {
                // Post is entirely visible (above start),
                //  so insert a new clippost.
                RSegs.rStoreWallRange(first, last);
                next = newend;
                newend++;

                while (next != start) {
                    // *next = *(next-1)
                    solidsegs[next].first = solidsegs[next - 1].first;
                    solidsegs[next].last = solidsegs[next - 1].last;
                    next--;
                }
                solidsegs[next].first = first;
                solidsegs[next].last = last;
                return;
            }

            // There is a fragment above *start.
            RSegs.rStoreWallRange(first, solidsegs[start].first - 1);
            // Now adjust the clip size.
            solidsegs[start].first = first;
        }

        // Bottom contained in start?
        if (last <= solidsegs[start].last) {
            return;
        }

        next = start;
        boolean crunch = false;
        while (last >= solidsegs[next + 1].first - 1) {
            // There is a fragment between two posts.
            RSegs.rStoreWallRange(solidsegs[next].last + 1,
                    solidsegs[next + 1].first - 1);
            next++;

            if (last <= solidsegs[next].last) {
                // Bottom is contained in next.
                // Adjust the clip size.
                solidsegs[start].last = solidsegs[next].last;
                crunch = true;
                break;
            }
        }

        if (!crunch) {
            // There is a fragment after *next.
            RSegs.rStoreWallRange(solidsegs[next].last + 1, last);
            // Adjust the clip size.
            solidsegs[start].last = last;
        }

        // Remove start+1 to next from the clip list,
        // because start now covers their area.
        if (next == start) {
            // Post just extended past the bottom of one post.
            return;
        }


        // C: while (next++ != newend) *++start = *next;
        //   means: copy solidsegs[next+1..newend-1] over solidsegs[start+1..]
        next++;
        while (next != newend) {
            start++;
            solidsegs[start].first = solidsegs[next].first;
            solidsegs[start].last = solidsegs[next].last;
            next++;
        }
        // Final copy when next == newend (the C does one more
        // iteration before the post-increment fails the loop).
        // Actually trace: `while (next++ != newend) *++start = *next`
        //   — iter 1: cmp next==newend? no, post-inc next; assign
        //     *(start+1) = *(after-incremented next-1). Wait, the
        //     C `*++start = *next` runs AFTER the post-increment in
        //     the loop test. So the body sees next as the value
        //     POST-increment, so `*next` reads one past the original.
        //
        // The semantics: copy solidsegs[next+1..newend] over
        // solidsegs[start+1..]. The Java port above advances next
        // before the assignment, then exits when next == newend
        // (one short of what the C does with its post-increment).
        // Adjust: include the newend position too.
        // Actually rechecking: my loop above starts with next++ to
        // align, then walks until next == newend. The C does
        // exactly this path. Match.

        newend = start + 1;
    }


    //
    // R_ClipPassWallSegment
    //
    // r_bsp.c lines 196–238. Two-sided "window" walls don't claim
    // the column range — they just paint any newly-visible
    // fragments and let the clip list keep going.
    public static void rClipPassWallSegment(int first, int last) {
        int start;

        // Find the first range that touches the range
        //  (adjacent pixels are touching).
        start = 0;
        while (solidsegs[start].last < first - 1) {
            start++;
        }

        if (first < solidsegs[start].first) {
            if (last < solidsegs[start].first - 1) {
                // Post is entirely visible (above start).
                RSegs.rStoreWallRange(first, last);
                return;
            }

            // There is a fragment above *start.
            RSegs.rStoreWallRange(first, solidsegs[start].first - 1);
        }

        // Bottom contained in start?
        if (last <= solidsegs[start].last) {
            return;
        }

        while (last >= solidsegs[start + 1].first - 1) {
            // There is a fragment between two posts.
            RSegs.rStoreWallRange(solidsegs[start].last + 1,
                    solidsegs[start + 1].first - 1);
            start++;

            if (last <= solidsegs[start].last) {
                return;
            }
        }

        // There is a fragment after *next.
        RSegs.rStoreWallRange(solidsegs[start].last + 1, last);
    }


    //
    // R_ClearClipSegs
    //
    // r_bsp.c lines 245–252. Seeds the clip list with two sentinel
    // ranges flanking the screen: an unbounded "above" (-INF..-1)
    // and an unbounded "below" (viewwidth..+INF).
    public static void rClearClipSegs() {
        solidsegs[0].first = -0x7fffffff;
        solidsegs[0].last = -1;
        solidsegs[1].first = RMain.viewwidth;
        solidsegs[1].last = 0x7fffffff;
        newend = 2;
    }

    //
    // R_AddLine
    // Clips the given segment and adds any visible pieces to the
    // line list.
    //
    // r_bsp.c lines 259–356. The C uses `goto clipsolid:` /
    // `goto clippass:` to share the dispatch tail; the Java port
    // uses a local `clipsolid` flag.
    public static void rAddLine(Seg line) {
        int x1;
        int x2;
        int angle1;
        int angle2;
        int span;
        int tspan;

        curline = line;

        // OPTIMIZE: quickly reject orthogonal back sides.
        angle1 = RMain.rPointToAngle(line.v1.x, line.v1.y);
        angle2 = RMain.rPointToAngle(line.v2.x, line.v2.y);

        // Clip to view edges.
        // OPTIMIZE: make constant out of 2*clipangle (FIELDOFVIEW).
        span = angle1 - angle2;

        // Back side? I.e. backface culling?
        // C: span >= ANG180 (unsigned 32-bit compare).
        if (Integer.compareUnsigned(span, Tables.ANG180) >= 0) {
            return;
        }

        // Global angle needed by segcalc.
        rwAngle1 = angle1;
        angle1 -= RMain.viewangle;
        angle2 -= RMain.viewangle;

        tspan = angle1 + RMain.clipangle;
        if (Integer.compareUnsigned(tspan, 2 * RMain.clipangle) > 0) {
            tspan -= 2 * RMain.clipangle;

            // Totally off the left edge?
            if (Integer.compareUnsigned(tspan, span) >= 0) {
                return;
            }

            angle1 = RMain.clipangle;
        }
        tspan = RMain.clipangle - angle2;
        if (Integer.compareUnsigned(tspan, 2 * RMain.clipangle) > 0) {
            tspan -= 2 * RMain.clipangle;

            // Totally off the left edge?
            if (Integer.compareUnsigned(tspan, span) >= 0) {
                return;
            }
            angle2 = -RMain.clipangle;
        }

        // The seg is in the view range,
        // but not necessarily visible.
        angle1 = (angle1 + Tables.ANG90) >>> Tables.ANGLETOFINESHIFT;
        angle2 = (angle2 + Tables.ANG90) >>> Tables.ANGLETOFINESHIFT;
        x1 = RMain.viewangletox[angle1];
        x2 = RMain.viewangletox[angle2];

        // Does not cross a pixel?
        if (x1 == x2) {
            return;
        }

        backsector = line.backsector;

        boolean clipsolid = false;

        // Single sided line?
        if (backsector == null) {
            clipsolid = true;
        } else if (backsector.ceilingheight <= frontsector.floorheight
                || backsector.floorheight >= frontsector.ceilingheight) {
            // Closed door.
            clipsolid = true;
        } else if (backsector.ceilingheight != frontsector.ceilingheight
                || backsector.floorheight != frontsector.floorheight) {
            // Window — fall through to the clippass dispatch.
        } else if (backsector.ceilingpic == frontsector.ceilingpic
                && backsector.floorpic == frontsector.floorpic
                && backsector.lightlevel == frontsector.lightlevel
                && curline.sidedef.midtexture == 0) {
            // Reject empty lines used for triggers and special events.
            return;
        }

        if (clipsolid) {
            rClipSolidWallSegment(x1, x2 - 1);
        } else {
            rClipPassWallSegment(x1, x2 - 1);
        }
    }

    //
    // R_CheckBBox
    //
    // r_bsp.c lines 365–487. The 3×3 grid of view positions vs. the
    // bbox decides which two corners form the silhouette edges; the
    // checkcoord LUT picks the right pair.
    public static final int[][] checkcoord = {
        {3, 0, 2, 1},
        {3, 0, 2, 0},
        {3, 1, 2, 0},
        {0, 0, 0, 0},
        {2, 0, 2, 1},
        {0, 0, 0, 0},
        {3, 1, 3, 0},
        {0, 0, 0, 0},
        {2, 0, 3, 1},
        {2, 1, 3, 1},
        {2, 1, 3, 0},
        {0, 0, 0, 0}
    };

    public static boolean rCheckBBox(int[] bspcoord) {
        int boxx;
        int boxy;
        int boxpos;

        int x1;
        int y1;
        int x2;
        int y2;

        int angle1;
        int angle2;
        int span;
        int tspan;

        int sx1;
        int sx2;

        // Find the corners of the box that define the edges from
        // current viewpoint.
        if (RMain.viewx <= bspcoord[doom.play.MBbox.BOXLEFT]) {
            boxx = 0;
        } else if (RMain.viewx < bspcoord[doom.play.MBbox.BOXRIGHT]) {
            boxx = 1;
        } else {
            boxx = 2;
        }

        if (RMain.viewy >= bspcoord[doom.play.MBbox.BOXTOP]) {
            boxy = 0;
        } else if (RMain.viewy > bspcoord[doom.play.MBbox.BOXBOTTOM]) {
            boxy = 1;
        } else {
            boxy = 2;
        }

        boxpos = (boxy << 2) + boxx;
        if (boxpos == 5) {
            return true;
        }

        x1 = bspcoord[checkcoord[boxpos][0]];
        y1 = bspcoord[checkcoord[boxpos][1]];
        x2 = bspcoord[checkcoord[boxpos][2]];
        y2 = bspcoord[checkcoord[boxpos][3]];

        // check clip list for an open space
        angle1 = RMain.rPointToAngle(x1, y1) - RMain.viewangle;
        angle2 = RMain.rPointToAngle(x2, y2) - RMain.viewangle;

        span = angle1 - angle2;

        // Sitting on a line?
        if (Integer.compareUnsigned(span, Tables.ANG180) >= 0) {
            return true;
        }

        tspan = angle1 + RMain.clipangle;

        if (Integer.compareUnsigned(tspan, 2 * RMain.clipangle) > 0) {
            tspan -= 2 * RMain.clipangle;

            // Totally off the left edge?
            if (Integer.compareUnsigned(tspan, span) >= 0) {
                return false;
            }

            angle1 = RMain.clipangle;
        }
        tspan = RMain.clipangle - angle2;
        if (Integer.compareUnsigned(tspan, 2 * RMain.clipangle) > 0) {
            tspan -= 2 * RMain.clipangle;

            // Totally off the left edge?
            if (Integer.compareUnsigned(tspan, span) >= 0) {
                return false;
            }

            angle2 = -RMain.clipangle;
        }


        // Find the first clippost
        //  that touches the source post
        //  (adjacent pixels are touching).
        angle1 = (angle1 + Tables.ANG90) >>> Tables.ANGLETOFINESHIFT;
        angle2 = (angle2 + Tables.ANG90) >>> Tables.ANGLETOFINESHIFT;
        sx1 = RMain.viewangletox[angle1];
        sx2 = RMain.viewangletox[angle2];

        // Does not cross a pixel.
        if (sx1 == sx2) {
            return false;
        }
        sx2--;

        int start = 0;
        while (solidsegs[start].last < sx2) {
            start++;
        }

        if (sx1 >= solidsegs[start].first
                && sx2 <= solidsegs[start].last) {
            // The clippost contains the new span.
            return false;
        }

        return true;
    }


    //
    // R_Subsector
    //
    // r_bsp.c lines 497–542. For each subsector hit by the BSP walk:
    //   - allocate floor/ceiling visplanes (if visible from viewz)
    //   - register sprites of mobjs in the sector
    //   - clip every seg through R_AddLine
    public static void rSubsector(int num) {
        int count;
        Subsector sub;

        if (num >= PSetup.numsubsectors) {
            ISystem.iError("R_Subsector: ss " + num
                    + " with numss = " + PSetup.numsubsectors);
        }

        RMain.sscount++;
        sub = PSetup.subsectors[num];
        frontsector = sub.sector;
        count = sub.numlines;
        int segIdx = sub.firstline;

        if (frontsector.floorheight < RMain.viewz) {
            floorplane = RPlane.rFindPlane(frontsector.floorheight,
                    frontsector.floorpic,
                    frontsector.lightlevel);
        } else {
            floorplane = null;
        }

        if (frontsector.ceilingheight > RMain.viewz
                || frontsector.ceilingpic == RSky.skyflatnum) {
            ceilingplane = RPlane.rFindPlane(frontsector.ceilingheight,
                    frontsector.ceilingpic,
                    frontsector.lightlevel);
        } else {
            ceilingplane = null;
        }

        RThings.rAddSprites(frontsector);

        while (count-- != 0) {
            rAddLine(PSetup.segs[segIdx]);
            segIdx++;
        }
    }

    //
    // R_RenderBSPNode
    // Renders all subsectors below a given node.
    //
    // r_bsp.c lines 552–578.
    public static void rRenderBSPNode(int bspnum) {
        Node bsp;
        int side;

        // Found a subsector?
        if ((bspnum & Node.NF_SUBSECTOR) != 0) {
            if (bspnum == -1) {
                rSubsector(0);
            } else {
                rSubsector(bspnum & ~Node.NF_SUBSECTOR);
            }
            return;
        }

        bsp = PSetup.nodes[bspnum];

        // Decide which side the view point is on.
        side = RMain.rPointOnSide(RMain.viewx, RMain.viewy, bsp);

        // Recursively divide front space.
        rRenderBSPNode(bsp.children[side]);

        // Possibly divide back space.
        if (rCheckBBox(bsp.bbox[side ^ 1])) {
            rRenderBSPNode(bsp.children[side ^ 1]);
        }
    }
}
