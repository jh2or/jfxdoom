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
}
