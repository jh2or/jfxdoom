// Port of linuxdoom-1.10/r_draw.c and r_draw.h.
// The column and span blitter — the entire hot path of the software renderer
// ends here. Every wall, floor, ceiling, sprite, transparent overlay and
// fuzzy spectre column flows through one of these functions.
//
// Translation notes:
//   * C `byte*` pointers into big shared buffers become (byte[] base, int
//     offset) pairs. The "pointer" globals — `dc_source`, `dc_colormap`,
//     `ds_source`, `ds_colormap`, `dc_translation` — therefore split in two.
//   * `ylookup[]` and `columnofs[]` are pure offsets into `screens[0]` (C
//     stores a pointer; Java stores the integer offset that C would add).
//     Callers resolve via `screens[0][ylookup[y] + columnofs[x]]`.
//   * The do/while(count--) idiom translates as `do { ... } while (count-- > 0)`
//     — same trip count for identical `count` values (see body comment).
//   * The `&127` in `R_DrawColumn` ties to 128-tall texture columns; this is
//     a deliberate Doom invariant (wall textures cap at 128 texels tall), not
//     a bug. Leaving the mask as-is.
//   * The `unsigned` range-checks (`(unsigned)dc_x >= SCREENWIDTH`) become
//     two signed comparisons `dc_x < 0 || dc_x >= SCREENWIDTH`.
//   * Two big `#if 0`-guarded alternate loop-unrolled bodies in the C source
//     are intentionally not translated. They document optimisation attempts
//     and are dead code.
//   * `R_FillBackScreen` and `R_DrawViewBorder` are ported but depend on
//     `scaledviewwidth` / `viewheight` being set by `R_ExecuteSetViewSize`
//     (still in the unported r_main.c). Their bodies are correct the moment
//     those globals are populated.
//   * `viewimage` is declared in the C but never assigned or read by anyone
//     (a dev-era leftover). Kept as a public field for literal-source parity.
package doom.render;

import doom.fixed.Fixed;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.ISystem;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class RDraw {

    // r_draw.c line 47–51.
    public static final int MAXWIDTH = 1120;
    public static final int MAXHEIGHT = 832;
    public static final int SBARHEIGHT = 32;

    // r_draw.c line 63–70.
    public static byte[] viewimage;
    public static int viewwidth;
    public static int scaledviewwidth;
    public static int viewheight;
    public static int viewwindowx;
    public static int viewwindowy;

    // ylookup[y] = row offset into screens[0] for screen row (y + viewwindowy).
    public static int[] ylookup = new int[MAXHEIGHT];

    // columnofs[x] = column offset into a row for view column x (inc. viewwindowx).
    public static int[] columnofs = new int[MAXWIDTH];

    // r_draw.c line 76 — color translation tables for player suits.
    public static byte[][] translations = new byte[3][256];

    // --- R_DrawColumn inputs (r_draw.c lines 85–93) ---

    // dc_colormap — a 256-byte lighting LUT. Translated as (base,offset) into
    // `RData.colormaps`. Callers do `colormaps[dcColormapOfs + idx]`.
    public static byte[] dcColormap;
    public static int dcColormapOfs;

    public static int dcX;
    public static int dcYl;
    public static int dcYh;
    public static int dcIscale;    // fixed_t
    public static int dcTexturemid;// fixed_t

    // dc_source — first pixel in a column. Points into either a lump-cached
    // patch column or a composite texture column (r_data.rGetColumn).
    public static byte[] dcSource;
    public static int dcSourceOfs;

    public static int dccount;     // profiling only

    // --- R_DrawSpan inputs (r_draw.c lines 500–515) ---

    public static int dsY;
    public static int dsX1;
    public static int dsX2;

    public static byte[] dsColormap;
    public static int dsColormapOfs;

    public static int dsXfrac;     // fixed_t
    public static int dsYfrac;     // fixed_t
    public static int dsXstep;     // fixed_t
    public static int dsYstep;     // fixed_t

    // ds_source — start of a 64×64 flat tile.
    public static byte[] dsSource;
    public static int dsSourceOfs;

    public static int dscount;     // profiling only

    // --- Fuzz (spectre/invisibility) ---

    public static final int FUZZTABLE = 50;
    public static final int FUZZOFF = Doomdef.SCREENWIDTH;

    // r_draw.c lines 263–272.
    public static int[] fuzzoffset = {
            FUZZOFF, -FUZZOFF, FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF,
            FUZZOFF, FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF,
            FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF, -FUZZOFF, -FUZZOFF, -FUZZOFF,
            FUZZOFF, -FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF,
            FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF, -FUZZOFF, FUZZOFF,
            FUZZOFF, -FUZZOFF, -FUZZOFF, -FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF,
            FUZZOFF, FUZZOFF, -FUZZOFF, FUZZOFF, FUZZOFF, -FUZZOFF, FUZZOFF
    };

    public static int fuzzpos = 0;

    // --- Player-color translation ---

    public static byte[] dcTranslation;
    public static int dcTranslationOfs;
    public static byte[] translationtables;

    private RDraw() {
    }

    // void R_DrawColumn(void)
    public static void rDrawColumn() {
        int count = dcYh - dcYl;
        if (count < 0) {
            return;
        }

        if (dcX < 0 || dcX >= Doomdef.SCREENWIDTH
                || dcYl < 0
                || dcYh >= Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawColumn: %d to %d at %d",
                    dcYl, dcYh, dcX);
        }

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dcYl] + columnofs[dcX];

        int fracstep = dcIscale;
        int frac = dcTexturemid + (dcYl - RMain.centery) * fracstep;

        do {
            int src = dcSource[dcSourceOfs + ((frac >> Fixed.FRACBITS) & 127)] & 0xFF;
            screen[destIdx] = dcColormap[dcColormapOfs + src];
            destIdx += Doomdef.SCREENWIDTH;
            frac += fracstep;
        } while (count-- > 0);
    }

    // void R_DrawColumnLow(void)
    public static void rDrawColumnLow() {
        int count = dcYh - dcYl;
        if (count < 0) {
            return;
        }

        if (dcX < 0 || dcX >= Doomdef.SCREENWIDTH
                || dcYl < 0
                || dcYh >= Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawColumn: %d to %d at %d",
                    dcYl, dcYh, dcX);
        }

        // Blocky mode, need to multiply by 2.
        dcX <<= 1;

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dcYl] + columnofs[dcX];
        int dest2Idx = ylookup[dcYl] + columnofs[dcX + 1];

        int fracstep = dcIscale;
        int frac = dcTexturemid + (dcYl - RMain.centery) * fracstep;

        do {
            // Hack. Does not work correctly.
            int src = dcSource[dcSourceOfs + ((frac >> Fixed.FRACBITS) & 127)] & 0xFF;
            byte v = dcColormap[dcColormapOfs + src];
            screen[destIdx] = v;
            screen[dest2Idx] = v;
            destIdx += Doomdef.SCREENWIDTH;
            dest2Idx += Doomdef.SCREENWIDTH;
            frac += fracstep;
        } while (count-- > 0);
    }

    // void R_DrawFuzzColumn(void)
    // Creates a fuzzy image by copying pixels from adjacent ones to left
    // and right. Used with an all-black colormap for spectres/invisibility.
    public static void rDrawFuzzColumn() {
        // Adjust borders. Low...
        if (dcYl == 0) {
            dcYl = 1;
        }
        // .. and high.
        if (dcYh == viewheight - 1) {
            dcYh = viewheight - 2;
        }

        int count = dcYh - dcYl;
        if (count < 0) {
            return;
        }

        if (dcX < 0 || dcX >= Doomdef.SCREENWIDTH
                || dcYl < 0 || dcYh >= Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawFuzzColumn: %d to %d at %d",
                    dcYl, dcYh, dcX);
        }

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dcYl] + columnofs[dcX];

        int fracstep = dcIscale;
        int frac = dcTexturemid + (dcYl - RMain.centery) * fracstep;

        // Lookup framebuffer, retrieve a pixel one column left or right,
        // then re-index via colormap #6 (slightly brighter than average).
        do {
            int neighbour = screen[destIdx + fuzzoffset[fuzzpos]] & 0xFF;
            screen[destIdx] = RData.colormaps[6 * 256 + neighbour];

            if (++fuzzpos == FUZZTABLE) {
                fuzzpos = 0;
            }

            destIdx += Doomdef.SCREENWIDTH;
            frac += fracstep;
        } while (count-- > 0);
    }

    // void R_DrawTranslatedColumn(void) — used for player sprites, re-colours
    // the green color ramp to grey/brown/red per player number.
    public static void rDrawTranslatedColumn() {
        int count = dcYh - dcYl;
        if (count < 0) {
            return;
        }

        if (dcX < 0 || dcX >= Doomdef.SCREENWIDTH
                || dcYl < 0
                || dcYh >= Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawColumn: %d to %d at %d",
                    dcYl, dcYh, dcX);
        }

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dcYl] + columnofs[dcX];

        int fracstep = dcIscale;
        int frac = dcTexturemid + (dcYl - RMain.centery) * fracstep;

        do {
            int raw = dcSource[dcSourceOfs + (frac >> Fixed.FRACBITS)] & 0xFF;
            int translated = dcTranslation[dcTranslationOfs + raw] & 0xFF;
            screen[destIdx] = dcColormap[dcColormapOfs + translated];
            destIdx += Doomdef.SCREENWIDTH;
            frac += fracstep;
        } while (count-- > 0);
    }

    // void R_InitTranslationTables(void) — builds the 3×256 color ramp table.
    // The C aligns the allocation to 256 bytes for fast `&0xff` indexing; in
    // Java we don't need that and just allocate a plain 768-byte buffer.
    public static void rInitTranslationTables() {
        translationtables = new byte[256 * 3];

        // translate just the 16 green colors
        for (int i = 0; i < 256; i++) {
            if (i >= 0x70 && i <= 0x7F) {
                // map green ramp to gray, brown, red
                translationtables[i] = (byte) (0x60 + (i & 0xF));
                translationtables[i + 256] = (byte) (0x40 + (i & 0xF));
                translationtables[i + 512] = (byte) (0x20 + (i & 0xF));
            } else {
                // Keep all other colors as is.
                translationtables[i] = (byte) i;
                translationtables[i + 256] = (byte) i;
                translationtables[i + 512] = (byte) i;
            }
        }
    }

    // void R_DrawSpan(void) — horizontal floor/ceiling span over a 64×64 flat.
    public static void rDrawSpan() {
        if (dsX2 < dsX1
                || dsX1 < 0
                || dsX2 >= Doomdef.SCREENWIDTH
                || dsY < 0 || dsY > Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawSpan: %d to %d at %d",
                    dsX1, dsX2, dsY);
        }

        int xfrac = dsXfrac;
        int yfrac = dsYfrac;

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dsY] + columnofs[dsX1];

        int count = dsX2 - dsX1;

        do {
            // Current texture index in u,v.
            int spot = ((yfrac >> (16 - 6)) & (63 * 64)) + ((xfrac >> 16) & 63);
            int src = dsSource[dsSourceOfs + spot] & 0xFF;
            screen[destIdx++] = dsColormap[dsColormapOfs + src];
            xfrac += dsXstep;
            yfrac += dsYstep;
        } while (count-- > 0);
    }

    // void R_DrawSpanLow(void) — double-pixel blocky mode.
    public static void rDrawSpanLow() {
        if (dsX2 < dsX1
                || dsX1 < 0
                || dsX2 >= Doomdef.SCREENWIDTH
                || dsY < 0 || dsY > Doomdef.SCREENHEIGHT) {
            throw ISystem.iError("R_DrawSpan: %d to %d at %d",
                    dsX1, dsX2, dsY);
        }

        int xfrac = dsXfrac;
        int yfrac = dsYfrac;

        // Blocky mode, need to multiply by 2.
        dsX1 <<= 1;
        dsX2 <<= 1;

        byte[] screen = VVideo.screens[0];
        int destIdx = ylookup[dsY] + columnofs[dsX1];

        int count = dsX2 - dsX1;
        do {
            int spot = ((yfrac >> (16 - 6)) & (63 * 64)) + ((xfrac >> 16) & 63);
            byte v = dsColormap[dsColormapOfs + (dsSource[dsSourceOfs + spot] & 0xFF)];
            screen[destIdx++] = v;
            screen[destIdx++] = v;
            xfrac += dsXstep;
            yfrac += dsYstep;
        } while (count-- > 0);
    }

    // void R_InitBuffer(int width, int height)
    // Precalculates per-row and per-column offsets so the drawing inner
    // loops avoid multiply-by-SCREENWIDTH.
    public static void rInitBuffer(int width, int height) {
        // Handle resize — smaller view windows with border and/or status bar.
        viewwindowx = (Doomdef.SCREENWIDTH - width) >> 1;

        // Column offset for windows.
        for (int i = 0; i < width; i++) {
            columnofs[i] = viewwindowx + i;
        }

        // Same for base row offset.
        if (width == Doomdef.SCREENWIDTH) {
            viewwindowy = 0;
        } else {
            viewwindowy = (Doomdef.SCREENHEIGHT - SBARHEIGHT - height) >> 1;
        }

        // Precalculate all row offsets into screens[0].
        for (int i = 0; i < height; i++) {
            ylookup[i] = (i + viewwindowy) * Doomdef.SCREENWIDTH;
        }
    }

    // void R_FillBackScreen(void)
    // Fills screens[1] with a tiled flat and draws a beveled border.
    public static void rFillBackScreen() throws IOException {
        if (scaledviewwidth == Doomdef.SCREENWIDTH) {
            return;
        }

        String name = (Doomstat.gamemode == Doomstat.GameMode.commercial)
                ? "GRNROCK" : "FLOOR7_2";

        byte[] src = WWad.wCacheLumpName(name, ZZone.PU_CACHE);
        byte[] dest = VVideo.screens[1];
        int destIdx = 0;

        for (int y = 0; y < Doomdef.SCREENHEIGHT - SBARHEIGHT; y++) {
            for (int x = 0; x < Doomdef.SCREENWIDTH / 64; x++) {
                System.arraycopy(src, (y & 63) << 6, dest, destIdx, 64);
                destIdx += 64;
            }
            if ((Doomdef.SCREENWIDTH & 63) != 0) {
                System.arraycopy(src, (y & 63) << 6, dest, destIdx,
                        Doomdef.SCREENWIDTH & 63);
                destIdx += (Doomdef.SCREENWIDTH & 63);
            }
        }

        Patch top = Patch.fromLump(WWad.wCacheLumpName("brdr_t", ZZone.PU_CACHE));
        for (int x = 0; x < scaledviewwidth; x += 8) {
            VVideo.vDrawPatch(viewwindowx + x, viewwindowy - 8, 1, top);
        }
        Patch bot = Patch.fromLump(WWad.wCacheLumpName("brdr_b", ZZone.PU_CACHE));
        for (int x = 0; x < scaledviewwidth; x += 8) {
            VVideo.vDrawPatch(viewwindowx + x, viewwindowy + viewheight, 1, bot);
        }
        Patch left = Patch.fromLump(WWad.wCacheLumpName("brdr_l", ZZone.PU_CACHE));
        for (int y = 0; y < viewheight; y += 8) {
            VVideo.vDrawPatch(viewwindowx - 8, viewwindowy + y, 1, left);
        }
        Patch right = Patch.fromLump(WWad.wCacheLumpName("brdr_r", ZZone.PU_CACHE));
        for (int y = 0; y < viewheight; y += 8) {
            VVideo.vDrawPatch(viewwindowx + scaledviewwidth, viewwindowy + y, 1, right);
        }

        VVideo.vDrawPatch(viewwindowx - 8, viewwindowy - 8, 1,
                Patch.fromLump(WWad.wCacheLumpName("brdr_tl", ZZone.PU_CACHE)));
        VVideo.vDrawPatch(viewwindowx + scaledviewwidth, viewwindowy - 8, 1,
                Patch.fromLump(WWad.wCacheLumpName("brdr_tr", ZZone.PU_CACHE)));
        VVideo.vDrawPatch(viewwindowx - 8, viewwindowy + viewheight, 1,
                Patch.fromLump(WWad.wCacheLumpName("brdr_bl", ZZone.PU_CACHE)));
        VVideo.vDrawPatch(viewwindowx + scaledviewwidth, viewwindowy + viewheight, 1,
                Patch.fromLump(WWad.wCacheLumpName("brdr_br", ZZone.PU_CACHE)));
    }

    // void R_VideoErase(unsigned ofs, int count) — LFB copy from screen 1 to 0.
    public static void rVideoErase(int ofs, int count) {
        System.arraycopy(VVideo.screens[1], ofs,
                VVideo.screens[0], ofs, count);
    }

    // void R_DrawViewBorder(void) — copies the back-screen border onto the main screen.
    public static void rDrawViewBorder() {
        if (scaledviewwidth == Doomdef.SCREENWIDTH) {
            return;
        }

        int top = ((Doomdef.SCREENHEIGHT - SBARHEIGHT) - viewheight) / 2;
        int side = (Doomdef.SCREENWIDTH - scaledviewwidth) / 2;

        // copy top and one line of left side
        rVideoErase(0, top * Doomdef.SCREENWIDTH + side);

        // copy one line of right side and bottom
        int ofs = (viewheight + top) * Doomdef.SCREENWIDTH - side;
        rVideoErase(ofs, top * Doomdef.SCREENWIDTH + side);

        // copy sides using wraparound
        ofs = top * Doomdef.SCREENWIDTH + Doomdef.SCREENWIDTH - side;
        side <<= 1;

        for (int i = 1; i < viewheight; i++) {
            rVideoErase(ofs, side);
            ofs += Doomdef.SCREENWIDTH;
        }

        VVideo.vMarkRect(0, 0, Doomdef.SCREENWIDTH,
                Doomdef.SCREENHEIGHT - SBARHEIGHT);
    }

    // Test-only: clear all module state.
    static void resetForTests() {
        viewimage = null;
        viewwidth = scaledviewwidth = viewheight = 0;
        viewwindowx = viewwindowy = 0;
        ylookup = new int[MAXHEIGHT];
        columnofs = new int[MAXWIDTH];
        translations = new byte[3][256];
        dcColormap = null;
        dcColormapOfs = 0;
        dcX = dcYl = dcYh = 0;
        dcIscale = dcTexturemid = 0;
        dcSource = null;
        dcSourceOfs = 0;
        dccount = 0;
        dsY = dsX1 = dsX2 = 0;
        dsColormap = null;
        dsColormapOfs = 0;
        dsXfrac = dsYfrac = dsXstep = dsYstep = 0;
        dsSource = null;
        dsSourceOfs = 0;
        dscount = 0;
        fuzzpos = 0;
        dcTranslation = null;
        dcTranslationOfs = 0;
        translationtables = null;
    }
}
