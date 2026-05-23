package doom.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.system.Doomdef;
import doom.system.ISystem;
import doom.ui.VVideo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RDrawTest {

    @BeforeEach
    void resetState() {
        RDraw.resetForTests();
        // VVideo.resetForTests is package-private to doom.ui; vInit() is
        // enough — it re-allocates the four framebuffers RDraw writes into.
        VVideo.screens = new byte[5][];
        VVideo.vInit();
    }

    // R_InitBuffer(width, height) — full-width view skips viewwindowy,
    // and ylookup[y] must equal (y + viewwindowy) * RENDER_WIDTH.
    // (Renderer paths use RENDER_* — viewBuffer is sized accordingly.)
    @Test
    void initBufferFullWidthPopulatesLookups() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH,
                Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);

        assertEquals(0, RDraw.viewwindowx);
        assertEquals(0, RDraw.viewwindowy);
        for (int x = 0; x < Doomdef.RENDER_WIDTH; x++) {
            assertEquals(x, RDraw.columnofs[x]);
        }
        int rows = Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL;
        for (int y = 0; y < rows; y++) {
            assertEquals(y * Doomdef.RENDER_WIDTH, RDraw.ylookup[y]);
        }
    }

    // Small view window — viewwindowx/y centre within the renderer buffer.
    @Test
    void initBufferSmallWindowCentresOrigin() {
        int w = 160 * Doomdef.RENDER_MUL;
        int h = 100 * Doomdef.RENDER_MUL;
        RDraw.rInitBuffer(w, h);

        assertEquals((Doomdef.RENDER_WIDTH - w) >> 1, RDraw.viewwindowx);
        assertEquals((Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL - h) >> 1,
                RDraw.viewwindowy);
        assertEquals(RDraw.viewwindowx, RDraw.columnofs[0]);
        assertEquals(RDraw.viewwindowx + w - 1, RDraw.columnofs[w - 1]);
        assertEquals(RDraw.viewwindowy * Doomdef.RENDER_WIDTH, RDraw.ylookup[0]);
    }

    // R_InitTranslationTables — green ramp 0x70..0x7F remaps to gray/brown/red;
    // everything else is identity across all three tables.
    @Test
    void initTranslationTablesMapsGreenRamp() {
        RDraw.rInitTranslationTables();
        assertEquals(256 * 3, RDraw.translationtables.length);

        for (int i = 0; i < 256; i++) {
            byte g = RDraw.translationtables[i];
            byte b = RDraw.translationtables[i + 256];
            byte r = RDraw.translationtables[i + 512];
            if (i >= 0x70 && i <= 0x7F) {
                assertEquals((byte) (0x60 + (i & 0xF)), g, "gray ramp " + i);
                assertEquals((byte) (0x40 + (i & 0xF)), b, "brown ramp " + i);
                assertEquals((byte) (0x20 + (i & 0xF)), r, "red ramp " + i);
            } else {
                assertEquals((byte) i, g, "identity gray " + i);
                assertEquals((byte) i, b, "identity brown " + i);
                assertEquals((byte) i, r, "identity red " + i);
            }
        }
    }

    // R_DrawColumn — with an identity colormap and a source byte[], pixels
    // written along a column should equal the sampled texture bytes.
    @Test
    void drawColumnWritesSampledPixels() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH, Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);

        // Identity colormap so the output byte equals the source byte.
        byte[] identity = new byte[256];
        for (int i = 0; i < 256; i++) {
            identity[i] = (byte) i;
        }
        RDraw.dcColormap = identity;
        RDraw.dcColormapOfs = 0;

        // 128-tall source column with known pattern source[i] = i.
        byte[] source = new byte[128];
        for (int i = 0; i < 128; i++) {
            source[i] = (byte) i;
        }
        RDraw.dcSource = source;
        RDraw.dcSourceOfs = 0;

        RDraw.dcX = 10;
        RDraw.dcYl = 20;
        RDraw.dcYh = 29;
        RDraw.dcIscale = Fixed.FRACUNIT;   // step one texel per screen row
        RDraw.dcTexturemid = 0;
        RMain.centery = 20;                // frac starts at 0 at dcYl

        RDraw.rDrawColumn();

        byte[] screen = VVideo.viewBuffer;
        // 10 rows written (dcYl=20 .. dcYh=29). Each row samples (frac>>16)&127.
        for (int y = 20; y <= 29; y++) {
            int frac = (y - RMain.centery) * Fixed.FRACUNIT;
            int expected = (frac >> Fixed.FRACBITS) & 127;
            int got = screen[y * Doomdef.RENDER_WIDTH + 10] & 0xFF;
            assertEquals(expected, got, "row " + y);
        }
    }

    // R_DrawColumn — negative count (yh < yl) is a no-op, not an error.
    @Test
    void drawColumnEmptyRangeIsNoOp() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH, Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);
        RDraw.dcColormap = new byte[256];
        RDraw.dcSource = new byte[128];
        RDraw.dcX = 0;
        RDraw.dcYl = 50;
        RDraw.dcYh = 49;
        RDraw.rDrawColumn(); // no throw
    }

    @Test
    void drawColumnOutOfBoundsThrows() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH, Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);
        RDraw.dcColormap = new byte[256];
        RDraw.dcSource = new byte[128];
        RDraw.dcX = Doomdef.RENDER_WIDTH; // off-screen right
        RDraw.dcYl = 0;
        RDraw.dcYh = 10;
        assertThrows(ISystem.DoomError.class, RDraw::rDrawColumn);
    }

    // R_DrawTranslatedColumn — translation table must be applied before the
    // colormap. Use identity colormap and a translation that inverts each byte.
    @Test
    void drawTranslatedColumnAppliesTranslationFirst() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH, Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);

        byte[] identity = new byte[256];
        byte[] invert = new byte[256];
        for (int i = 0; i < 256; i++) {
            identity[i] = (byte) i;
            invert[i] = (byte) (255 - i);
        }
        RDraw.dcColormap = identity;
        RDraw.dcColormapOfs = 0;
        RDraw.dcTranslation = invert;
        RDraw.dcTranslationOfs = 0;

        byte[] source = new byte[256];
        for (int i = 0; i < 256; i++) {
            source[i] = (byte) i;
        }
        RDraw.dcSource = source;
        RDraw.dcSourceOfs = 0;

        RDraw.dcX = 5;
        RDraw.dcYl = 0;
        RDraw.dcYh = 0;
        RDraw.dcIscale = Fixed.FRACUNIT;
        RDraw.dcTexturemid = Fixed.fromInt(77); // sample source[77]
        RMain.centery = 0;

        RDraw.rDrawTranslatedColumn();
        int got = VVideo.viewBuffer[5] & 0xFF;
        assertEquals(255 - 77, got);
    }

    // R_DrawSpan — horizontal floor/ceiling blit samples 64×64 flat.
    @Test
    void drawSpanWritesAcrossRow() {
        RDraw.rInitBuffer(Doomdef.RENDER_WIDTH, Doomdef.RENDER_HEIGHT - RDraw.SBARHEIGHT * Doomdef.RENDER_MUL);

        byte[] identity = new byte[256];
        for (int i = 0; i < 256; i++) {
            identity[i] = (byte) i;
        }
        RDraw.dsColormap = identity;
        RDraw.dsColormapOfs = 0;

        // 64×64 flat where flat[v*64+u] = (byte)(v*64+u).
        byte[] flat = new byte[64 * 64];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = (byte) i;
        }
        RDraw.dsSource = flat;
        RDraw.dsSourceOfs = 0;

        RDraw.dsY = 50;
        RDraw.dsX1 = 0;
        RDraw.dsX2 = 9;
        RDraw.dsXfrac = 0;
        RDraw.dsYfrac = 0;
        RDraw.dsXstep = Fixed.FRACUNIT;  // advance one texel per screen pixel
        RDraw.dsYstep = 0;

        RDraw.rDrawSpan();

        byte[] screen = VVideo.viewBuffer;
        int rowBase = 50 * Doomdef.RENDER_WIDTH;
        for (int x = 0; x <= 9; x++) {
            int xfrac = x * Fixed.FRACUNIT;
            int expected = ((0 >> (16 - 6)) & (63 * 64)) + ((xfrac >> 16) & 63);
            assertEquals(expected & 0xFF, screen[rowBase + x] & 0xFF, "x=" + x);
        }
    }

    @Test
    void drawSpanOutOfBoundsThrows() {
        RDraw.dsX1 = 5;
        RDraw.dsX2 = 3; // x2 < x1
        RDraw.dsY = 0;
        assertThrows(ISystem.DoomError.class, RDraw::rDrawSpan);
    }

    // R_VideoErase — raw byte copy from screens[1] into screens[0].
    @Test
    void videoEraseCopiesBetweenScreens() {
        // Fill screens[1] with a marker, screens[0] with zeros.
        for (int i = 0; i < VVideo.screens[1].length; i++) {
            VVideo.screens[1][i] = (byte) 0xA5;
        }
        RDraw.rVideoErase(100, 50);
        for (int i = 100; i < 150; i++) {
            assertEquals((byte) 0xA5, VVideo.screens[0][i]);
        }
        // Pixels outside the range stay untouched.
        assertEquals(0, VVideo.screens[0][99]);
        assertEquals(0, VVideo.screens[0][150]);
    }

    // R_DrawViewBorder — full-width view should be a no-op.
    @Test
    void drawViewBorderFullWidthIsNoOp() {
        RDraw.scaledviewwidth = Doomdef.RENDER_WIDTH;
        RDraw.viewheight = 168 * Doomdef.RENDER_MUL;
        // Pre-fill screens[0] and confirm nothing changes.
        for (int i = 0; i < VVideo.screens[0].length; i++) {
            VVideo.screens[0][i] = (byte) 0x11;
        }
        RDraw.rDrawViewBorder();
        for (int i = 0; i < VVideo.screens[0].length; i++) {
            assertEquals((byte) 0x11, VVideo.screens[0][i]);
        }
    }

    // R_DrawViewBorder — narrower view must copy border bytes from screens[1].
    @Test
    void drawViewBorderSmallWindowCopiesFromBackBuffer() {
        int w = 160, h = 100;
        RDraw.rInitBuffer(w, h);
        RDraw.scaledviewwidth = w;
        RDraw.viewheight = h;

        // Distinct marker per screen so we can tell copies apart.
        for (int i = 0; i < VVideo.screens[0].length; i++) {
            VVideo.screens[0][i] = 0;
            VVideo.screens[1][i] = (byte) 0x77;
        }

        RDraw.rDrawViewBorder();

        // Top-left corner of screen 0 must now mirror screen 1.
        assertEquals((byte) 0x77, VVideo.screens[0][0]);
        // Centre of the view window (which the border does NOT overwrite)
        // should still be zero.
        int centre = (RDraw.viewwindowy + h / 2) * Doomdef.SCREENWIDTH
                + RDraw.viewwindowx + w / 2;
        assertEquals(0, VVideo.screens[0][centre]);
        // Dirty box expanded to full screen minus status bar.
        assertNotEquals(0, VVideo.dirtybox[0] | VVideo.dirtybox[1]
                | VVideo.dirtybox[2] | VVideo.dirtybox[3]);
    }
}
