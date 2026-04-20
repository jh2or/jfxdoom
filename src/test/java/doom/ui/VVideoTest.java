package doom.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import doom.play.MBbox;
import doom.render.Patch;
import doom.system.Doomdef;
import doom.system.ISystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VVideoTest {

    private static final int W = Doomdef.SCREENWIDTH;
    private static final int H = Doomdef.SCREENHEIGHT;

    @BeforeEach
    void reset() {
        VVideo.resetForTests();
        VVideo.vInit();
        MBbox.clearBox(VVideo.dirtybox);
    }

    @Test
    void initAllocatesFourScreens() {
        for (int i = 0; i < 4; i++) {
            assertEquals(W * H, VVideo.screens[i].length,
                    "screen " + i + " should be 320*200 bytes");
        }
    }

    @Test
    void gammatableHasFiveRowsOf256() {
        assertEquals(5, VVideo.gammatable.length);
        for (int[] row : VVideo.gammatable) {
            assertEquals(256, row.length);
        }
        // Spot-check row 0 — identity-ish, v_video.c lines 53–68:
        // starts with 1,2,3 ... ends with 255.
        assertEquals(1, VVideo.gammatable[0][0]);
        assertEquals(255, VVideo.gammatable[0][255]);
        // Row 4 is the most aggressive — starts at 16.
        assertEquals(16, VVideo.gammatable[4][0]);
        assertEquals(255, VVideo.gammatable[4][255]);
    }

    @Test
    void markRectExpandsDirtybox() {
        VVideo.vMarkRect(10, 20, 30, 40);
        // After adding (10,20) and (10+30-1, 20+40-1) = (39, 59):
        assertEquals(10, VVideo.dirtybox[MBbox.BOXLEFT]);
        assertEquals(39, VVideo.dirtybox[MBbox.BOXRIGHT]);
        assertEquals(20, VVideo.dirtybox[MBbox.BOXBOTTOM]);
        assertEquals(59, VVideo.dirtybox[MBbox.BOXTOP]);
    }

    @Test
    void drawBlockCopiesPixelsAndMarksDirty() {
        byte[] src = new byte[4 * 3];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (i + 1);

        VVideo.vDrawBlock(5, 2, 0, 4, 3, src);

        byte[] scr = VVideo.screens[0];
        // Row 0: 1..4 at (5,2)..(8,2)
        assertEquals(1, scr[2 * W + 5]);
        assertEquals(4, scr[2 * W + 8]);
        // Row 1: 5..8 at (5,3)..(8,3)
        assertEquals(5, scr[3 * W + 5]);
        assertEquals(8, scr[3 * W + 8]);
        // Row 2: 9..12 at (5,4)..(8,4)
        assertEquals(9, scr[4 * W + 5]);
        assertEquals(12, scr[4 * W + 8]);

        assertEquals(5, VVideo.dirtybox[MBbox.BOXLEFT]);
        assertEquals(8, VVideo.dirtybox[MBbox.BOXRIGHT]);
    }

    @Test
    void getBlockReadsPixels() {
        // Paint 2x2 region at (1,1).
        byte[] scr = VVideo.screens[0];
        scr[1 * W + 1] = 0x11;
        scr[1 * W + 2] = 0x22;
        scr[2 * W + 1] = 0x33;
        scr[2 * W + 2] = 0x44;

        byte[] dest = new byte[4];
        VVideo.vGetBlock(1, 1, 0, 2, 2, dest);
        assertArrayEquals(new byte[] { 0x11, 0x22, 0x33, 0x44 }, dest);
    }

    @Test
    void copyRectDuplicatesBetweenScreens() {
        // Paint a 3x2 block into screen 0, copy to screen 1 at a different origin.
        byte[] s0 = VVideo.screens[0];
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 3; x++)
                s0[(10 + y) * W + (20 + x)] = (byte) (0x40 + y * 3 + x);

        VVideo.vCopyRect(20, 10, 0, 3, 2, 50, 60, 1);

        byte[] s1 = VVideo.screens[1];
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 3; x++)
                assertEquals((byte) (0x40 + y * 3 + x),
                        s1[(60 + y) * W + (50 + x)],
                        "pixel (" + x + "," + y + ")");
    }

    @Test
    void copyRectRangeCheckThrows() {
        assertThrows(ISystem.DoomError.class,
                () -> VVideo.vCopyRect(-1, 0, 0, 10, 10, 0, 0, 0));
        assertThrows(ISystem.DoomError.class,
                () -> VVideo.vCopyRect(0, 0, 0, 400, 10, 0, 0, 0)); // width OOB
        assertThrows(ISystem.DoomError.class,
                () -> VVideo.vCopyRect(0, 0, 5, 10, 10, 0, 0, 0)); // srcscrn > 4
    }

    @Test
    void drawBlockRangeCheckThrows() {
        byte[] src = new byte[4];
        assertThrows(ISystem.DoomError.class,
                () -> VVideo.vDrawBlock(-1, 0, 0, 2, 2, src));
        assertThrows(ISystem.DoomError.class,
                () -> VVideo.vDrawBlock(0, 0, 0, 321, 1, new byte[321]));
    }

    // --- Patch rendering ---

    // Build a synthetic patch: a 2x3 all-red (index 0xAA) rectangle.
    // Layout:
    //   header: w=2 h=3 lo=0 to=0
    //   columnofs[0], columnofs[1]
    //   col0 post:  topdelta=0 length=3 pad=0 [AA AA AA] pad=0 topdelta=FF
    //   col1 post:  topdelta=0 length=3 pad=0 [AA AA AA] pad=0 topdelta=FF
    private static Patch makeSolidPatch() throws IOException {
        int w = 2, h = 3;
        int headerSize = 8;
        int colofsSize = 4 * w;
        int postSize = 3 + h + 1 + 1; // topdelta+length+pad + pixels + pad + terminator
        byte[] buf = new byte[headerSize + colofsSize + postSize * w];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) w);
        bb.putShort((short) h);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        int col0Ofs = headerSize + colofsSize;
        int col1Ofs = col0Ofs + postSize;
        bb.putInt(col0Ofs);
        bb.putInt(col1Ofs);
        for (int c = 0; c < w; c++) {
            int ofs = headerSize + colofsSize + c * postSize;
            buf[ofs] = 0;              // topdelta
            buf[ofs + 1] = (byte) h;   // length
            buf[ofs + 2] = 0;          // unused pad
            for (int p = 0; p < h; p++) buf[ofs + 3 + p] = (byte) 0xAA;
            buf[ofs + 3 + h] = 0;         // trailing pad
            buf[ofs + 3 + h + 1] = (byte) 0xFF; // terminator
        }
        // sanity: parse it back
        return Patch.fromLump(buf);
    }

    @Test
    void drawPatchPaintsColumnPixels() throws IOException {
        Patch p = makeSolidPatch();
        // draw at (100, 50), scrn=0 — no offsets in our synthetic patch.
        VVideo.vDrawPatch(100, 50, 0, p);

        byte[] scr = VVideo.screens[0];
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals((byte) 0xAA, scr[(50 + y) * W + (100 + x)],
                        "pixel (" + x + "," + y + ")");
            }
        }
        // A pixel just outside the patch is still zero.
        assertEquals(0, scr[50 * W + 99]);
        assertEquals(0, scr[50 * W + 102]);

        // dirtybox was updated because scrn==0.
        assertEquals(100, VVideo.dirtybox[MBbox.BOXLEFT]);
        assertEquals(101, VVideo.dirtybox[MBbox.BOXRIGHT]);
    }

    @Test
    void drawPatchHonorsTopdeltaOffset() throws IOException {
        // Single column, single post at topdelta=5, length=2 of value 0x77.
        byte[] buf;
        int w = 1, h = 8; // h is bounding-box height so patch fits on screen
        int headerSize = 8;
        int colofsSize = 4 * w;
        int postLen = 2;
        int postBytes = 3 + postLen + 1 + 1;
        buf = new byte[headerSize + colofsSize + postBytes];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) w);
        bb.putShort((short) h);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        int colOfs = headerSize + colofsSize;
        bb.putInt(colOfs);
        buf[colOfs] = 5;            // topdelta
        buf[colOfs + 1] = 2;        // length
        buf[colOfs + 2] = 0;
        buf[colOfs + 3] = (byte) 0x77;
        buf[colOfs + 4] = (byte) 0x77;
        buf[colOfs + 5] = 0;
        buf[colOfs + 6] = (byte) 0xFF;

        Patch p = Patch.fromLump(buf);
        VVideo.vDrawPatch(10, 0, 1, p); // scrn=1 → no dirty mark
        byte[] scr = VVideo.screens[1];
        // topdelta 5 → draw at y=5 and y=6 at x=10.
        assertEquals((byte) 0x77, scr[5 * W + 10]);
        assertEquals((byte) 0x77, scr[6 * W + 10]);
        // y=4 (row above) and y=7 (row below) are untouched.
        assertEquals(0, scr[4 * W + 10]);
        assertEquals(0, scr[7 * W + 10]);
    }

    @Test
    void drawPatchFlippedMirrorsColumns() throws IOException {
        // 2-wide patch: col0 all 0x11, col1 all 0x22. Flipped → col0 gets 0x22.
        int w = 2, h = 2;
        int headerSize = 8;
        int colofsSize = 4 * w;
        int postBytes = 3 + h + 1 + 1;
        byte[] buf = new byte[headerSize + colofsSize + postBytes * w];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) w);
        bb.putShort((short) h);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        int col0Ofs = headerSize + colofsSize;
        int col1Ofs = col0Ofs + postBytes;
        bb.putInt(col0Ofs);
        bb.putInt(col1Ofs);
        int[] colors = { 0x11, 0x22 };
        for (int c = 0; c < w; c++) {
            int ofs = headerSize + colofsSize + c * postBytes;
            buf[ofs] = 0;
            buf[ofs + 1] = (byte) h;
            buf[ofs + 2] = 0;
            for (int p = 0; p < h; p++) buf[ofs + 3 + p] = (byte) colors[c];
            buf[ofs + 3 + h] = 0;
            buf[ofs + 3 + h + 1] = (byte) 0xFF;
        }

        Patch p = Patch.fromLump(buf);
        VVideo.vDrawPatchFlipped(50, 50, 0, p);

        byte[] scr = VVideo.screens[0];
        // Column 0 of the output receives source column 1 = 0x22.
        assertEquals((byte) 0x22, scr[50 * W + 50]);
        assertEquals((byte) 0x22, scr[51 * W + 50]);
        assertEquals((byte) 0x11, scr[50 * W + 51]);
        assertEquals((byte) 0x11, scr[51 * W + 51]);
    }

}
