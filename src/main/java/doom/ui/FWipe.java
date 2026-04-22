// Port of linuxdoom-1.10/f_wipe.c and f_wipe.h.
// Mission begin melt/wipe screen special effect.
//
// The C treats `byte* wipe_scr_{start,end,mid}` as a raw framebuffer
// for the color-fade wipe, and reinterprets the same memory as `short*`
// for the melt wipe (so it moves two palette pixels per "cell"). In
// Java we keep everything as `byte[]` and translate each `short`
// access into a pair of adjacent bytes — the observable result is
// byte-identical to the C.
package doom.ui;

import doom.play.MRandom;
import doom.wad.ZZone;

public final class FWipe {

    // f_wipe.h — wipe effect selector.
    public static final int WIPE_COLORXFORM = 0;
    public static final int WIPE_MELT = 1;
    public static final int WIPE_NUMWIPES = 2;

    // f_wipe.c line 43 — "when zero, stop the wipe".
    static boolean go = false;

    // f_wipe.c lines 45–47 — three byte* globals.
    static byte[] wipe_scr_start;
    static byte[] wipe_scr_end;
    static byte[] wipe_scr;

    // f_wipe.c line 138 — static int* y (per-column melt position).
    // C allocates this via Z_Malloc(width*sizeof(int), PU_STATIC, 0); we
    // allocate a plain int[] because ZZone only hands out byte[] and the
    // contents are never referenced as bytes (same rationale as
    // r_data.c deviation #7).
    static int[] y;

    // f_wipe.c lines 50–70 — wipe_shittyColMajorXform.
    //
    // The C signature is `(short* array, int width, int height)` — width
    // and height are in short-sized cells. The backing byte buffer has
    // `width * height * 2` bytes. We translate every short access into a
    // 2-byte pair copy, which preserves the raw bit pattern the melt
    // path then reads as shorts on the way out.
    public static void wipeShittyColMajorXform(byte[] array, int width, int height) {
        byte[] dest = ZZone.zMalloc(width * height * 2, ZZone.PU_STATIC, null);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dstIdx = (x * height + y) * 2;
                int srcIdx = (y * width + x) * 2;
                dest[dstIdx]     = array[srcIdx];
                dest[dstIdx + 1] = array[srcIdx + 1];
            }
        }

        System.arraycopy(dest, 0, array, 0, width * height * 2);

        ZZone.zFree(dest);
    }

    // f_wipe.c lines 72–80 — wipe_initColorXForm.
    public static int wipeInitColorXForm(int width, int height, int ticks) {
        System.arraycopy(wipe_scr_start, 0, wipe_scr, 0, width * height);
        return 0;
    }

    // f_wipe.c lines 82–126 — wipe_doColorXForm.
    //
    // Fades wipe_scr toward wipe_scr_end one palette step per tick,
    // returning non-zero when no byte differs (i.e. the fade finished).
    public static int wipeDoColorXForm(int width, int height, int ticks) {
        boolean changed = false;
        // C uses `byte* w` and `byte* e` walking two buffers in lockstep.
        // We keep an index pair into the byte[] arrays.
        int wEnd = width * height;
        for (int i = 0; i < wEnd; i++) {
            int w = wipe_scr[i] & 0xFF;
            int e = wipe_scr_end[i] & 0xFF;
            if (w != e) {
                int newval;
                if (w > e) {
                    newval = w - ticks;
                    if (newval < e) {
                        wipe_scr[i] = (byte) e;
                    } else {
                        wipe_scr[i] = (byte) newval;
                    }
                    changed = true;
                } else if (w < e) {
                    newval = w + ticks;
                    if (newval > e) {
                        wipe_scr[i] = (byte) e;
                    } else {
                        wipe_scr[i] = (byte) newval;
                    }
                    changed = true;
                }
            }
        }

        return changed ? 0 : 1;
    }

    // f_wipe.c lines 128–135 — wipe_exitColorXForm.
    public static int wipeExitColorXForm(int width, int height, int ticks) {
        return 0;
    }

    // f_wipe.c lines 140–169 — wipe_initMelt.
    public static int wipeInitMelt(int width, int height, int ticks) {
        // copy start screen to main screen
        System.arraycopy(wipe_scr_start, 0, wipe_scr, 0, width * height);

        // makes this wipe faster (in theory)
        // to have stuff in column-major format
        wipeShittyColMajorXform(wipe_scr_start, width / 2, height);
        wipeShittyColMajorXform(wipe_scr_end,   width / 2, height);

        // setup initial column positions
        // (y<0 => not ready to scroll yet)
        y = new int[width];
        y[0] = -(MRandom.mRandom() % 16);
        for (int i = 1; i < width; i++) {
            int r = (MRandom.mRandom() % 3) - 1;
            y[i] = y[i - 1] + r;
            if (y[i] > 0) {
                y[i] = 0;
            } else if (y[i] == -16) {
                y[i] = -15;
            }
        }

        return 0;
    }

    // f_wipe.c lines 171–224 — wipe_doMelt.
    //
    // In the C, `s` and `d` are `short*` pointers into wipe_scr_end and
    // wipe_scr respectively. Each short is 2 bytes of the byte[]. We
    // translate every `*s++` / `d[idx] = *s++` into a 2-byte copy at
    // the equivalent byte offset. `idx` was a short-count stride in the
    // C; we keep it in short units and multiply by 2 at the byte-copy.
    public static int wipeDoMelt(int widthIn, int height, int ticks) {
        // C: width/=2; — from here on width is measured in shorts.
        int width = widthIn / 2;

        boolean done = true;

        while (ticks-- > 0) {
            for (int i = 0; i < width; i++) {
                if (y[i] < 0) {
                    y[i]++;
                    done = false;
                } else if (y[i] < height) {
                    int dy = (y[i] < 16) ? y[i] + 1 : 8;
                    if (y[i] + dy >= height) {
                        dy = height - y[i];
                    }
                    // short* s = &((short*)wipe_scr_end)[i*height + y[i]];
                    int sIdx = (i * height + y[i]) * 2;
                    // short* d = &((short*)wipe_scr)[y[i]*width + i];
                    int dIdx = (y[i] * width + i) * 2;
                    int idx = 0;
                    for (int j = dy; j > 0; j--) {
                        // d[idx] = *(s++);  — short copy, 2 bytes.
                        wipe_scr[dIdx + idx * 2]     = wipe_scr_end[sIdx];
                        wipe_scr[dIdx + idx * 2 + 1] = wipe_scr_end[sIdx + 1];
                        sIdx += 2;
                        idx += width;
                    }
                    y[i] += dy;
                    // s = &((short*)wipe_scr_start)[i*height];
                    sIdx = (i * height) * 2;
                    // d = &((short*)wipe_scr)[y[i]*width + i];
                    dIdx = (y[i] * width + i) * 2;
                    idx = 0;
                    for (int j = height - y[i]; j > 0; j--) {
                        wipe_scr[dIdx + idx * 2]     = wipe_scr_start[sIdx];
                        wipe_scr[dIdx + idx * 2 + 1] = wipe_scr_start[sIdx + 1];
                        sIdx += 2;
                        idx += width;
                    }
                    done = false;
                }
            }
        }

        return done ? 1 : 0;
    }

    // f_wipe.c lines 226–234 — wipe_exitMelt.
    public static int wipeExitMelt(int width, int height, int ticks) {
        // C: Z_Free(y);  — for the int[] we allocated outside the zone,
        // drop the reference so the GC can reclaim it.
        y = null;
        return 0;
    }

    // f_wipe.c lines 236–246 — wipe_StartScreen.
    public static int wipeStartScreen(int x, int y, int width, int height) {
        wipe_scr_start = VVideo.screens[2];
        IVideo.iReadScreen(wipe_scr_start);
        return 0;
    }

    // f_wipe.c lines 248–259 — wipe_EndScreen.
    public static int wipeEndScreen(int x, int y, int width, int height) {
        wipe_scr_end = VVideo.screens[3];
        IVideo.iReadScreen(wipe_scr_end);
        VVideo.vDrawBlock(x, y, 0, width, height, wipe_scr_start); // restore start scr.
        return 0;
    }

    // f_wipe.c lines 261–302 — wipe_ScreenWipe.
    //
    // The C builds a function-pointer table `wipes[]` indexed by
    // `wipeno*3 + {0,1,2}` (init/do/exit). Java can't store function
    // pointers as literals the same way, so we dispatch by index. The
    // observable sequence of calls is identical.
    public static int wipeScreenWipe(int wipeno, int x, int y, int width, int height, int ticks) {
        int rc;

        // initial stuff
        if (!go) {
            go = true;
            // wipe_scr = (byte *) Z_Malloc(width*height, PU_STATIC, 0); // DEBUG
            wipe_scr = VVideo.screens[0];
            wipeInit(wipeno, width, height, ticks);
        }

        // do a piece of wipe-in
        VVideo.vMarkRect(0, 0, width, height);
        rc = wipeDo(wipeno, width, height, ticks);
        //  V_DrawBlock(x, y, 0, width, height, wipe_scr); // DEBUG

        // final stuff
        if (rc != 0) {
            go = false;
            wipeExit(wipeno, width, height, ticks);
        }

        return go ? 0 : 1;
    }

    // Dispatch helpers matching `wipes[wipeno*3 + n]` in the C.
    private static int wipeInit(int wipeno, int width, int height, int ticks) {
        return switch (wipeno) {
            case WIPE_COLORXFORM -> wipeInitColorXForm(width, height, ticks);
            case WIPE_MELT       -> wipeInitMelt(width, height, ticks);
            default -> 0;
        };
    }

    private static int wipeDo(int wipeno, int width, int height, int ticks) {
        return switch (wipeno) {
            case WIPE_COLORXFORM -> wipeDoColorXForm(width, height, ticks);
            case WIPE_MELT       -> wipeDoMelt(width, height, ticks);
            default -> 0;
        };
    }

    private static int wipeExit(int wipeno, int width, int height, int ticks) {
        return switch (wipeno) {
            case WIPE_COLORXFORM -> wipeExitColorXForm(width, height, ticks);
            case WIPE_MELT       -> wipeExitMelt(width, height, ticks);
            default -> 0;
        };
    }

    private FWipe() {
    }
}
