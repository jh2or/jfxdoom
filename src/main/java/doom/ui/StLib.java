// Port of linuxdoom-1.10/st_lib.c — status-bar widgets.
//
// Four widget types, all nested classes mirroring the C structs:
//   Number   — right-justified digit pile backed by a patch-per-digit
//   Percent  — Number plus a static "%" glyph
//   MultIcon — single patch chosen by an index into an array
//   BinIcon  — single patch, drawn or erased depending on a boolean
//
// All four pull "current value / visibility" from caller-owned
// `int[1]` / `boolean[1]` holders (deviation 19 — Java cannot take the
// address of a primitive). The draw path mirrors the C exactly,
// calling VVideo.vDrawPatch / vCopyRect against the status-bar
// background layer (screen 4, stored as BG below).
package doom.ui;

import doom.render.Patch;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class StLib {

    // st_lib.h lines 33–34 — status-bar screen numbering, distinct
    // from HuLib's 0/1 pair.
    public static final int BG = 4;
    public static final int FG = 0;

    // st_lib.c line 56 — STMINUS patch cache.
    // Loaded by stLibInit() once the WAD is mounted.
    public static Patch sttminus;

    // st_lib.h lines 45–70 — st_number_t.
    public static final class Number {
        public int x;
        public int y;
        public int width;            // max # of digits
        public int oldnum;
        public int[] num;            // caller-owned int holder
        public boolean[] on;
        public Patch[] p;            // 0..9 digit patches
        public int data;
    }

    // st_lib.h lines 76–84 — st_percent_t.
    public static final class Percent {
        public Number n = new Number();
        public Patch p;              // percent sign glyph
    }

    // st_lib.h lines 89–111 — st_multicon_t.
    public static final class MultIcon {
        public int x;
        public int y;
        public int oldinum;
        public int[] inum;           // caller-owned int holder
        public boolean[] on;
        public Patch[] p;            // icon list
        public int data;
    }

    // st_lib.h lines 118–138 — st_binicon_t.
    public static final class BinIcon {
        public int x;
        public int y;
        public int oldval;           // C stores an int; 0/1 range
        public boolean[] val;
        public boolean[] on;
        public Patch p;
        public int data;
    }

    // st_lib.c lines 58–61 — STlib_init.
    // The C casts the lump directly to `patch_t*`; we parse it.
    public static void stLibInit() {
        try {
            sttminus = Patch.fromLump(WWad.wCacheLumpName("STTMINUS", ZZone.PU_STATIC));
        } catch (IOException e) {
            throw ISystem.iError("Couldn't load STTMINUS: %s", e.getMessage());
        }
    }

    // st_lib.c lines 66–82 — STlib_initNum.
    public static void stLibInitNum(Number n, int x, int y,
                                    Patch[] pl, int[] num, boolean[] on, int width) {
        n.x = x;
        n.y = y;
        n.oldnum = 0;
        n.width = width;
        n.num = num;
        n.on = on;
        n.p = pl;
    }

    // st_lib.c lines 90–148 — STlib_drawNum.
    // "Note: worth the trouble?" — faithful to the C comment.
    public static void stLibDrawNum(Number n, boolean refresh) {
        int numdigits = n.width;
        int num = n.num[0];

        int w = n.p[0].width;
        int h = n.p[0].height;
        int x = n.x;

        boolean neg;

        n.oldnum = n.num[0];

        neg = num < 0;

        if (neg) {
            if (numdigits == 2 && num < -9) {
                num = -9;
            } else if (numdigits == 3 && num < -99) {
                num = -99;
            }
            num = -num;
        }

        // clear the area
        x = n.x - numdigits * w;

        if (n.y - StStuff.ST_Y < 0) {
            throw ISystem.iError("drawNum: n->y - ST_Y < 0");
        }

        VVideo.vCopyRect(x, n.y - StStuff.ST_Y, BG, w * numdigits, h, x, n.y, FG);

        // if non-number, do not draw it
        if (num == 1994) {
            return;
        }

        x = n.x;

        // in the special case of 0, you draw 0
        if (num == 0) {
            VVideo.vDrawPatch(x - w, n.y, FG, n.p[0]);
        }

        // draw the new number
        while (num != 0 && numdigits-- != 0) {
            x -= w;
            VVideo.vDrawPatch(x, n.y, FG, n.p[num % 10]);
            num /= 10;
        }

        // draw a minus sign if necessary
        if (neg) {
            VVideo.vDrawPatch(x - 8, n.y, FG, sttminus);
        }
    }

    // st_lib.c lines 152–158 — STlib_updateNum.
    public static void stLibUpdateNum(Number n, boolean refresh) {
        if (n.on[0]) {
            stLibDrawNum(n, refresh);
        }
    }

    // st_lib.c lines 163–174 — STlib_initPercent.
    public static void stLibInitPercent(Percent p, int x, int y,
                                        Patch[] pl, int[] num, boolean[] on,
                                        Patch percent) {
        stLibInitNum(p.n, x, y, pl, num, on, 3);
        p.p = percent;
    }

    // st_lib.c lines 179–188 — STlib_updatePercent.
    // The C parameter is `int refresh` (not boolean); non-zero means
    // "redraw the % glyph this frame". Preserve int semantics.
    public static void stLibUpdatePercent(Percent per, int refresh) {
        if (refresh != 0 && per.n.on[0]) {
            VVideo.vDrawPatch(per.n.x, per.n.y, FG, per.p);
        }
        stLibUpdateNum(per.n, refresh != 0);
    }

    // st_lib.c lines 192–207 — STlib_initMultIcon.
    public static void stLibInitMultIcon(MultIcon i, int x, int y,
                                         Patch[] il, int[] inum, boolean[] on) {
        i.x = x;
        i.y = y;
        i.oldinum = -1;
        i.inum = inum;
        i.on = on;
        i.p = il;
    }

    // st_lib.c lines 211–240 — STlib_updateMultIcon.
    public static void stLibUpdateMultIcon(MultIcon mi, boolean refresh) {
        int w;
        int h;
        int x;
        int y;

        if (mi.on[0]
                && (mi.oldinum != mi.inum[0] || refresh)
                && mi.inum[0] != -1) {
            if (mi.oldinum != -1) {
                x = mi.x - mi.p[mi.oldinum].leftoffset;
                y = mi.y - mi.p[mi.oldinum].topoffset;
                w = mi.p[mi.oldinum].width;
                h = mi.p[mi.oldinum].height;

                if (y - StStuff.ST_Y < 0) {
                    throw ISystem.iError("updateMultIcon: y - ST_Y < 0");
                }

                VVideo.vCopyRect(x, y - StStuff.ST_Y, BG, w, h, x, y, FG);
            }
            VVideo.vDrawPatch(mi.x, mi.y, FG, mi.p[mi.inum[0]]);
            mi.oldinum = mi.inum[0];
        }
    }

    // st_lib.c lines 244–259 — STlib_initBinIcon.
    public static void stLibInitBinIcon(BinIcon b, int x, int y,
                                        Patch i, boolean[] val, boolean[] on) {
        b.x = x;
        b.y = y;
        b.oldval = 0;
        b.val = val;
        b.on = on;
        b.p = i;
    }

    // st_lib.c lines 263–292 — STlib_updateBinIcon.
    public static void stLibUpdateBinIcon(BinIcon bi, boolean refresh) {
        int x;
        int y;
        int w;
        int h;

        // The C compares `oldval != *val` with both treated as int
        // (boolean is int in C). Translate as boolean-int: `oldval` is
        // the "last seen" flag state (0 or 1), `*val` is current.
        int curval = bi.val[0] ? 1 : 0;
        if (bi.on[0] && (bi.oldval != curval || refresh)) {
            x = bi.x - bi.p.leftoffset;
            y = bi.y - bi.p.topoffset;
            w = bi.p.width;
            h = bi.p.height;

            if (y - StStuff.ST_Y < 0) {
                throw ISystem.iError("updateBinIcon: y - ST_Y < 0");
            }

            if (bi.val[0]) {
                VVideo.vDrawPatch(bi.x, bi.y, FG, bi.p);
            } else {
                VVideo.vCopyRect(x, y - StStuff.ST_Y, BG, w, h, x, y, FG);
            }

            bi.oldval = curval;
        }
    }

    private StLib() {
    }
}
