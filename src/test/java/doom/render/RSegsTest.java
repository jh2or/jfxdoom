package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RSegsTest {

    @BeforeEach
    void resetRenderer() {
        // Renderer scaffolding.
        RMain.rSetViewSize(11, 0);
        RMain.rExecuteSetViewSize();
        RMain.rInitLightTables();

        // Per-frame view vector.
        RMain.viewx = 0;
        RMain.viewy = 0;
        RMain.viewz = 41 * Fixed.FRACUNIT;
        RMain.viewangle = 0;
        RMain.fixedcolormap = -1;
        RMain.extralight = 0;

        // Per-frame buffer reset.
        RBsp.rClearDrawSegs();
        RBsp.rClearClipSegs();
        RPlane.rClearPlanes();

        // Minimal RData (texturetranslation / textureheight) so
        // R_StoreWallRange's single-sided branch doesn't NPE.
        if (RData.texturetranslation == null) {
            RData.texturetranslation = new int[1];
            RData.textureheight = new int[1];
        }
        // Sky needs to not clobber.
        RSky.skyflatnum = 999;
    }

    private Seg makeSeg(int x1, int y1, int x2, int y2,
                       Sector front, Sector back) {
        Vertex v1 = new Vertex();
        v1.x = x1; v1.y = y1;
        Vertex v2 = new Vertex();
        v2.x = x2; v2.y = y2;
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
        seg.backsector = back;
        return seg;
    }

    private Sector makeSector(int floor, int ceil) {
        Sector s = new Sector();
        s.floorheight = floor;
        s.ceilingheight = ceil;
        s.floorpic = 0;
        s.ceilingpic = 0;
        s.lightlevel = 192;
        return s;
    }

    // ---- R_StoreWallRange: single-sided line ----

    // A single-sided wall stores a drawseg with SIL_BOTH silhouette
    // and bumps RBsp.dsP.
    @Test
    void storeWallRangeSingleSidedClipsBoth() {
        Sector front = makeSector(0, 200 * Fixed.FRACUNIT);
        Seg seg = makeSeg(100 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          200 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          front, /*back=*/ null);
        RBsp.curline = seg;
        RBsp.frontsector = front;
        RBsp.backsector = null;
        RBsp.floorplane = RPlane.rFindPlane(0, front.floorpic, front.lightlevel);
        RBsp.ceilingplane = RPlane.rFindPlane(
                front.ceilingheight, front.ceilingpic, front.lightlevel);

        int dsBefore = RBsp.dsP;
        RBsp.rwAngle1 = 0;
        RSegs.rStoreWallRange(50, 60);

        assertEquals(dsBefore + 1, RBsp.dsP);
        Drawseg ds = RBsp.drawsegs[dsBefore];
        assertNotNull(ds);
        assertEquals(50, ds.x1);
        assertEquals(60, ds.x2);
        assertEquals(Drawseg.SIL_BOTH, ds.silhouette);
        // Single-sided sprtopclip points at screenheightarray
        // (sprite top-clip at the screen height — no ceiling
        // texture above).
        assertSame(RMain.screenheightarray, ds.sprtopclip);
        assertSame(RThings.negonearray, ds.sprbottomclip);
        // ML_MAPPED set on the linedef.
        assertTrue((seg.linedef.flags & Line.ML_MAPPED) != 0);
    }

    // R_StoreWallRange's overflow guard: with ds_p == MAXDRAWSEGS,
    // the call returns without crashing.
    @Test
    void storeWallRangeOverflowReturnsCleanly() {
        Sector front = makeSector(0, 200 * Fixed.FRACUNIT);
        Seg seg = makeSeg(100 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          200 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          front, null);
        RBsp.curline = seg;
        RBsp.frontsector = front;
        RBsp.backsector = null;
        RBsp.dsP = RBsp.MAXDRAWSEGS;

        // No crash, no growth.
        RSegs.rStoreWallRange(50, 60);
        assertEquals(RBsp.MAXDRAWSEGS, RBsp.dsP);
    }

    // ---- HEIGHTBITS / HEIGHTUNIT match C ----

    @Test
    void heightConstantsMatchCSource() {
        assertEquals(12, RSegs.HEIGHTBITS);
        assertEquals(1 << 12, RSegs.HEIGHTUNIT);
    }

    // ---- Two-sided "window" line ----

    // Front and back have identical heights → no upper/lower
    // textures, no markfloor / markceiling. A drawseg lands with
    // SIL_NONE.
    @Test
    void storeWallRangeTwoSidedSamePlaneSeesNoTexture() {
        Sector front = makeSector(0, 200 * Fixed.FRACUNIT);
        Sector back  = makeSector(0, 200 * Fixed.FRACUNIT);
        Seg seg = makeSeg(100 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          200 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          front, back);
        RBsp.curline = seg;
        RBsp.frontsector = front;
        RBsp.backsector = back;
        RBsp.floorplane   = RPlane.rFindPlane(0, 0, 192);
        RBsp.ceilingplane = RPlane.rFindPlane(
                front.ceilingheight, 0, 192);

        int dsBefore = RBsp.dsP;
        RBsp.rwAngle1 = 0;
        RSegs.rStoreWallRange(50, 60);

        assertEquals(dsBefore + 1, RBsp.dsP);
        Drawseg ds = RBsp.drawsegs[dsBefore];
        // No silhouette additions for a "window" with identical
        // heights and view between them.
        assertEquals(0, ds.silhouette);
    }

    // ---- Closed-door (back sector squeezed flat) → SIL_BOTH ----

    @Test
    void storeWallRangeClosedDoorMarksBothSilhouettes() {
        Sector front = makeSector(0, 200 * Fixed.FRACUNIT);
        Sector back  = makeSector(0, 0); // ceiling at floor
        Seg seg = makeSeg(100 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          200 * Fixed.FRACUNIT, 50 * Fixed.FRACUNIT,
                          front, back);
        RBsp.curline = seg;
        RBsp.frontsector = front;
        RBsp.backsector = back;
        RBsp.floorplane   = RPlane.rFindPlane(0, 0, 192);
        RBsp.ceilingplane = RPlane.rFindPlane(
                front.ceilingheight, 0, 192);

        int dsBefore = RBsp.dsP;
        RBsp.rwAngle1 = 0;
        RSegs.rStoreWallRange(50, 60);

        Drawseg ds = RBsp.drawsegs[dsBefore];
        assertTrue((ds.silhouette & Drawseg.SIL_TOP) != 0);
        assertTrue((ds.silhouette & Drawseg.SIL_BOTTOM) != 0);
    }
}
