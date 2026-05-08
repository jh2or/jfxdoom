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
        RMain.fixedcolormap = -1;

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

    // Wire the minimum seg + sector state for tests that exercise
    // RBsp.rClipSolidWallSegment / rClipPassWallSegment, which now
    // dispatch into the real RSegs.rStoreWallRange. Vertices are
    // offset from the origin so RMain.rPointToDist's Fixed.div(dx, dy)
    // doesn't divide by zero (the C has the same invariant — segs
    // never coincide with the viewer in real geometry). Floor/ceiling
    // textures are out of bounds for RData (which isn't initialised
    // in this unit test); the no-texture path through R_StoreWallRange
    // is exercised by leaving midtexture==0 on the sidedef and
    // making the seg single-sided so segtextured stays false.
    private void setupCurline() {
        Sector front = new Sector();
        front.floorheight = 0;
        front.ceilingheight = 200 * doom.fixed.Fixed.FRACUNIT;
        front.floorpic = 0;
        front.ceilingpic = 0;
        front.lightlevel = 192;
        Vertex v1 = new Vertex();
        v1.x = 100 * doom.fixed.Fixed.FRACUNIT;
        v1.y = 50 * doom.fixed.Fixed.FRACUNIT;
        Vertex v2 = new Vertex();
        v2.x = 200 * doom.fixed.Fixed.FRACUNIT;
        v2.y = 50 * doom.fixed.Fixed.FRACUNIT;
        Side sd = new Side();
        sd.midtexture = 0;
        sd.toptexture = 0;
        sd.bottomtexture = 0;
        sd.textureoffset = 0;
        sd.rowoffset = 0;
        sd.sector = front;
        Line ld = new Line();
        ld.flags = 0;
        ld.special = 0;
        ld.tag = 0;
        Seg seg = new Seg();
        seg.v1 = v1;
        seg.v2 = v2;
        seg.angle = 0;
        seg.offset = 0;
        seg.linedef = ld;
        seg.sidedef = sd;
        seg.frontsector = front;
        seg.backsector = null;
        RBsp.curline = seg;
        RBsp.frontsector = front;
        RBsp.backsector = null;

        // Minimal RData state. R_StoreWallRange's single-sided
        // branch reads texturetranslation[sidedef.midtexture] (=0)
        // and textureheight[idx] when ML_DONTPEGBOTTOM is set;
        // sidedef has no texture flags so the texture-pixel path
        // (segtextured=false) skips the height read.
        if (RData.texturetranslation == null) {
            RData.texturetranslation = new int[1];
            RData.textureheight = new int[1];
        }

        // R_StoreWallRange writes ceilingplane / floorplane via
        // markceiling/markfloor — visplane allocator must be ready.
        // Set viewz between front floor and ceiling so both planes
        // are visible.
        RMain.viewz = 41 * doom.fixed.Fixed.FRACUNIT;
        RPlane.lastvisplane = 0;
        RPlane.lastopening = 0;
        for (int i = 0; i < RPlane.floorclip.length; i++) {
            RPlane.floorclip[i] = (short) RDraw.viewheight;
            RPlane.ceilingclip[i] = -1;
        }
        RBsp.floorplane = RPlane.rFindPlane(0,
                front.floorpic, front.lightlevel);
        RBsp.ceilingplane = RPlane.rFindPlane(0,
                front.ceilingpic, front.lightlevel);
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
        setupCurline();
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
        setupCurline();
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
        setupCurline();
        RBsp.rClipSolidWallSegment(0, 300);
        int dsBefore = RBsp.dsP;
        RBsp.rClipSolidWallSegment(50, 100);
        assertEquals(dsBefore, RBsp.dsP);
    }

    // R_ClipPassWallSegment paints the visible fragment but doesn't
    // claim the column range (clip list unchanged).
    @Test
    void clipPassWallSegmentDoesNotClaimRange() {
        setupCurline();
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
