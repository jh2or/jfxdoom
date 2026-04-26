package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.RMain;
import doom.render.Sector;
import doom.render.Slopetype;
import doom.render.Vertex;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PMaputlTest {

    @BeforeEach
    void reset() {
        PMaputl.opentop = 0;
        PMaputl.openbottom = 0;
        PMaputl.openrange = 0;
        PMaputl.lowfloor = 0;
        PSetup.numlines = 0;
        PSetup.lines = null;
        PSetup.bmapwidth = 0;
        PSetup.bmapheight = 0;
        PSetup.blockmap = null;
        PSetup.blockmaplump = null;
        PSetup.blocklinks = null;
        // Bumping past whatever any earlier test left behind keeps
        // each iterator pass starting with "fresh" lines.
        RMain.validcount++;
    }

    // Build a Line whose v1=(x1,y1), v2=(x2,y2), with dx/dy precalculated
    // and slopetype tagged. (P_SetupLevel does this in C; we do it here
    // for tests.)
    private static Line line(int x1, int y1, int x2, int y2, Slopetype st) {
        Line ld = new Line();
        ld.v1 = new Vertex(); ld.v1.x = x1; ld.v1.y = y1;
        ld.v2 = new Vertex(); ld.v2.x = x2; ld.v2.y = y2;
        ld.dx = x2 - x1;
        ld.dy = y2 - y1;
        ld.slopetype = st;
        ld.sidenum[0] = 0;
        ld.sidenum[1] = -1;
        return ld;
    }

    // --- P_AproxDistance ---

    // Pure formula: dx + dy - min(dx,dy)/2 with both abs'd.
    @Test
    void aproxDistanceMatchesFormula() {
        // dx >= dy: returns dx + dy - dy/2
        assertEquals(100 + 40 - 20, PMaputl.pAproxDistance(100, 40));
        // dx < dy:  returns dx + dy - dx/2
        assertEquals(40 + 100 - 20, PMaputl.pAproxDistance(40, 100));
    }

    @Test
    void aproxDistanceTakesAbsoluteValues() {
        assertEquals(PMaputl.pAproxDistance(100, 40),
                     PMaputl.pAproxDistance(-100, -40));
        assertEquals(PMaputl.pAproxDistance(100, 40),
                     PMaputl.pAproxDistance(-100, 40));
    }

    @Test
    void aproxDistanceZeroAxisCollapses() {
        assertEquals(50, PMaputl.pAproxDistance(50, 0));
        assertEquals(50, PMaputl.pAproxDistance(0, 50));
        assertEquals(0, PMaputl.pAproxDistance(0, 0));
    }

    // --- P_PointOnLineSide ---

    // Vertical line (dx==0) — uses x against v1.x, side decided by sign of dy.
    @Test
    void pointOnLineSideVerticalLineWithPositiveDy() {
        // v1=(10,0) v2=(10,100): dx=0, dy=+100
        Line ld = line(10, 0, 10, 100, Slopetype.ST_VERTICAL);

        // x <= v1.x → returns (dy > 0) = 1
        assertEquals(1, PMaputl.pPointOnLineSide(5, 50, ld));
        assertEquals(1, PMaputl.pPointOnLineSide(10, 50, ld)); // boundary
        // x >  v1.x → returns (dy < 0) = 0
        assertEquals(0, PMaputl.pPointOnLineSide(15, 50, ld));
    }

    @Test
    void pointOnLineSideVerticalLineWithNegativeDy() {
        // v1=(10,100) v2=(10,0): dx=0, dy=-100
        Line ld = line(10, 100, 10, 0, Slopetype.ST_VERTICAL);

        assertEquals(0, PMaputl.pPointOnLineSide(5, 50, ld));   // x<=v1.x → dy>0 = 0
        assertEquals(1, PMaputl.pPointOnLineSide(15, 50, ld));  // x>v1.x  → dy<0 = 1
    }

    // Horizontal line (dy==0).
    @Test
    void pointOnLineSideHorizontalLineWithPositiveDx() {
        // v1=(0,10) v2=(100,10): dx=+100, dy=0
        Line ld = line(0, 10, 100, 10, Slopetype.ST_HORIZONTAL);

        assertEquals(0, PMaputl.pPointOnLineSide(50, 5, ld));   // y<=v1.y → dx<0 = 0
        assertEquals(1, PMaputl.pPointOnLineSide(50, 15, ld));  // y>v1.y  → dx>0 = 1
    }

    // Diagonal line: front side.
    @Test
    void pointOnLineSideDiagonalFront() {
        // v1=(0,0) v2=(FRACUNIT,FRACUNIT): goes up-right. The "front" side
        // (return 0) is the right side.
        Line ld = line(0, 0, Fixed.FRACUNIT, Fixed.FRACUNIT, Slopetype.ST_POSITIVE);

        // (FRACUNIT, 0) is below the line → front
        assertEquals(0, PMaputl.pPointOnLineSide(Fixed.FRACUNIT, 0, ld));
        // (0, FRACUNIT) is above the line → back
        assertEquals(1, PMaputl.pPointOnLineSide(0, Fixed.FRACUNIT, ld));
    }

    // --- P_BoxOnLineSide ---

    // Helper: build a box with four explicit corners.
    private static int[] box(int top, int bottom, int left, int right) {
        int[] b = new int[4];
        b[MBbox.BOXTOP] = top;
        b[MBbox.BOXBOTTOM] = bottom;
        b[MBbox.BOXLEFT] = left;
        b[MBbox.BOXRIGHT] = right;
        return b;
    }

    @Test
    void boxOnLineSideHorizontalLineBoxAbove() {
        // Line y=10, dx>0. Box entirely above (y>10).
        Line ld = line(0, 10, 100, 10, Slopetype.ST_HORIZONTAL);
        int[] b = box(/*top*/30, /*bot*/20, /*L*/40, /*R*/60);

        // Both p1 (top>v1.y → 1) and p2 (bottom>v1.y → 1). dx>=0 → no flip.
        assertEquals(1, PMaputl.pBoxOnLineSide(b, ld));
    }

    @Test
    void boxOnLineSideHorizontalLineBoxBelow() {
        Line ld = line(0, 10, 100, 10, Slopetype.ST_HORIZONTAL);
        int[] b = box(5, 0, 40, 60);

        // top<v1.y → 0, bottom<v1.y → 0. dx>=0 → no flip.
        assertEquals(0, PMaputl.pBoxOnLineSide(b, ld));
    }

    @Test
    void boxOnLineSideHorizontalLineBoxStraddles() {
        Line ld = line(0, 10, 100, 10, Slopetype.ST_HORIZONTAL);
        int[] b = box(20, 0, 40, 60); // top above, bottom below

        // p1=1, p2=0 → -1 (straddles).
        assertEquals(-1, PMaputl.pBoxOnLineSide(b, ld));
    }

    @Test
    void boxOnLineSideVerticalLine() {
        // Line x=10, dy>0.
        Line ld = line(10, 0, 10, 100, Slopetype.ST_VERTICAL);

        // Box entirely to the left of x=10: right<v1.x → both 1.
        // dy>=0 → no flip.
        assertEquals(1, PMaputl.pBoxOnLineSide(box(50, 40, 0, 5), ld));
        // Box entirely to the right: right<v1.x is false → both 0.
        assertEquals(0, PMaputl.pBoxOnLineSide(box(50, 40, 20, 30), ld));
        // Box straddles vertical line.
        assertEquals(-1, PMaputl.pBoxOnLineSide(box(50, 40, 5, 15), ld));
    }

    // --- P_PointOnDivlineSide ---

    @Test
    void pointOnDivlineSideAxisAlignedShortcuts() {
        Divline dl = new Divline();
        // Vertical: dx=0, dy>0. Same shape as P_PointOnLineSide vertical.
        dl.x = 10; dl.y = 0; dl.dx = 0; dl.dy = 100;
        assertEquals(1, PMaputl.pPointOnDivlineSide(5, 50, dl));
        assertEquals(0, PMaputl.pPointOnDivlineSide(15, 50, dl));

        // Horizontal: dy=0, dx>0.
        dl.x = 0; dl.y = 10; dl.dx = 100; dl.dy = 0;
        assertEquals(0, PMaputl.pPointOnDivlineSide(50, 5, dl));
        assertEquals(1, PMaputl.pPointOnDivlineSide(50, 15, dl));
    }

    @Test
    void pointOnDivlineSideDiagonalFrontAndBack() {
        // 45-degree line up-right from origin.
        Divline dl = new Divline();
        dl.x = 0; dl.y = 0; dl.dx = Fixed.FRACUNIT; dl.dy = Fixed.FRACUNIT;

        // (FRACUNIT, 0) — below line → front (0)
        assertEquals(0, PMaputl.pPointOnDivlineSide(Fixed.FRACUNIT, 0, dl));
        // (0, FRACUNIT) — above line → back (1)
        assertEquals(1, PMaputl.pPointOnDivlineSide(0, Fixed.FRACUNIT, dl));
    }

    // --- P_MakeDivline ---

    @Test
    void makeDivlineCopiesFromLine() {
        Line ld = line(10, 20, 50, 80, Slopetype.ST_POSITIVE);
        Divline dl = new Divline();

        PMaputl.pMakeDivline(ld, dl);

        assertEquals(10, dl.x);
        assertEquals(20, dl.y);
        assertEquals(40, dl.dx); // 50-10
        assertEquals(60, dl.dy); // 80-20
    }

    // --- P_InterceptVector ---

    // Two parallel divlines → den == 0 → returns 0.
    @Test
    void interceptVectorReturnsZeroForParallelLines() {
        Divline a = new Divline();
        Divline b = new Divline();
        a.x = 0; a.y = 0; a.dx = Fixed.FRACUNIT; a.dy = 0;
        b.x = 0; b.y = Fixed.FRACUNIT; b.dx = Fixed.FRACUNIT; b.dy = 0;

        assertEquals(0, PMaputl.pInterceptVector(a, b));
    }

    // Perpendicular cross at the midpoint of v2 (whose origin is v2,
    // direction is v2.dx/v2.dy). The C signature is `(v2, v1)`: the
    // returned frac is along v2.
    @Test
    void interceptVectorComputesFractionAlongV2() {
        // v2 horizontal: from (0,0) length FRACUNIT in +x.
        Divline v2 = new Divline();
        v2.x = 0; v2.y = 0; v2.dx = Fixed.FRACUNIT; v2.dy = 0;
        // v1 vertical: passes through (FRACUNIT/2, -FRACUNIT) going +y.
        Divline v1 = new Divline();
        v1.x = Fixed.FRACUNIT / 2; v1.y = -Fixed.FRACUNIT;
        v1.dx = 0; v1.dy = Fixed.FRACUNIT;

        // Crossing should be at FRACUNIT/2 along v2 → frac = 0.5 (in fixed).
        int frac = PMaputl.pInterceptVector(v2, v1);
        // Allow ±2 LSBs of fixed-point fuzz.
        assertTrue(Math.abs(frac - (Fixed.FRACUNIT / 2)) <= 2,
                "frac=" + frac);
    }

    // --- P_LineOpening ---

    @Test
    void lineOpeningSingleSidedSetsRangeToZero() {
        Line ld = line(0, 0, 100, 0, Slopetype.ST_HORIZONTAL);
        ld.sidenum[1] = -1; // single sided

        // Pre-poison the other globals so we can confirm they're untouched.
        PMaputl.opentop = 999;
        PMaputl.openbottom = 888;
        PMaputl.lowfloor = 777;

        PMaputl.pLineOpening(ld);

        assertEquals(0, PMaputl.openrange);
        // Single-sided early-return — opentop/openbottom/lowfloor untouched.
        assertEquals(999, PMaputl.opentop);
        assertEquals(888, PMaputl.openbottom);
        assertEquals(777, PMaputl.lowfloor);
    }

    // Two-sided: front floor lower, back floor higher → openbottom = back,
    // lowfloor = front.
    @Test
    void lineOpeningTwoSidedFrontFloorLower() {
        Line ld = line(0, 0, 100, 0, Slopetype.ST_HORIZONTAL);
        ld.sidenum[1] = 1;
        ld.frontsector = sec(0, 200);    // floor=0, ceil=200
        ld.backsector  = sec(50, 150);   // floor=50, ceil=150

        PMaputl.pLineOpening(ld);

        // opentop = min(ceil) = 150
        assertEquals(150, PMaputl.opentop);
        // back floor (50) > front floor (0) → openbottom = back floor = 50,
        // lowfloor = front floor = 0
        assertEquals(50, PMaputl.openbottom);
        assertEquals(0, PMaputl.lowfloor);
        assertEquals(100, PMaputl.openrange); // 150 - 50
    }

    // Two-sided: front floor higher, back floor lower → openbottom=front,
    // lowfloor=back.
    @Test
    void lineOpeningTwoSidedFrontFloorHigher() {
        Line ld = line(0, 0, 100, 0, Slopetype.ST_HORIZONTAL);
        ld.sidenum[1] = 1;
        ld.frontsector = sec(80, 200);
        ld.backsector  = sec(20, 150);

        PMaputl.pLineOpening(ld);

        assertEquals(150, PMaputl.opentop);
        assertEquals(80, PMaputl.openbottom);
        assertEquals(20, PMaputl.lowfloor);
        assertEquals(70, PMaputl.openrange);
    }

    @Test
    void lineOpeningOpentopPicksLowerCeiling() {
        Line ld = line(0, 0, 100, 0, Slopetype.ST_HORIZONTAL);
        ld.sidenum[1] = 1;
        ld.frontsector = sec(0, 100);  // ceil = 100 (lower)
        ld.backsector  = sec(0, 200);  // ceil = 200

        PMaputl.pLineOpening(ld);

        assertEquals(100, PMaputl.opentop);
    }

    private static Sector sec(int floor, int ceiling) {
        Sector s = new Sector();
        s.floorheight = floor;
        s.ceilingheight = ceiling;
        return s;
    }

    // ----- P_BlockLinesIterator / P_BlockThingsIterator -----
    //
    // Synthetic 2x2 blockmap. Layout in blockmaplump[]:
    //   [0..3]  header (origin x, y, width=2, height=2)
    //   [4..7]  per-block list start offsets (block (0,0)..(1,1))
    //   [8..]   the per-block lists (each is a sequence of line
    //           indices into PSetup.lines[], terminated with -1).

    // Helper: install a small synthetic blockmap world.
    // Lines are populated separately via setLines(); blocklinks
    // for thing-iterator tests via setBlocklinks().
    private static void installBlockmap(int width, int height, int[] perBlockOffsets, int[] tail) {
        // Header (4 ints) + perBlockOffsets (width*height ints) + tail.
        int[] lump = new int[4 + perBlockOffsets.length + tail.length];
        lump[0] = 0; // bmaporgx
        lump[1] = 0; // bmaporgy
        lump[2] = width;
        lump[3] = height;
        for (int i = 0; i < perBlockOffsets.length; i++) {
            lump[4 + i] = perBlockOffsets[i];
        }
        for (int i = 0; i < tail.length; i++) {
            lump[4 + perBlockOffsets.length + i] = tail[i];
        }
        PSetup.blockmaplump = lump;
        PSetup.blockmap = lump;
        PSetup.bmapwidth = width;
        PSetup.bmapheight = height;
        PSetup.blocklinks = new Mobj[width * height];
    }

    // Out-of-bounds (negative or beyond bmapwidth/height) returns true
    // without invoking the callback.
    @Test
    void blockLinesIteratorOutOfBoundsReturnsTrueWithoutCalling() {
        // 4-block lump: 4 header + 4 offsets + tail. All blocks point
        // at index 8 = the tail terminator → all empty.
        installBlockmap(2, 2, new int[] { 8, 8, 8, 8 }, new int[] { -1 });
        PSetup.lines = new Line[] {};

        boolean[] called = { false };
        java.util.function.Predicate<Line> cb = ld -> { called[0] = true; return true; };

        assertTrue(PMaputl.pBlockLinesIterator(-1, 0, cb));
        assertTrue(PMaputl.pBlockLinesIterator(0, -1, cb));
        assertTrue(PMaputl.pBlockLinesIterator(2, 0, cb));
        assertTrue(PMaputl.pBlockLinesIterator(0, 2, cb));
        assertFalse(called[0]);
    }

    // Empty block (offset → list of just -1) returns true with no calls.
    @Test
    void blockLinesIteratorEmptyBlockReturnsTrue() {
        // Lump layout: 4 header ints + 1 per-block offset + 1 tail.
        // The single block points at index 5 (= header + perBlockOffsets),
        // which is the -1 terminator → empty list.
        installBlockmap(1, 1, new int[] { 5 }, new int[] { -1 });
        PSetup.lines = new Line[] {};

        int[] count = { 0 };
        boolean rtn = PMaputl.pBlockLinesIterator(0, 0, ld -> { count[0]++; return true; });

        assertTrue(rtn);
        assertEquals(0, count[0]);
    }

    // Visits each line in the block once.
    @Test
    void blockLinesIteratorVisitsAllLinesInBlock() {
        // Block (0,0) → list at offset 5: lines [0, 1, 2, -1].
        installBlockmap(1, 1, new int[] { 5 }, new int[] { 0, 1, 2, -1 });

        Line l0 = new Line(); Line l1 = new Line(); Line l2 = new Line();
        PSetup.lines = new Line[] { l0, l1, l2 };
        PSetup.numlines = 3;

        // Bump validcount so the post-stamp comparison guarantees
        // these lines look fresh.
        RMain.validcount++;

        List<Line> visited = new ArrayList<>();
        boolean rtn = PMaputl.pBlockLinesIterator(0, 0, ld -> { visited.add(ld); return true; });

        assertTrue(rtn);
        assertEquals(List.of(l0, l1, l2), visited);
    }

    // validcount-stamped lines are skipped on subsequent passes.
    @Test
    void blockLinesIteratorSkipsLinesAlreadyStamped() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { 0, 1, -1 });
        Line l0 = new Line(); Line l1 = new Line();
        PSetup.lines = new Line[] { l0, l1 };

        RMain.validcount++;
        // Pre-stamp l0 with the current count.
        l0.validcount = RMain.validcount;

        List<Line> visited = new ArrayList<>();
        PMaputl.pBlockLinesIterator(0, 0, ld -> { visited.add(ld); return true; });

        // l0 was stamped → skipped; l1 was visited.
        assertEquals(List.of(l1), visited);
    }

    // Callback returns false → iterator stops + propagates false.
    @Test
    void blockLinesIteratorStopsWhenCallbackReturnsFalse() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { 0, 1, 2, -1 });
        PSetup.lines = new Line[] { new Line(), new Line(), new Line() };

        RMain.validcount++;

        int[] count = { 0 };
        boolean rtn = PMaputl.pBlockLinesIterator(0, 0, ld -> {
            count[0]++;
            return count[0] < 2; // stop after the second call returns false
        });

        assertFalse(rtn);
        assertEquals(2, count[0]);
    }

    // After a complete pass, every visited line carries the current
    // validcount stamp.
    @Test
    void blockLinesIteratorStampsVisitedLines() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { 0, 1, -1 });
        Line l0 = new Line(); Line l1 = new Line();
        PSetup.lines = new Line[] { l0, l1 };

        RMain.validcount++;
        int target = RMain.validcount;

        PMaputl.pBlockLinesIterator(0, 0, ld -> true);

        assertEquals(target, l0.validcount);
        assertEquals(target, l1.validcount);
    }

    // ----- P_BlockThingsIterator -----

    // Out-of-bounds returns true, callback never fires.
    @Test
    void blockThingsIteratorOutOfBoundsReturnsTrueWithoutCalling() {
        installBlockmap(2, 2, new int[] { 8, 8, 8, 8 }, new int[] { -1 });
        // (Iterator never reads blockmap on the bounds-fail path; the
        // valid offsets just keep the lump from looking malformed.)
        boolean[] called = { false };
        java.util.function.Predicate<Mobj> cb = m -> { called[0] = true; return true; };

        assertTrue(PMaputl.pBlockThingsIterator(-1, 0, cb));
        assertTrue(PMaputl.pBlockThingsIterator(0, 2, cb));
        assertFalse(called[0]);
    }

    // Empty cell (no head mobj) → true, no calls.
    @Test
    void blockThingsIteratorEmptyCellReturnsTrue() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { -1 });
        // blocklinks[0] left null by installBlockmap.
        int[] count = { 0 };

        boolean rtn = PMaputl.pBlockThingsIterator(0, 0, m -> { count[0]++; return true; });

        assertTrue(rtn);
        assertEquals(0, count[0]);
    }

    // Walks the bnext chain from blocklinks[block] head to the end.
    @Test
    void blockThingsIteratorWalksBnextChain() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { -1 });
        Mobj a = new Mobj(); Mobj b = new Mobj(); Mobj c = new Mobj();
        a.bnext = b;
        b.bnext = c;
        // c.bnext stays null → end of chain
        PSetup.blocklinks[0] = a;

        List<Mobj> visited = new ArrayList<>();
        boolean rtn = PMaputl.pBlockThingsIterator(0, 0, m -> { visited.add(m); return true; });

        assertTrue(rtn);
        assertEquals(List.of(a, b, c), visited);
    }

    // Callback returns false stops the walk and propagates false.
    @Test
    void blockThingsIteratorStopsWhenCallbackReturnsFalse() {
        installBlockmap(1, 1, new int[] { 5 }, new int[] { -1 });
        Mobj a = new Mobj(); Mobj b = new Mobj(); Mobj c = new Mobj();
        a.bnext = b; b.bnext = c;
        PSetup.blocklinks[0] = a;

        int[] count = { 0 };
        boolean rtn = PMaputl.pBlockThingsIterator(0, 0, m -> {
            count[0]++;
            return m != b; // stop on b
        });

        assertFalse(rtn);
        assertEquals(2, count[0]);  // visited a, then b returned false
    }
}
