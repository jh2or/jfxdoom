package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.system.Doomdef;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RPlaneTest {

    @BeforeEach
    void resetPlanes() {
        RDraw.viewwidth = Doomdef.SCREENWIDTH;
        RDraw.viewheight = Doomdef.SCREENHEIGHT;
        RSky.skyflatnum = 99;
        // Re-init each test so visplanes[] don't accumulate across.
        RPlane.lastvisplane = 0;
        RPlane.lastopening = 0;
    }

    // R_FindPlane on an empty pool allocates a fresh visplane and
    // populates header fields.
    @Test
    void findPlaneAllocatesFresh() {
        int h = 100 * Fixed.FRACUNIT;
        RPlane.Visplane vp = RPlane.rFindPlane(h, /*picnum*/ 5, /*lightlevel*/ 192);
        assertEquals(1, RPlane.lastvisplane);
        assertEquals(h, vp.height);
        assertEquals(5, vp.picnum);
        assertEquals(192, vp.lightlevel);
        assertEquals(Doomdef.SCREENWIDTH, vp.minx);
        assertEquals(-1, vp.maxx);
        // top[] reset to 0xff sentinel.
        for (int i = 0; i < vp.top.length; i++) {
            assertEquals((byte) 0xff, vp.top[i],
                    "top[" + i + "] not sentinel");
        }
    }

    // Repeated find with the same key returns the same visplane.
    @Test
    void findPlaneReusesMatching() {
        RPlane.Visplane a = RPlane.rFindPlane(50 * Fixed.FRACUNIT, 7, 128);
        RPlane.Visplane b = RPlane.rFindPlane(50 * Fixed.FRACUNIT, 7, 128);
        assertSame(a, b);
        assertEquals(1, RPlane.lastvisplane);
    }

    // Different picnum or lightlevel allocates a separate visplane.
    @Test
    void findPlaneDifferentKeysMakeNewSlots() {
        RPlane.Visplane a = RPlane.rFindPlane(50 * Fixed.FRACUNIT, 7, 128);
        RPlane.Visplane b = RPlane.rFindPlane(50 * Fixed.FRACUNIT, 7, 192);
        RPlane.Visplane c = RPlane.rFindPlane(50 * Fixed.FRACUNIT, 8, 128);
        assertNotSame(a, b);
        assertNotSame(b, c);
        assertNotSame(a, c);
        assertEquals(3, RPlane.lastvisplane);
    }

    // Sky-flat picnum collapses height + lightlevel so all sky
    // columns share one visplane.
    @Test
    void findPlaneCollapsesSkyFlat() {
        RPlane.Visplane a = RPlane.rFindPlane(100 * Fixed.FRACUNIT,
                RSky.skyflatnum, 100);
        RPlane.Visplane b = RPlane.rFindPlane(500 * Fixed.FRACUNIT,
                RSky.skyflatnum, 200);
        assertSame(a, b);
        assertEquals(0, a.height);
        assertEquals(0, a.lightlevel);
    }

    // R_CheckPlane on disjoint columns extends the visplane in
    // place (top[] entirely 0xff in the requested range).
    @Test
    void checkPlaneExtendsWhenColumnsAreClean() {
        RPlane.Visplane vp = RPlane.rFindPlane(100 * Fixed.FRACUNIT, 5, 192);
        RPlane.Visplane back = RPlane.rCheckPlane(vp, 50, 100);
        assertSame(vp, back);
        assertEquals(50, vp.minx);
        assertEquals(100, vp.maxx);
    }

    // R_CheckPlane splits when the requested range overlaps
    // already-filled columns.
    @Test
    void checkPlaneSplitsOnDirtyColumns() {
        RPlane.Visplane vp = RPlane.rFindPlane(100 * Fixed.FRACUNIT, 5, 192);
        // Pretend columns 50..100 already have a fragment.
        vp.minx = 50;
        vp.maxx = 100;
        for (int i = 50; i <= 100; i++) {
            vp.top[i + 1] = 0; // not 0xff
        }

        RPlane.Visplane fresh = RPlane.rCheckPlane(vp, 60, 90);
        assertNotSame(vp, fresh);
        assertEquals(60, fresh.minx);
        assertEquals(90, fresh.maxx);
        assertEquals(2, RPlane.lastvisplane);
    }

    // R_ClearPlanes resets the per-frame buffers.
    @Test
    void clearPlanesResetsState() {
        RPlane.rFindPlane(100 * Fixed.FRACUNIT, 5, 192);
        RPlane.rFindPlane(50 * Fixed.FRACUNIT, 7, 128);
        assertEquals(2, RPlane.lastvisplane);

        RPlane.rClearPlanes();

        assertEquals(0, RPlane.lastvisplane);
        assertEquals(0, RPlane.lastopening);
        // floorclip / ceilingclip seeded.
        for (int i = 0; i < RDraw.viewwidth; i++) {
            assertEquals(RDraw.viewheight, RPlane.floorclip[i]);
            assertEquals(-1, RPlane.ceilingclip[i]);
        }
        // cachedheight reset.
        for (int i = 0; i < RPlane.cachedheight.length; i++) {
            assertEquals(0, RPlane.cachedheight[i]);
        }
    }

    // R_MakeSpans is bookkeeping-only: with R_MapPlane stubbed it
    // just walks the t1/b1/t2/b2 span stacks and updates spanstart.
    @Test
    void makeSpansSeedsNewSpanStarts() {
        // t1=t2=10, b1=b2=20 → no closing of existing spans, no new
        // openings (the conditions `t1 < t2` etc. all fail).
        // Use t2=5 (smaller than t1=10) → "new span starting at x"
        // path fires for rows 5..10.
        RPlane.spanstart[5] = -999;
        RPlane.spanstart[6] = -999;
        RPlane.rMakeSpans(/*x=*/ 100, /*t1=*/ 10, /*b1=*/ 20,
                /*t2=*/ 5, /*b2=*/ 20);
        // Rows 5..9 (since t2 < t1) get spanstart = 100.
        for (int y = 5; y < 10; y++) {
            assertEquals(100, RPlane.spanstart[y]);
        }
    }

    // Constants match C source.
    @Test
    void constantsMatchCSource() {
        assertEquals(128, RPlane.MAXVISPLANES);
        assertEquals(Doomdef.SCREENWIDTH * 64, RPlane.MAXOPENINGS);
        assertEquals(RPlane.MAXVISPLANES, RPlane.visplanes.length);
        assertEquals(RPlane.MAXOPENINGS, RPlane.openings.length);
    }

    // Pool overflow throws an iError.
    @Test
    void findPlaneOverflowThrows() {
        for (int i = 0; i < RPlane.MAXVISPLANES; i++) {
            // each call has a unique picnum so they all allocate.
            RPlane.rFindPlane(i, i, i);
        }
        assertEquals(RPlane.MAXVISPLANES, RPlane.lastvisplane);

        try {
            RPlane.rFindPlane(999, 999, 999);
            assertTrue(false, "expected DoomError");
        } catch (doom.system.ISystem.DoomError e) {
            // expected
        }
    }

    // ---- R_MapPlane ----

    // Sets ds globals from row + plane height + view-vector inputs.
    @Test
    void mapPlanePopulatesDsGlobals() {
        // Need r_main scaffolding — viewangle/viewx/viewy/centerxfrac
        // /xtoviewangle/yslope/distscale/zlight/fixedcolormap.
        RMain.rSetViewSize(11, 0);
        RMain.rExecuteSetViewSize();
        RMain.rInitLightTables();

        // Per-frame view vector.
        RMain.viewx = 0;
        RMain.viewy = 0;
        RMain.viewangle = 0;
        RMain.fixedcolormap = -1; // use light tables

        // basexscale / baseyscale — derived from viewangle in
        // R_ClearPlanes; ensure the cache reset.
        RPlane.lastvisplane = 0;
        RPlane.rClearPlanes();

        // Pick a flat plane 100 fixed-units below the viewer.
        RPlane.planeheight = 100 * Fixed.FRACUNIT;
        // Wire planezlight to a non-null light row.
        RPlane.planezlight = RMain.zlight[8];

        // rDrawSpan reads dsSource (the 64×64 flat) and
        // dsColormap; provide both so the dispatch doesn't AIOOBE.
        // colormaps must have at least 33 rows × 256 entries.
        if (RData.colormaps == null) {
            RData.colormaps = new byte[34 * 256];
        }
        RDraw.dsSource = new byte[64 * 64];
        RDraw.dsSourceOfs = 0;
        // VVideo.screens[0] must exist for rDrawSpan to write into.
        if (doom.ui.VVideo.screens[0] == null) {
            doom.ui.VVideo.screens[0] =
                    new byte[doom.system.Doomdef.SCREENWIDTH
                           * doom.system.Doomdef.SCREENHEIGHT];
        }

        // Map a 1-pixel span on the screen middle.
        int y = RDraw.viewheight / 2 + 10;  // below horizon
        int x1 = RMain.viewwidth / 2;
        int x2 = x1;
        RPlane.rMapPlane(y, x1, x2);

        // ds globals updated.
        assertEquals(y, RDraw.dsY);
        assertEquals(x1, RDraw.dsX1);
        assertEquals(x2, RDraw.dsX2);
        // dsColormap pointing at RData.colormaps with offset from
        // planezlight.
        assertSame(RData.colormaps, RDraw.dsColormap);
    }

    // ---- R_DrawPlanes (integration smoke test against E1M1) ----

    @Test
    void drawPlanesSmokesAgainstE1M1() throws java.io.IOException {
        java.nio.file.Path wad = null;
        for (String c : new String[] {
                "reference-wads/DOOM.WAD",
                "reference-wads/doom.wad",
                "reference-wads/doom1.wad",
                "reference-wads/DOOM1.WAD" }) {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(c))) {
                wad = java.nio.file.Paths.get(c).toAbsolutePath();
                break;
            }
        }
        if (wad == null) {
            return;     // no WAD — skip silently
        }

        doom.wad.ZZone.zInit();
        doom.wad.WWad.resetForTests();
        doom.render.RData.resetForTests();
        doom.wad.WWad.wInitFile(wad.toString());
        doom.render.RData.rInitData();

        // Framebuffer the spans paint into.
        doom.ui.VVideo.vInit();

        // Minimal renderer setup.
        RMain.rSetViewSize(11, 0);
        RMain.rExecuteSetViewSize();
        RMain.rInitLightTables();
        RSky.rInitSkyMap();

        // Per-frame state.
        RMain.viewx = 0;
        RMain.viewy = 0;
        RMain.viewz = 41 * Fixed.FRACUNIT;
        RMain.viewangle = 0;
        RMain.fixedcolormap = -1;
        RMain.extralight = 0;

        RPlane.rClearPlanes();
        RBsp.rClearDrawSegs();

        // Allocate one sky visplane spanning a few columns. Sky is
        // simpler than a regular flat — uses dcSource (column ref
        // from R_GetColumn on the sky texture) which is loaded by
        // rInitData. A single column-row gets dispatched per column.
        RPlane.Visplane vp = RPlane.rFindPlane(
                /*height=*/ 0,
                /*picnum=*/ RSky.skyflatnum,
                /*lightlevel=*/ 0);
        vp.minx = 100;
        vp.maxx = 110;
        for (int x = vp.minx; x <= vp.maxx; x++) {
            // Provide a single-row span at row viewheight/2 + 5 so
            // the column-walk has something to dispatch.
            vp.top[x + 1]    = (byte) (RDraw.viewheight / 2 + 5);
            vp.bottom[x + 1] = (byte) (RDraw.viewheight / 2 + 5);
        }

        // Should not throw — walks the visplane and paints a few
        // sky rows.
        RPlane.rDrawPlanes();
    }
}
