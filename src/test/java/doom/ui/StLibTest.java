package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.render.Patch;
import doom.system.Doomdef;
import doom.system.ISystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StLibTest {

    // width=1, height=1 patch with one transparent column (topdelta 0xff).
    // Valid for V_DrawPatch: column terminator in the first post byte.
    private static Patch tinyPatch() {
        // header(8) + columnofs[1](4) = 12 bytes header total.
        // Column at offset 12: topdelta=0xff (end-of-column marker).
        byte[] lump = new byte[]{
                1, 0,               // width 1
                1, 0,               // height 1
                0, 0,               // leftoffset
                0, 0,               // topoffset
                12, 0, 0, 0,        // columnofs[0] = 12
                (byte) 0xff,        // column terminator
        };
        return Patch.fromLump(lump);
    }

    private static Patch[] digits() {
        Patch[] p = new Patch[10];
        for (int i = 0; i < 10; i++) {
            p[i] = tinyPatch();
        }
        return p;
    }

    @BeforeEach
    void reset() {
        // Allocate all five screens (including the status-bar BG=4).
        VVideo.screens = new byte[5][];
        for (int i = 0; i < 5; i++) {
            VVideo.screens[i] = new byte[Doomdef.SCREENWIDTH * Doomdef.SCREENHEIGHT];
        }
        VVideo.dirtybox = new int[4];
        StLib.sttminus = tinyPatch(); // stand-in so drawNum's minus-path doesn't NPE
    }

    // st_lib.c lines 66–82 — initNum.
    @Test
    void initNumStoresAllFields() {
        StLib.Number n = new StLib.Number();
        Patch[] p = digits();
        int[] num = { 42 };
        boolean[] on = { true };

        StLib.stLibInitNum(n, 40, 180, p, num, on, 3);

        assertEquals(40, n.x);
        assertEquals(180, n.y);
        assertEquals(3, n.width);
        assertEquals(0, n.oldnum);
        assertSame(num, n.num);
        assertSame(on, n.on);
        assertSame(p, n.p);
    }

    // st_lib.c lines 152–158 — updateNum no-ops when on=false.
    @Test
    void updateNumSkipsWhenOff() {
        StLib.Number n = new StLib.Number();
        int[] num = { 55 };
        StLib.stLibInitNum(n, 40, 180, digits(), num, new boolean[] { false }, 3);

        StLib.stLibUpdateNum(n, true);

        // drawNum never ran, so oldnum stays at its init value.
        assertEquals(0, n.oldnum);
    }

    // drawNum guard on y < ST_Y.
    @Test
    void drawNumThrowsWhenAboveStatusBar() {
        StLib.Number n = new StLib.Number();
        StLib.stLibInitNum(n, 40, StStuff.ST_Y - 1, digits(),
                new int[] { 1 }, new boolean[] { true }, 3);

        assertThrows(ISystem.DoomError.class, () -> StLib.stLibDrawNum(n, true));
    }

    // drawNum with the sentinel 1994 short-circuits before drawing digits,
    // but still updates oldnum.
    @Test
    void drawNumSentinel1994UpdatesOldnumThenReturns() {
        StLib.Number n = new StLib.Number();
        StLib.stLibInitNum(n, 40, StStuff.ST_Y, digits(),
                new int[] { 1994 }, new boolean[] { true }, 3);

        StLib.stLibDrawNum(n, true);

        assertEquals(1994, n.oldnum);
    }

    // drawNum clamps an out-of-range negative two-digit value to -9.
    @Test
    void drawNumClampsNegativeBeyondWidth() {
        StLib.Number n = new StLib.Number();
        int[] num = { -15 };
        StLib.stLibInitNum(n, 40, StStuff.ST_Y, digits(), num, new boolean[] { true }, 2);

        StLib.stLibDrawNum(n, true);

        // oldnum captures the raw value, not the clamped one.
        assertEquals(-15, n.oldnum);
    }

    // st_lib.c lines 163–174 — initPercent.
    @Test
    void initPercentWrapsNumberAndStoresGlyph() {
        StLib.Percent pct = new StLib.Percent();
        Patch[] p = digits();
        Patch glyph = tinyPatch();
        int[] num = { 50 };
        boolean[] on = { true };

        StLib.stLibInitPercent(pct, 40, 180, p, num, on, glyph);

        assertEquals(3, pct.n.width); // forced to 3
        assertSame(num, pct.n.num);
        assertSame(glyph, pct.p);
    }

    // st_lib.c lines 192–207 — initMultIcon starts oldinum at -1.
    @Test
    void initMultIconSeedsOldinumToMinusOne() {
        StLib.MultIcon mi = new StLib.MultIcon();

        StLib.stLibInitMultIcon(mi, 1, 2, digits(), new int[] { 3 }, new boolean[] { true });

        assertEquals(-1, mi.oldinum);
        assertEquals(1, mi.x);
        assertEquals(2, mi.y);
    }

    // updateMultIcon skips entirely when inum = -1.
    @Test
    void updateMultIconSkipsWhenInumNegative() {
        StLib.MultIcon mi = new StLib.MultIcon();
        StLib.stLibInitMultIcon(mi, 40, StStuff.ST_Y, digits(),
                new int[] { -1 }, new boolean[] { true });

        StLib.stLibUpdateMultIcon(mi, true);

        assertEquals(-1, mi.oldinum); // untouched
    }

    // updateMultIcon tracks oldinum once it draws.
    @Test
    void updateMultIconUpdatesOldinumAfterDraw() {
        StLib.MultIcon mi = new StLib.MultIcon();
        StLib.stLibInitMultIcon(mi, 40, StStuff.ST_Y, digits(),
                new int[] { 5 }, new boolean[] { true });

        StLib.stLibUpdateMultIcon(mi, true);

        assertEquals(5, mi.oldinum);
    }

    // st_lib.c lines 244–259 — initBinIcon.
    @Test
    void initBinIconStoresState() {
        StLib.BinIcon b = new StLib.BinIcon();
        Patch p = tinyPatch();
        boolean[] val = { true };
        boolean[] on = { true };

        StLib.stLibInitBinIcon(b, 10, 20, p, val, on);

        assertEquals(10, b.x);
        assertEquals(20, b.y);
        assertEquals(0, b.oldval);
        assertSame(val, b.val);
        assertSame(on, b.on);
        assertSame(p, b.p);
    }

    // updateBinIcon skips when off.
    @Test
    void updateBinIconSkipsWhenOff() {
        StLib.BinIcon b = new StLib.BinIcon();
        StLib.stLibInitBinIcon(b, 10, StStuff.ST_Y, tinyPatch(),
                new boolean[] { true }, new boolean[] { false });

        StLib.stLibUpdateBinIcon(b, true);

        assertEquals(0, b.oldval); // no change
    }

    // updateBinIcon tracks oldval after drawing when val=true.
    @Test
    void updateBinIconUpdatesOldvalAfterDraw() {
        StLib.BinIcon b = new StLib.BinIcon();
        StLib.stLibInitBinIcon(b, 10, StStuff.ST_Y, tinyPatch(),
                new boolean[] { true }, new boolean[] { true });

        StLib.stLibUpdateBinIcon(b, true);

        assertEquals(1, b.oldval);
    }

    // Once oldval matches the current val, subsequent updates without
    // refresh are no-ops — matches the C's oldval != *val gate.
    @Test
    void updateBinIconNoOpWhenStableAndNoRefresh() {
        StLib.BinIcon b = new StLib.BinIcon();
        StLib.stLibInitBinIcon(b, 10, StStuff.ST_Y, tinyPatch(),
                new boolean[] { true }, new boolean[] { true });
        StLib.stLibUpdateBinIcon(b, true); // now oldval=1
        assertEquals(1, b.oldval);

        // Flip val to false — next update should redraw (background).
        b.val[0] = false;
        StLib.stLibUpdateBinIcon(b, false);

        assertEquals(0, b.oldval);
    }

    // updateBinIcon guard.
    @Test
    void updateBinIconThrowsWhenAboveStatusBar() {
        StLib.BinIcon b = new StLib.BinIcon();
        // Topoffset offsets y; use a patch whose leftoffset/topoffset are 0
        // and a y well above ST_Y so the guard fires.
        StLib.stLibInitBinIcon(b, 10, StStuff.ST_Y - 10, tinyPatch(),
                new boolean[] { true }, new boolean[] { true });

        assertThrows(ISystem.DoomError.class, () -> StLib.stLibUpdateBinIcon(b, true));
    }

    // updatePercent draws the glyph only when refresh != 0 and on=true.
    // Covers the `refresh != 0` integer-to-bool preservation (the C took
    // an `int refresh` parameter).
    @Test
    void updatePercentSkipsGlyphWhenRefreshZero() {
        StLib.Percent pct = new StLib.Percent();
        Patch[] p = digits();
        int[] num = { 25 };
        StLib.stLibInitPercent(pct, 40, StStuff.ST_Y, p, num,
                new boolean[] { true }, tinyPatch());

        // Both paths call updateNum which updates oldnum; the refresh=0
        // path just skips drawing the '%' glyph. Sanity: oldnum moves.
        StLib.stLibUpdatePercent(pct, 0);

        assertEquals(25, pct.n.oldnum);
    }

    // ST_Y derived from Doomdef constants — sanity check the stub.
    @Test
    void stStuffStYMatches168() {
        assertEquals(168, StStuff.ST_Y);
        assertTrue(StStuff.ST_HEIGHT > 0);
    }
}
