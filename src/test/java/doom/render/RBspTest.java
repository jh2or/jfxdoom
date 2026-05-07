package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.play.PSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RBspTest {

    @BeforeEach
    void resetRenderer() {
        RMain.viewx = 0;
        RMain.viewy = 0;
        RMain.viewz = 0;
        RMain.viewangle = 0;
        RMain.viewwidth = 320;
        RMain.clipangle = Tables.ANG90 / 2; // ~45° default fov
        RMain.sscount = 0;

        RBsp.rClearDrawSegs();
        RBsp.rClearClipSegs();

        // Seed viewangletox[] with a monotonic ramp so the BSP walk
        // doesn't NPE / produce degenerate columns. Real engine
        // builds this in R_InitTextureMapping; the unit tests just
        // need any valid mapping.
        for (int i = 0; i < RMain.viewangletox.length; i++) {
            RMain.viewangletox[i] = i;
        }
    }

    // R_ClearDrawSegs resets the next-free index.
    @Test
    void clearDrawSegsResetsCounter() {
        RBsp.dsP = 5;
        RBsp.rClearDrawSegs();
        assertEquals(0, RBsp.dsP);
    }

    // R_ClearClipSegs seeds the bounding sentinel pair.
    @Test
    void clearClipSegsSeedsSentinels() {
        RBsp.rClearClipSegs();
        assertEquals(-0x7fffffff, RBsp.solidsegs[0].first);
        assertEquals(-1, RBsp.solidsegs[0].last);
        assertEquals(RMain.viewwidth, RBsp.solidsegs[1].first);
        assertEquals(0x7fffffff, RBsp.solidsegs[1].last);
        assertEquals(2, RBsp.newend);
    }

    // R_ClipSolidWallSegment with a fresh clip list inserts a new
    // entry between the sentinels and bumps the drawseg list.
    @Test
    void clipSolidWallSegmentInsertsNewClippost() {
        RBsp.rClipSolidWallSegment(100, 200);
        // newend bumped to 3 (sentinels at 0 and 2; new clip at 1).
        assertEquals(3, RBsp.newend);
        assertEquals(100, RBsp.solidsegs[1].first);
        assertEquals(200, RBsp.solidsegs[1].last);
        // R_StoreWallRange was called → drawsegs got an entry.
        assertEquals(1, RBsp.dsP);
    }

    // A second call adjacent to the first merges into one range.
    @Test
    void clipSolidWallSegmentMergesAdjacentRanges() {
        RBsp.rClipSolidWallSegment(100, 200);
        RBsp.rClipSolidWallSegment(201, 250);
        // Both ranges merge into solidsegs[1].
        assertEquals(100, RBsp.solidsegs[1].first);
        assertEquals(250, RBsp.solidsegs[1].last);
        // Both spawned drawsegs.
        assertEquals(2, RBsp.dsP);
    }

    // A range entirely contained within an existing solidseg paints
    // nothing (no new drawseg).
    @Test
    void clipSolidWallSegmentContainedDoesNothing() {
        RBsp.rClipSolidWallSegment(0, 300);
        int dsBefore = RBsp.dsP;
        RBsp.rClipSolidWallSegment(50, 100);
        assertEquals(dsBefore, RBsp.dsP);
    }

    // R_ClipPassWallSegment paints the visible fragment but doesn't
    // claim the column range (clip list unchanged).
    @Test
    void clipPassWallSegmentDoesNotClaimRange() {
        RBsp.rClipPassWallSegment(100, 200);
        // Clip list unchanged from clear (still just sentinels).
        assertEquals(2, RBsp.newend);
        // But it did paint.
        assertEquals(1, RBsp.dsP);
    }

    // R_CheckBBox returns true for a bbox containing the viewpoint
    // (boxpos == 5).
    @Test
    void checkBBoxOnTopOfBoxReturnsTrue() {
        // viewx=0, viewy=0. Bbox brackets origin → boxx=1, boxy=1
        // → boxpos = (1<<2)|1 = 5.
        int[] bbox = new int[4];
        bbox[doom.play.MBbox.BOXLEFT]   = -10 * Fixed.FRACUNIT;
        bbox[doom.play.MBbox.BOXRIGHT]  =  10 * Fixed.FRACUNIT;
        bbox[doom.play.MBbox.BOXBOTTOM] = -10 * Fixed.FRACUNIT;
        bbox[doom.play.MBbox.BOXTOP]    =  10 * Fixed.FRACUNIT;

        assertTrue(RBsp.rCheckBBox(bbox));
    }

    // checkcoord LUT matches the C source verbatim.
    @Test
    void checkcoordLutMatchesCSource() {
        // Just spot-check a few entries.
        assertEquals(3, RBsp.checkcoord[0][0]);
        assertEquals(0, RBsp.checkcoord[0][1]);
        assertEquals(2, RBsp.checkcoord[0][2]);
        assertEquals(1, RBsp.checkcoord[0][3]);
        // boxpos 5 is the "viewpoint inside box" case — never read
        // since R_CheckBBox returns true before LUT access.
        // boxpos 8: {2,0,3,1}
        assertEquals(2, RBsp.checkcoord[8][0]);
        assertEquals(0, RBsp.checkcoord[8][1]);
        assertEquals(3, RBsp.checkcoord[8][2]);
        assertEquals(1, RBsp.checkcoord[8][3]);
    }

    // MAXDRAWSEGS / MAXSEGS constants match C.
    @Test
    void constantsMatchCSource() {
        assertEquals(256, RBsp.MAXDRAWSEGS);
        assertEquals(32, RBsp.MAXSEGS);
        assertEquals(RBsp.MAXDRAWSEGS, RBsp.drawsegs.length);
    }

    // R_Subsector with no segs visits the subsector but adds nothing
    // to the drawseg list.
    @Test
    void subsectorWithNoSegsBumpsCount() {
        // Build a one-subsector world.
        Sector sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.floorpic = 1;
        sec.ceilingpic = 2;
        sec.lightlevel = 192;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;
        ss.numlines = 0;
        ss.firstline = 0;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.segs = new Seg[0];
        PSetup.numsegs = 0;

        RMain.viewz = 100 * Fixed.FRACUNIT;

        int before = RMain.sscount;
        RBsp.rSubsector(0);

        assertEquals(before + 1, RMain.sscount);
        // floor/ceiling visplanes allocated (viewz between heights).
        assertNotNull(RBsp.floorplane);
        assertNotNull(RBsp.ceilingplane);
        // No drawsegs added.
        assertEquals(0, RBsp.dsP);
    }

    // viewz above ceiling skips the ceiling plane allocation.
    @Test
    void subsectorAboveCeilingSkipsCeilingPlane() {
        Sector sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 50 * Fixed.FRACUNIT;
        sec.floorpic = 0;
        sec.ceilingpic = 0;
        sec.lightlevel = 0;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;
        ss.numlines = 0;
        ss.firstline = 0;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.segs = new Seg[0];
        PSetup.numsegs = 0;

        RMain.viewz = 100 * Fixed.FRACUNIT;
        // Skyflatnum != ceilingpic (both 0 here is a problem — set
        // skyflatnum to something else).
        RSky.skyflatnum = 99;

        RBsp.rSubsector(0);
        // Floor visible (floorheight < viewz).
        assertNotNull(RBsp.floorplane);
        // Ceiling not visible (ceilingheight < viewz, no skyflat).
        assertNull(RBsp.ceilingplane);
    }
}
