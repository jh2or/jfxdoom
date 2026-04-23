// Port of linuxdoom-1.10/hu_lib.c — heads-up display widget library.
//
// Three widgets, each a nested class mirroring the C structs:
//   TextLine  (hu_textline_t)  — fixed-capacity char buffer + patch font
//   SText     (hu_stext_t)     — ring of TextLine rows (message history)
//   IText     (hu_itext_t)     — TextLine + left-margin + key handler
//
// The draw/erase paths go through VVideo.vDrawPatchDirect and
// RDraw.rVideoErase — same functions the C would call through
// V_DrawPatchDirect / R_VideoErase.
package doom.ui;

import doom.render.Patch;
import doom.render.RDraw;
import doom.system.Doomdef;

public final class HuLib {

    // hu_lib.h lines 30–31.
    public static final int BG = 1;
    public static final int FG = 0;

    // hu_lib.h lines 34–37.
    public static final int HU_CHARERASE = Doomdef.KEY_BACKSPACE;
    public static final int HU_MAXLINES = 4;
    public static final int HU_MAXLINELENGTH = 80;

    // hu_lib.h lines 45–59 — hu_textline_t.
    public static final class TextLine {
        public int x;
        public int y;
        public Patch[] f;            // font table (caller owns)
        public int sc;               // start character
        public char[] l = new char[HU_MAXLINELENGTH + 1];
        public int len;              // current length
        public int needsupdate;      // tic countdown
    }

    // hu_lib.h lines 65–75 — hu_stext_t.
    public static final class SText {
        public TextLine[] l = new TextLine[HU_MAXLINES];
        public int h;                // height in lines
        public int cl;               // current line number
        public boolean[] on;         // C `boolean*` — size-1 array; see deviation 19
        public boolean laston;
    }

    // hu_lib.h lines 81–92 — hu_itext_t.
    public static final class IText {
        public TextLine l = new TextLine();
        public int lm;               // left margin
        public boolean[] on;         // see deviation 19
        public boolean laston;
    }

    // hu_lib.c line 38 — #define noterased viewwindowx.
    // The C macro aliases the renderer-global so erase path can probe
    // whether the view is reduced. We read RDraw.viewwindowx directly.

    // hu_lib.c lines 42–44 — HUlib_init is empty.
    public static void huLibInit() {
    }

    // hu_lib.c lines 46–51 — HUlib_clearTextLine.
    public static void huLibClearTextLine(TextLine t) {
        t.len = 0;
        t.l[0] = 0;
        t.needsupdate = 1; // C: true; tracked as int countdown elsewhere
    }

    // hu_lib.c lines 53–66 — HUlib_initTextLine.
    public static void huLibInitTextLine(TextLine t, int x, int y, Patch[] f, int sc) {
        t.x = x;
        t.y = y;
        t.f = f;
        t.sc = sc;
        huLibClearTextLine(t);
    }

    // hu_lib.c lines 68–84 — HUlib_addCharToTextLine.
    public static boolean huLibAddCharToTextLine(TextLine t, char ch) {
        if (t.len == HU_MAXLINELENGTH) {
            return false;
        }
        t.l[t.len++] = ch;
        t.l[t.len] = 0;
        t.needsupdate = 4;
        return true;
    }

    // hu_lib.c lines 86–97 — HUlib_delCharFromTextLine.
    public static boolean huLibDelCharFromTextLine(TextLine t) {
        if (t.len == 0) {
            return false;
        }
        t.l[--t.len] = 0;
        t.needsupdate = 4;
        return true;
    }

    // hu_lib.c lines 99–139 — HUlib_drawTextLine.
    public static void huLibDrawTextLine(TextLine l, boolean drawcursor) {
        int x = l.x;
        for (int i = 0; i < l.len; i++) {
            int c = Character.toUpperCase(l.l[i]) & 0xFF; // C: unsigned char
            if (c != ' ' && c >= l.sc && c <= '_') {
                int w = l.f[c - l.sc].width;
                if (x + w > Doomdef.SCREENWIDTH) {
                    break;
                }
                VVideo.vDrawPatchDirect(x, l.y, FG, l.f[c - l.sc]);
                x += w;
            } else {
                x += 4;
                if (x >= Doomdef.SCREENWIDTH) {
                    break;
                }
            }
        }

        if (drawcursor
                && x + l.f['_' - l.sc].width <= Doomdef.SCREENWIDTH) {
            VVideo.vDrawPatchDirect(x, l.y, FG, l.f['_' - l.sc]);
        }
    }

    // hu_lib.c lines 143–174 — HUlib_eraseTextLine.
    // The C's `static boolean lastautomapactive` is a function-level
    // static that's assigned but never read — dead code. Lifted to a
    // package-private field to preserve the write.
    static boolean lastautomapactive = true;

    public static void huLibEraseTextLine(TextLine l) {
        // Only erases when NOT in automap and the screen is reduced,
        // and the text must either need updating or refreshing
        // (because of a recent change back from the automap)
        if (!AmMap.automapactive && RDraw.viewwindowx != 0 && l.needsupdate != 0) {
            int lh = l.f[0].height + 1;
            int y = l.y;
            int yoffset = y * Doomdef.SCREENWIDTH;
            for (; y < l.y + lh; y++, yoffset += Doomdef.SCREENWIDTH) {
                if (y < RDraw.viewwindowy || y >= RDraw.viewwindowy + RDraw.viewheight) {
                    RDraw.rVideoErase(yoffset, Doomdef.SCREENWIDTH);
                } else {
                    RDraw.rVideoErase(yoffset, RDraw.viewwindowx);
                    RDraw.rVideoErase(yoffset + RDraw.viewwindowx + RDraw.viewwidth,
                            RDraw.viewwindowx);
                }
            }
        }

        lastautomapactive = AmMap.automapactive;
        if (l.needsupdate != 0) {
            l.needsupdate--;
        }
    }

    // hu_lib.c lines 176–198 — HUlib_initSText.
    public static void huLibInitSText(SText s, int x, int y, int h,
                                      Patch[] font, int startchar, boolean[] on) {
        s.h = h;
        s.on = on;
        s.laston = true;
        s.cl = 0;
        for (int i = 0; i < h; i++) {
            s.l[i] = new TextLine();
            huLibInitTextLine(s.l[i], x, y - i * (font[0].height + 1),
                    font, startchar);
        }
    }

    // hu_lib.c lines 200–214 — HUlib_addLineToSText.
    public static void huLibAddLineToSText(SText s) {
        if (++s.cl == s.h) {
            s.cl = 0;
        }
        huLibClearTextLine(s.l[s.cl]);

        for (int i = 0; i < s.h; i++) {
            s.l[i].needsupdate = 4;
        }
    }

    // hu_lib.c lines 216–229 — HUlib_addMessageToSText.
    public static void huLibAddMessageToSText(SText s, String prefix, String msg) {
        huLibAddLineToSText(s);
        if (prefix != null) {
            for (int i = 0; i < prefix.length(); i++) {
                huLibAddCharToTextLine(s.l[s.cl], prefix.charAt(i));
            }
        }
        for (int i = 0; i < msg.length(); i++) {
            huLibAddCharToTextLine(s.l[s.cl], msg.charAt(i));
        }
    }

    // hu_lib.c lines 231–252 — HUlib_drawSText.
    public static void huLibDrawSText(SText s) {
        if (!s.on[0]) {
            return;
        }

        for (int i = 0; i < s.h; i++) {
            int idx = s.cl - i;
            if (idx < 0) {
                idx += s.h;
            }
            TextLine l = s.l[idx];
            huLibDrawTextLine(l, false);
        }
    }

    // hu_lib.c lines 254–267 — HUlib_eraseSText.
    public static void huLibEraseSText(SText s) {
        for (int i = 0; i < s.h; i++) {
            if (s.laston && !s.on[0]) {
                s.l[i].needsupdate = 4;
            }
            huLibEraseTextLine(s.l[i]);
        }
        s.laston = s.on[0];
    }

    // hu_lib.c lines 269–282 — HUlib_initIText.
    public static void huLibInitIText(IText it, int x, int y,
                                      Patch[] font, int startchar, boolean[] on) {
        it.lm = 0;
        it.on = on;
        it.laston = true;
        huLibInitTextLine(it.l, x, y, font, startchar);
    }

    // hu_lib.c lines 286–290 — HUlib_delCharFromIText.
    public static void huLibDelCharFromIText(IText it) {
        if (it.l.len != it.lm) {
            huLibDelCharFromTextLine(it.l);
        }
    }

    // hu_lib.c lines 292–296 — HUlib_eraseLineFromIText.
    public static void huLibEraseLineFromIText(IText it) {
        while (it.lm != it.l.len) {
            huLibDelCharFromTextLine(it.l);
        }
    }

    // hu_lib.c lines 298–303 — HUlib_resetIText.
    public static void huLibResetIText(IText it) {
        it.lm = 0;
        huLibClearTextLine(it.l);
    }

    // hu_lib.c lines 305–313 — HUlib_addPrefixToIText.
    public static void huLibAddPrefixToIText(IText it, String str) {
        for (int i = 0; i < str.length(); i++) {
            huLibAddCharToTextLine(it.l, str.charAt(i));
        }
        it.lm = it.l.len;
    }

    // hu_lib.c lines 315–334 — HUlib_keyInIText.
    public static boolean huLibKeyInIText(IText it, int ch) {
        ch &= 0xFF; // C: unsigned char
        if (ch >= ' ' && ch <= '_') {
            huLibAddCharToTextLine(it.l, (char) ch);
        } else if (ch == Doomdef.KEY_BACKSPACE) {
            huLibDelCharFromIText(it);
        } else if (ch != Doomdef.KEY_ENTER) {
            return false;
        }
        return true;
    }

    // hu_lib.c lines 336–345 — HUlib_drawIText.
    public static void huLibDrawIText(IText it) {
        TextLine l = it.l;
        if (!it.on[0]) {
            return;
        }
        huLibDrawTextLine(l, true);
    }

    // hu_lib.c lines 347–353 — HUlib_eraseIText.
    public static void huLibEraseIText(IText it) {
        if (it.laston && !it.on[0]) {
            it.l.needsupdate = 4;
        }
        huLibEraseTextLine(it.l);
        it.laston = it.on[0];
    }

    private HuLib() {
    }
}
