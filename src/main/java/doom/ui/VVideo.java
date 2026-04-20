// Port of linuxdoom-1.10/v_video.c and v_video.h.
// Framebuffer blits, patch blitting, gamma table, dirty-rect tracking.
//
// Translation notes:
//   * `byte* screens[5]` becomes `public static byte[][] screens` — each of
//     screens[0..3] is a 320×200 byte palette-index framebuffer, screens[4]
//     is filled in by the status bar code later.
//   * pointer arithmetic in V_DrawPatch uses explicit index variables
//     (CLAUDE.md §5); bytes are masked `& 0xFF` when compared against 0xff
//     or used as multipliers (e.g. topdelta).
//   * The gamma table is embedded here verbatim from v_video.c; the values
//     are small enough that the class-init bytecode stays well under 64 KB,
//     so no .dat resource is needed (unlike tables.c).
//   * The commented-out planar-write VGA fallback inside V_DrawPatchDirect
//     is not translated — the C wraps `V_DrawPatch` unconditionally.
package doom.ui;

import doom.play.MBbox;
import doom.render.Patch;
import doom.system.Doomdef;
import doom.system.ISystem;

public final class VVideo {

    // v_video.c line 44 — byte[SCREENWIDTH*SCREENHEIGHT] each.
    public static byte[][] screens = new byte[5][];

    // v_video.c line 46.
    public static int[] dirtybox = new int[4];

    // v_video.c line 137.
    public static int usegamma;

    // v_video.c line 51 — gammatable[5][256]. Stored as int[][] so values
    // round-trip as unsigned; callers do `& 0xFF` before writing to a byte[].
    public static final int[][] gammatable = {
            {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
             17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,
             33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,
             49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,
             65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,
             81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,
             97,98,99,100,101,102,103,104,105,106,107,108,109,110,111,112,
             113,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,
             128,129,130,131,132,133,134,135,136,137,138,139,140,141,142,143,
             144,145,146,147,148,149,150,151,152,153,154,155,156,157,158,159,
             160,161,162,163,164,165,166,167,168,169,170,171,172,173,174,175,
             176,177,178,179,180,181,182,183,184,185,186,187,188,189,190,191,
             192,193,194,195,196,197,198,199,200,201,202,203,204,205,206,207,
             208,209,210,211,212,213,214,215,216,217,218,219,220,221,222,223,
             224,225,226,227,228,229,230,231,232,233,234,235,236,237,238,239,
             240,241,242,243,244,245,246,247,248,249,250,251,252,253,254,255},

            {2,4,5,7,8,10,11,12,14,15,16,18,19,20,21,23,24,25,26,27,29,30,31,
             32,33,34,36,37,38,39,40,41,42,44,45,46,47,48,49,50,51,52,54,55,
             56,57,58,59,60,61,62,63,64,65,66,67,69,70,71,72,73,74,75,76,77,
             78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,
             99,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,
             115,116,117,118,119,120,121,122,123,124,125,126,127,128,129,129,
             130,131,132,133,134,135,136,137,138,139,140,141,142,143,144,145,
             146,147,148,148,149,150,151,152,153,154,155,156,157,158,159,160,
             161,162,163,163,164,165,166,167,168,169,170,171,172,173,174,175,
             175,176,177,178,179,180,181,182,183,184,185,186,186,187,188,189,
             190,191,192,193,194,195,196,196,197,198,199,200,201,202,203,204,
             205,205,206,207,208,209,210,211,212,213,214,214,215,216,217,218,
             219,220,221,222,222,223,224,225,226,227,228,229,230,230,231,232,
             233,234,235,236,237,237,238,239,240,241,242,243,244,245,245,246,
             247,248,249,250,251,252,252,253,254,255},

            {4,7,9,11,13,15,17,19,21,22,24,26,27,29,30,32,33,35,36,38,39,40,42,
             43,45,46,47,48,50,51,52,54,55,56,57,59,60,61,62,63,65,66,67,68,69,
             70,72,73,74,75,76,77,78,79,80,82,83,84,85,86,87,88,89,90,91,92,93,
             94,95,96,97,98,100,101,102,103,104,105,106,107,108,109,110,111,112,
             113,114,114,115,116,117,118,119,120,121,122,123,124,125,126,127,128,
             129,130,131,132,133,133,134,135,136,137,138,139,140,141,142,143,144,
             144,145,146,147,148,149,150,151,152,153,153,154,155,156,157,158,159,
             160,160,161,162,163,164,165,166,166,167,168,169,170,171,172,172,173,
             174,175,176,177,178,178,179,180,181,182,183,183,184,185,186,187,188,
             188,189,190,191,192,193,193,194,195,196,197,197,198,199,200,201,201,
             202,203,204,205,206,206,207,208,209,210,210,211,212,213,213,214,215,
             216,217,217,218,219,220,221,221,222,223,224,224,225,226,227,228,228,
             229,230,231,231,232,233,234,235,235,236,237,238,238,239,240,241,241,
             242,243,244,244,245,246,247,247,248,249,250,251,251,252,253,254,254,
             255},

            {8,12,16,19,22,24,27,29,31,34,36,38,40,41,43,45,47,49,50,52,53,55,
             57,58,60,61,63,64,65,67,68,70,71,72,74,75,76,77,79,80,81,82,84,85,
             86,87,88,90,91,92,93,94,95,96,98,99,100,101,102,103,104,105,106,107,
             108,109,110,111,112,113,114,115,116,117,118,119,120,121,122,123,124,
             125,126,127,128,129,130,131,132,133,134,135,135,136,137,138,139,140,
             141,142,143,143,144,145,146,147,148,149,150,150,151,152,153,154,155,
             155,156,157,158,159,160,160,161,162,163,164,165,165,166,167,168,169,
             169,170,171,172,173,173,174,175,176,176,177,178,179,180,180,181,182,
             183,183,184,185,186,186,187,188,189,189,190,191,192,192,193,194,195,
             195,196,197,197,198,199,200,200,201,202,202,203,204,205,205,206,207,
             207,208,209,210,210,211,212,212,213,214,214,215,216,216,217,218,219,
             219,220,221,221,222,223,223,224,225,225,226,227,227,228,229,229,230,
             231,231,232,233,233,234,235,235,236,237,237,238,238,239,240,240,241,
             242,242,243,244,244,245,246,246,247,247,248,249,249,250,251,251,252,
             253,253,254,254,255},

            {16,23,28,32,36,39,42,45,48,50,53,55,57,60,62,64,66,68,69,71,73,75,76,
             78,80,81,83,84,86,87,89,90,92,93,94,96,97,98,100,101,102,103,105,106,
             107,108,109,110,112,113,114,115,116,117,118,119,120,121,122,123,124,
             125,126,128,128,129,130,131,132,133,134,135,136,137,138,139,140,141,
             142,143,143,144,145,146,147,148,149,150,150,151,152,153,154,155,155,
             156,157,158,159,159,160,161,162,163,163,164,165,166,166,167,168,169,
             169,170,171,172,172,173,174,175,175,176,177,177,178,179,180,180,181,
             182,182,183,184,184,185,186,187,187,188,189,189,190,191,191,192,193,
             193,194,195,195,196,196,197,198,198,199,200,200,201,202,202,203,203,
             204,205,205,206,207,207,208,208,209,210,210,211,211,212,213,213,214,
             214,215,216,216,217,217,218,219,219,220,220,221,221,222,223,223,224,
             224,225,225,226,227,227,228,228,229,229,230,230,231,232,232,233,233,
             234,234,235,235,236,236,237,237,238,239,239,240,240,241,241,242,242,
             243,243,244,244,245,245,246,246,247,247,248,248,249,249,250,250,251,
             251,252,252,253,254,254,255,255}
    };

    private static final int SCREENWIDTH = Doomdef.SCREENWIDTH;
    private static final int SCREENHEIGHT = Doomdef.SCREENHEIGHT;

    private VVideo() {
    }

    // void V_Init (void) — v_video.c line 482.
    // The C calls I_AllocLow for 4 screen buffers; here we just allocate
    // four byte[] framebuffers directly.
    public static void vInit() {
        for (int i = 0; i < 4; i++) {
            screens[i] = new byte[SCREENWIDTH * SCREENHEIGHT];
        }
    }

    // void V_MarkRect (int x, int y, int width, int height)
    public static void vMarkRect(int x, int y, int width, int height) {
        MBbox.addToBox(dirtybox, x, y);
        MBbox.addToBox(dirtybox, x + width - 1, y + height - 1);
    }

    // void V_CopyRect (int srcx, int srcy, int srcscrn, int width, int height,
    //                  int destx, int desty, int destscrn)
    public static void vCopyRect(
            int srcx, int srcy, int srcscrn,
            int width, int height,
            int destx, int desty, int destscrn) {
        // RANGECHECK mirror — C only compiles this when RANGECHECK is defined.
        // We keep it always-on so Java tests catch invalid rects; matches the
        // behavior shipped Doom used during development.
        if (srcx < 0
                || srcx + width > SCREENWIDTH
                || srcy < 0
                || srcy + height > SCREENHEIGHT
                || destx < 0 || destx + width > SCREENWIDTH
                || desty < 0
                || desty + height > SCREENHEIGHT
                || (srcscrn & 0xFFFFFFFFL) > 4L
                || (destscrn & 0xFFFFFFFFL) > 4L) {
            throw ISystem.iError("Bad V_CopyRect");
        }

        vMarkRect(destx, desty, width, height);

        byte[] src = screens[srcscrn];
        byte[] dest = screens[destscrn];
        int srcIdx = SCREENWIDTH * srcy + srcx;
        int destIdx = SCREENWIDTH * desty + destx;

        for (; height > 0; height--) {
            System.arraycopy(src, srcIdx, dest, destIdx, width);
            srcIdx += SCREENWIDTH;
            destIdx += SCREENWIDTH;
        }
    }

    // void V_DrawPatch (int x, int y, int scrn, patch_t* patch)
    // Masks a column based masked pic to the screen.
    public static void vDrawPatch(int x, int y, int scrn, Patch patch) {
        y -= patch.topoffset;
        x -= patch.leftoffset;

        if (x < 0
                || x + patch.width > SCREENWIDTH
                || y < 0
                || y + patch.height > SCREENHEIGHT
                || (scrn & 0xFFFFFFFFL) > 4L) {
            // C prints to stderr and returns — no I_Error abort. Matches
            // the "what is up with TNT.WAD?" comment in v_video.c.
            System.err.printf("Patch at %d,%d exceeds LFB%n", x, y);
            System.err.println("V_DrawPatch: bad patch (ignored)");
            return;
        }

        if (scrn == 0) {
            vMarkRect(x, y, patch.width, patch.height);
        }

        int col = 0;
        byte[] screen = screens[scrn];
        int desttop = y * SCREENWIDTH + x;

        int w = patch.width;
        byte[] patchData = patch.data;

        for (; col < w; x++, col++, desttop++) {
            int columnOfs = patch.columnofs[col];

            // step through the posts in a column
            while ((patchData[columnOfs] & 0xFF) != 0xFF) {
                int topdelta = patchData[columnOfs] & 0xFF;
                int length = patchData[columnOfs + 1] & 0xFF;
                int srcIdx = columnOfs + 3;
                int destIdx = desttop + topdelta * SCREENWIDTH;
                int count = length;

                while (count-- > 0) {
                    screen[destIdx] = patchData[srcIdx++];
                    destIdx += SCREENWIDTH;
                }
                columnOfs = columnOfs + length + 4;
            }
        }
    }

    // void V_DrawPatchFlipped (int x, int y, int scrn, patch_t* patch)
    // Flips horizontally — used for the status bar mirrored face.
    public static void vDrawPatchFlipped(int x, int y, int scrn, Patch patch) {
        y -= patch.topoffset;
        x -= patch.leftoffset;

        if (x < 0
                || x + patch.width > SCREENWIDTH
                || y < 0
                || y + patch.height > SCREENHEIGHT
                || (scrn & 0xFFFFFFFFL) > 4L) {
            System.err.printf("Patch origin %d,%d exceeds LFB%n", x, y);
            throw ISystem.iError("Bad V_DrawPatch in V_DrawPatchFlipped");
        }

        if (scrn == 0) {
            vMarkRect(x, y, patch.width, patch.height);
        }

        int col = 0;
        byte[] screen = screens[scrn];
        int desttop = y * SCREENWIDTH + x;

        int w = patch.width;
        byte[] patchData = patch.data;

        for (; col < w; x++, col++, desttop++) {
            int columnOfs = patch.columnofs[w - 1 - col];

            while ((patchData[columnOfs] & 0xFF) != 0xFF) {
                int topdelta = patchData[columnOfs] & 0xFF;
                int length = patchData[columnOfs + 1] & 0xFF;
                int srcIdx = columnOfs + 3;
                int destIdx = desttop + topdelta * SCREENWIDTH;
                int count = length;

                while (count-- > 0) {
                    screen[destIdx] = patchData[srcIdx++];
                    destIdx += SCREENWIDTH;
                }
                columnOfs = columnOfs + length + 4;
            }
        }
    }

    // void V_DrawPatchDirect (int x, int y, int scrn, patch_t* patch)
    // C body just wraps V_DrawPatch (the VGA-planar fallback is commented out).
    public static void vDrawPatchDirect(int x, int y, int scrn, Patch patch) {
        vDrawPatch(x, y, scrn, patch);
    }

    // void V_DrawBlock (int x, int y, int scrn, int width, int height, byte* src)
    public static void vDrawBlock(int x, int y, int scrn,
                                  int width, int height, byte[] src) {
        if (x < 0
                || x + width > SCREENWIDTH
                || y < 0
                || y + height > SCREENHEIGHT
                || (scrn & 0xFFFFFFFFL) > 4L) {
            throw ISystem.iError("Bad V_DrawBlock");
        }

        vMarkRect(x, y, width, height);

        byte[] dest = screens[scrn];
        int destIdx = y * SCREENWIDTH + x;
        int srcIdx = 0;

        while (height-- > 0) {
            System.arraycopy(src, srcIdx, dest, destIdx, width);
            srcIdx += width;
            destIdx += SCREENWIDTH;
        }
    }

    // void V_GetBlock (int x, int y, int scrn, int width, int height, byte* dest)
    public static void vGetBlock(int x, int y, int scrn,
                                 int width, int height, byte[] dest) {
        if (x < 0
                || x + width > SCREENWIDTH
                || y < 0
                || y + height > SCREENHEIGHT
                || (scrn & 0xFFFFFFFFL) > 4L) {
            // C reports "Bad V_DrawBlock" here — same literal string as the
            // block draw above, presumably a copy-paste in the original.
            throw ISystem.iError("Bad V_DrawBlock");
        }

        byte[] src = screens[scrn];
        int srcIdx = y * SCREENWIDTH + x;
        int destIdx = 0;

        while (height-- > 0) {
            System.arraycopy(src, srcIdx, dest, destIdx, width);
            srcIdx += SCREENWIDTH;
            destIdx += width;
        }
    }

    // Test-only: reset framebuffers + dirtybox to a known state.
    static void resetForTests() {
        screens = new byte[5][];
        dirtybox = new int[4];
        usegamma = 0;
    }
}
