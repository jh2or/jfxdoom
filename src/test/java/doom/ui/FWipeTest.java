package doom.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.MBbox;
import doom.play.MRandom;
import doom.system.Doomdef;
import doom.wad.ZZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FWipeTest {

    private static final int W = Doomdef.SCREENWIDTH;
    private static final int H = Doomdef.SCREENHEIGHT;

    @BeforeEach
    void reset() {
        ZZone.zInit();
        VVideo.resetForTests();
        VVideo.vInit();
        MBbox.clearBox(VVideo.dirtybox);
        FWipe.go = false;
        FWipe.wipe_scr_start = null;
        FWipe.wipe_scr_end = null;
        FWipe.wipe_scr = null;
        FWipe.y = null;
        MRandom.mClearRandom();
    }

    // shittyColMajorXform is a byte-pair transpose: `array[y*w+x]` (short
    // semantics) moves to `array[x*h+y]`. Run a 2x3 short grid and
    // verify byte-by-byte.
    @Test
    void colMajorXformTransposesShorts() {
        // 2x3 shorts = 6 entries = 12 bytes. Fill with unique pairs so
        // misordering would be obvious.
        int width = 2;
        int height = 3;
        byte[] arr = new byte[width * height * 2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 2;
                arr[idx]     = (byte) (10 * (y + 1) + x);
                arr[idx + 1] = (byte) (100 + 10 * (y + 1) + x);
            }
        }

        FWipe.wipeShittyColMajorXform(arr, width, height);

        // After transform, position (x,y) in row-major becomes (y,x) in
        // the transposed layout: dest[x*h+y] = src[y*w+x].
        byte[] expected = new byte[arr.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dstIdx = (x * height + y) * 2;
                expected[dstIdx]     = (byte) (10 * (y + 1) + x);
                expected[dstIdx + 1] = (byte) (100 + 10 * (y + 1) + x);
            }
        }

        assertArrayEquals(expected, arr);
    }

    // Initing a color-fade copies start → scr exactly.
    @Test
    void initColorXFormCopiesStartToScr() {
        FWipe.wipe_scr_start = new byte[16];
        FWipe.wipe_scr_end = new byte[16];
        FWipe.wipe_scr = new byte[16];
        for (int i = 0; i < 16; i++) FWipe.wipe_scr_start[i] = (byte) (i + 1);

        int rc = FWipe.wipeInitColorXForm(4, 4, 0);

        assertEquals(0, rc);
        for (int i = 0; i < 16; i++) {
            assertEquals((byte) (i + 1), FWipe.wipe_scr[i]);
        }
    }

    // doColorXForm: when wipe_scr == wipe_scr_end, return 1 (done),
    // nothing changes.
    @Test
    void doColorXFormReportsDoneWhenEqual() {
        FWipe.wipe_scr = new byte[] { 10, 20, 30, 40 };
        FWipe.wipe_scr_end = new byte[] { 10, 20, 30, 40 };
        int rc = FWipe.wipeDoColorXForm(4, 1, 1);
        assertEquals(1, rc);
    }

    // doColorXForm: one step of a fade. value > target → subtract ticks.
    // value < target → add ticks. Clamp at target.
    @Test
    void doColorXFormStepsTowardTarget() {
        FWipe.wipe_scr     = new byte[] {100, 50, 20, (byte) 200};
        FWipe.wipe_scr_end = new byte[] {90,  80, 25, (byte) 150};
        //                                  ^diff -10 +30 +5   -50
        int rc = FWipe.wipeDoColorXForm(4, 1, 5);

        // wipe_scr[0]: 100 → 95 (100-5 = 95, still > 90)
        assertEquals(95, FWipe.wipe_scr[0] & 0xFF);
        // wipe_scr[1]: 50 → 55 (50+5 = 55, still < 80)
        assertEquals(55, FWipe.wipe_scr[1] & 0xFF);
        // wipe_scr[2]: 20 → 25 (clamped at end; 20+5=25 == end so either
        // branch lands at 25).
        assertEquals(25, FWipe.wipe_scr[2] & 0xFF);
        // wipe_scr[3]: 200 → 195 (200-5, still > 150)
        assertEquals(195, FWipe.wipe_scr[3] & 0xFF);

        assertEquals(0, rc); // changed -> 0
    }

    // doColorXForm clamps when one step would overshoot.
    @Test
    void doColorXFormClampsOnOvershoot() {
        FWipe.wipe_scr     = new byte[] { 100 };
        FWipe.wipe_scr_end = new byte[] { 97 };
        int rc = FWipe.wipeDoColorXForm(1, 1, 10);
        assertEquals(97, FWipe.wipe_scr[0] & 0xFF);
        assertEquals(0, rc); // we touched a byte -> not done
    }

    // exitColorXForm is a no-op returning 0.
    @Test
    void exitColorXFormReturnsZero() {
        assertEquals(0, FWipe.wipeExitColorXForm(W, H, 1));
    }

    // initMelt: y[] has width entries; each value in [-15, 0]; neighbors
    // differ by at most 1 (per the r in {-1,0,1} rule) unless we hit the
    // "== -16 → -15" clamp (which produces a +1 jump only when y[i-1] ==
    // -15 and r == -1).
    @Test
    void initMeltPopulatesYWithinConstraints() {
        FWipe.wipe_scr_start = new byte[W * H];
        FWipe.wipe_scr_end = new byte[W * H];
        FWipe.wipe_scr = new byte[W * H];

        FWipe.wipeInitMelt(W, H, 1);

        assertNotNull(FWipe.y);
        assertEquals(W, FWipe.y.length);

        for (int i = 0; i < W; i++) {
            assertTrue(FWipe.y[i] >= -15 && FWipe.y[i] <= 0,
                    "y[" + i + "]=" + FWipe.y[i] + " out of [-15,0]");
        }

        // Step-to-step difference in {-1, 0, 1} ∪ {+1 from -15 clamp}.
        // In all cases |delta| <= 1 unless clamp bumps -16 → -15 after
        // an i-1 value of -15 with r=-1 (still delta 0). The clamp
        // y[i] > 0 → 0 can produce delta up to +1 too. So allowable
        // delta is in {-1, 0, 1}.
        for (int i = 1; i < W; i++) {
            int delta = FWipe.y[i] - FWipe.y[i - 1];
            assertTrue(delta >= -1 && delta <= 1,
                    "delta at " + i + " = " + delta);
        }
    }

    @Test
    void exitMeltFreesY() {
        FWipe.y = new int[] { 0, 1, 2 };
        int rc = FWipe.wipeExitMelt(W, H, 1);
        assertEquals(0, rc);
        assertNull(FWipe.y);
    }

    // doMelt eventually converges. Seed a small scenario where y[] is
    // already at height, meaning every column is finished.
    @Test
    void doMeltReturnsDoneWhenAllColumnsPast() {
        int width = 4;   // bytes (= 2 shorts)
        int height = 2;
        FWipe.wipe_scr = new byte[width * height];
        FWipe.wipe_scr_start = new byte[width * height];
        FWipe.wipe_scr_end = new byte[width * height];
        // doMelt divides width by 2 internally; we have 2 short-columns.
        FWipe.y = new int[] { height, height };

        int rc = FWipe.wipeDoMelt(width, height, 1);
        assertEquals(1, rc);
    }

    // doMelt advances a negative column by one tick.
    @Test
    void doMeltIncrementsNegativeColumns() {
        int width = 2;   // bytes => 1 short-column after /2
        int height = 2;
        FWipe.wipe_scr = new byte[width * height];
        FWipe.wipe_scr_start = new byte[width * height];
        FWipe.wipe_scr_end = new byte[width * height];
        FWipe.y = new int[] { -3 };

        int rc = FWipe.wipeDoMelt(width, height, 1);
        assertEquals(-2, FWipe.y[0]);
        assertEquals(0, rc);   // not done
    }

    // wipeStartScreen uses screens[2] and reads screens[0] into it.
    @Test
    void startScreenCopiesScreen0ToScreen2() {
        for (int i = 0; i < VVideo.screens[0].length; i++) {
            VVideo.screens[0][i] = (byte) (i & 0xFF);
        }

        int rc = FWipe.wipeStartScreen(0, 0, W, H);
        assertEquals(0, rc);
        assertArrayEquals(VVideo.screens[0], VVideo.screens[2]);
        // global pointer points at screens[2].
        assertTrue(FWipe.wipe_scr_start == VVideo.screens[2]);
    }

    // wipeEndScreen reads screens[0] into screens[3] and calls
    // V_DrawBlock to overwrite screens[0] with wipe_scr_start.
    @Test
    void endScreenCopiesScreen0ToScreen3AndRestoresStart() {
        // setup a known start screen
        byte[] startPayload = new byte[VVideo.screens[0].length];
        for (int i = 0; i < startPayload.length; i++) startPayload[i] = 11;
        System.arraycopy(startPayload, 0, VVideo.screens[2], 0, startPayload.length);
        FWipe.wipe_scr_start = VVideo.screens[2];

        // different bytes on screens[0] so we can tell end-screen apart
        byte[] endPayload = new byte[VVideo.screens[0].length];
        for (int i = 0; i < endPayload.length; i++) endPayload[i] = 22;
        System.arraycopy(endPayload, 0, VVideo.screens[0], 0, endPayload.length);

        int rc = FWipe.wipeEndScreen(0, 0, W, H);

        assertEquals(0, rc);
        // screens[3] should now hold the end payload (22s).
        assertArrayEquals(endPayload, VVideo.screens[3]);
        // screens[0] should have been overwritten by wipe_scr_start (11s).
        assertArrayEquals(startPayload, VVideo.screens[0]);
    }

    // Full pipeline: running wipeScreenWipe until it returns 1 converges.
    // Use the ColorXForm wipe on a tiny setup (it's easier to reason
    // about than melt, which reads framebuffer memory as shorts).
    @Test
    void screenWipeColorXFormConvergesAndFlipsGo() {
        // Prime the wipe with start=100s, end=50s on screens[2]/[3].
        byte[] s2 = VVideo.screens[2];
        byte[] s3 = VVideo.screens[3];
        byte[] s0 = VVideo.screens[0];
        for (int i = 0; i < s2.length; i++) { s2[i] = 100; s3[i] = 50; s0[i] = 0; }
        FWipe.wipe_scr_start = s2;
        FWipe.wipe_scr_end = s3;

        assertFalse(FWipe.go);

        // one tick is not enough to finish 50 units of fade.
        int rc = FWipe.wipeScreenWipe(FWipe.WIPE_COLORXFORM, 0, 0, W, H, 1);
        assertTrue(FWipe.go, "go should flip true after first call");
        assertEquals(0, rc);

        // Grind ticks until it completes.
        int safety = 200;
        while (rc == 0 && safety-- > 0) {
            rc = FWipe.wipeScreenWipe(FWipe.WIPE_COLORXFORM, 0, 0, W, H, 1);
        }

        assertEquals(1, rc);
        assertFalse(FWipe.go, "go should flip back false once done");
        // screens[0] == screens[3] (all 50s).
        for (int i = 0; i < s0.length; i++) {
            assertEquals(50, s0[i] & 0xFF, "pixel " + i + " not faded");
        }
    }
}
