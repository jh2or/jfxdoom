// Port of linuxdoom-1.10/am_map.c — full automap.
//
// Vector-line overhead view of the active level. Replaces the
// BSP renderer when active. Walls are picked by Linedef.flags +
// front/back-sector contrast (ML_MAPPED, ML_SECRET, locked-door
// teleporters, floor-step, ceiling-step). The player is drawn as
// a 7-segment "arrow" line-character (or a 16-segment "ddt-marker"
// arrow when the iddt cheat is on).
//
// Input: TAB toggles, arrows pan when follow-mode is off, +/-
// zoom, F toggles follow-mode, G toggles the gridline overlay,
// M drops a numbered mark, C clears marks, 0 zooms all-the-way-out,
// iddt cycles cheat levels (show all walls / show all things).
//
// The C code freely uses pointer arithmetic on the screen-0
// framebuffer (`fb = screens[0]`); the Java port keeps the same
// shape — fb is a byte[] reference + the put-dot macro indexes by
// (y * f_w + x).
package doom.ui;

import doom.fixed.Fixed;
import doom.play.MCheat;
import doom.play.Mobj;
import doom.play.PSetup;
import doom.play.Player;
import doom.render.Line;
import doom.render.Patch;
import doom.render.Tables;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class AmMap {

    // am_map.h lines 26–28 — ev_keyup tags ST_Responder filters on.
    public static final int AM_MSGHEADER  = ('a' << 24) + ('m' << 16);
    public static final int AM_MSGENTERED = AM_MSGHEADER | ('e' << 8);
    public static final int AM_MSGEXITED  = AM_MSGHEADER | ('x' << 8);

    // am_map.c lines 52–85 — palette ranges + automap colours.
    public static final int REDS              = 256 - 5 * 16;
    public static final int REDRANGE          = 16;
    public static final int BLUES             = 256 - 4 * 16 + 8;
    public static final int BLUERANGE         = 8;
    public static final int GREENS            = 7 * 16;
    public static final int GREENRANGE        = 16;
    public static final int GRAYS             = 6 * 16;
    public static final int GRAYSRANGE        = 16;
    public static final int BROWNS            = 4 * 16;
    public static final int BROWNRANGE        = 16;
    public static final int YELLOWS           = 256 - 32 + 7;
    public static final int YELLOWRANGE       = 1;
    public static final int BLACK             = 0;
    public static final int WHITE             = 256 - 47;

    public static final int BACKGROUND        = BLACK;
    public static final int YOURCOLORS        = WHITE;
    public static final int YOURRANGE         = 0;
    public static final int WALLCOLORS        = REDS;
    public static final int WALLRANGE         = REDRANGE;
    public static final int TSWALLCOLORS      = GRAYS;
    public static final int TSWALLRANGE       = GRAYSRANGE;
    public static final int FDWALLCOLORS      = BROWNS;
    public static final int FDWALLRANGE       = BROWNRANGE;
    public static final int CDWALLCOLORS      = YELLOWS;
    public static final int CDWALLRANGE       = YELLOWRANGE;
    public static final int THINGCOLORS       = GREENS;
    public static final int THINGRANGE        = GREENRANGE;
    public static final int SECRETWALLCOLORS  = WALLCOLORS;
    public static final int SECRETWALLRANGE   = WALLRANGE;
    public static final int GRIDCOLORS        = GRAYS + GRAYSRANGE / 2;
    public static final int GRIDRANGE         = 0;
    public static final int XHAIRCOLORS       = GRAYS;

    // am_map.c line 88.
    public static final int FB = 0;

    // am_map.c lines 90–104 — input keys + mark count.
    public static final int AM_PANDOWNKEY  = Doomdef.KEY_DOWNARROW;
    public static final int AM_PANUPKEY    = Doomdef.KEY_UPARROW;
    public static final int AM_PANRIGHTKEY = Doomdef.KEY_RIGHTARROW;
    public static final int AM_PANLEFTKEY  = Doomdef.KEY_LEFTARROW;
    public static final int AM_ZOOMINKEY   = '=';
    public static final int AM_ZOOMOUTKEY  = '-';
    public static final int AM_STARTKEY    = Doomdef.KEY_TAB;
    public static final int AM_ENDKEY      = Doomdef.KEY_TAB;
    public static final int AM_GOBIGKEY    = '0';
    public static final int AM_FOLLOWKEY   = 'f';
    public static final int AM_GRIDKEY     = 'g';
    public static final int AM_MARKKEY     = 'm';
    public static final int AM_CLEARMARKKEY = 'c';
    public static final int AM_NUMMARKPOINTS = 10;

    // p_local.h MAPBLOCKUNITS / PLAYERRADIUS — used here only.
    public static final int MAPBLOCKUNITS = 128;
    public static final int PLAYERRADIUS  = 16 * Fixed.FRACUNIT;

    // am_map.c line 107 — entry scale (0.2 in fixed).
    public static final int INITSCALEMTOF = (int) (0.2 * Fixed.FRACUNIT);
    // am_map.c lines 110–116 — pan + zoom rates.
    public static final int F_PANINC      = 4;
    public static final int M_ZOOMIN      = (int) (1.02 * Fixed.FRACUNIT);
    public static final int M_ZOOMOUT     = (int) (Fixed.FRACUNIT / 1.02);

    // am_map.c line 126.
    public static final int LINE_NEVERSEE = Line.ML_DONTDRAW;

    // am_map.c lines 128–146 — geometry helper structs.
    public static final class Fpoint { public int x, y; }
    public static final class Fline  { public final Fpoint a = new Fpoint(), b = new Fpoint(); }
    public static final class Mpoint { public int x, y; }
    public static final class Mline  { public final Mpoint a = new Mpoint(), b = new Mpoint(); }

    // Bounding-box outcodes for the Cohen-Sutherland clipper.
    private static final int CLIP_LEFT   = 1;
    private static final int CLIP_RIGHT  = 2;
    private static final int CLIP_BOTTOM = 4;
    private static final int CLIP_TOP    = 8;

    // am_map.c lines 160–211 — vector arrows.
    private static final int RPLR  = (8 * PLAYERRADIUS) / 7;
    public static final Mline[] player_arrow = mlines(new int[][] {
            { -RPLR + RPLR / 8, 0,         RPLR, 0 },
            {  RPLR, 0,                    RPLR - RPLR / 2, RPLR / 4 },
            {  RPLR, 0,                    RPLR - RPLR / 2, -RPLR / 4 },
            { -RPLR + RPLR / 8, 0,        -RPLR - RPLR / 8, RPLR / 4 },
            { -RPLR + RPLR / 8, 0,        -RPLR - RPLR / 8, -RPLR / 4 },
            { -RPLR + 3 * RPLR / 8, 0,    -RPLR + RPLR / 8, RPLR / 4 },
            { -RPLR + 3 * RPLR / 8, 0,    -RPLR + RPLR / 8, -RPLR / 4 },
    });

    public static final Mline[] cheat_player_arrow = mlines(new int[][] {
            { -RPLR + RPLR / 8, 0,         RPLR, 0 },
            {  RPLR, 0,                    RPLR - RPLR / 2, RPLR / 6 },
            {  RPLR, 0,                    RPLR - RPLR / 2, -RPLR / 6 },
            { -RPLR + RPLR / 8, 0,        -RPLR - RPLR / 8, RPLR / 6 },
            { -RPLR + RPLR / 8, 0,        -RPLR - RPLR / 8, -RPLR / 6 },
            { -RPLR + 3 * RPLR / 8, 0,    -RPLR + RPLR / 8, RPLR / 6 },
            { -RPLR + 3 * RPLR / 8, 0,    -RPLR + RPLR / 8, -RPLR / 6 },
            { -RPLR / 2, 0,               -RPLR / 2, -RPLR / 6 },
            { -RPLR / 2, -RPLR / 6,       -RPLR / 2 + RPLR / 6, -RPLR / 6 },
            { -RPLR / 2 + RPLR / 6, -RPLR / 6, -RPLR / 2 + RPLR / 6, RPLR / 4 },
            { -RPLR / 6, 0,               -RPLR / 6, -RPLR / 6 },
            { -RPLR / 6, -RPLR / 6,       0, -RPLR / 6 },
            { 0, -RPLR / 6,               0, RPLR / 4 },
            { RPLR / 6, RPLR / 4,         RPLR / 6, -RPLR / 7 },
            { RPLR / 6, -RPLR / 7,        RPLR / 6 + RPLR / 32, -RPLR / 7 - RPLR / 32 },
            { RPLR / 6 + RPLR / 32, -RPLR / 7 - RPLR / 32, RPLR / 6 + RPLR / 10, -RPLR / 7 },
    });

    // FRACUNIT-scale guy triangles (thing markers in cheat mode).
    private static final int RTRI = Fixed.FRACUNIT;
    public static final Mline[] triangle_guy = mlines(new int[][] {
            { (int)(-0.867 * RTRI), (int)(-0.5 * RTRI),  (int)(0.867 * RTRI), (int)(-0.5 * RTRI) },
            { (int)( 0.867 * RTRI), (int)(-0.5 * RTRI),  0, RTRI },
            { 0, RTRI,                                   (int)(-0.867 * RTRI), (int)(-0.5 * RTRI) },
    });

    public static final Mline[] thintriangle_guy = mlines(new int[][] {
            { (int)(-0.5 * RTRI), (int)(-0.7 * RTRI), RTRI, 0 },
            { RTRI, 0,                                (int)(-0.5 * RTRI), (int)(0.7 * RTRI) },
            { (int)(-0.5 * RTRI), (int)(0.7 * RTRI),  (int)(-0.5 * RTRI), (int)(-0.7 * RTRI) },
    });

    private static Mline[] mlines(int[][] coords) {
        Mline[] arr = new Mline[coords.length];
        for (int i = 0; i < coords.length; i++) {
            arr[i] = new Mline();
            arr[i].a.x = coords[i][0];
            arr[i].a.y = coords[i][1];
            arr[i].b.x = coords[i][2];
            arr[i].b.y = coords[i][3];
        }
        return arr;
    }

    // am_map.c lines 216–290 — module state.
    public static int     cheating;
    public static int     grid;
    public static int     leveljuststarted = 1;
    public static boolean automapactive;
    public static int     finit_width  = Doomdef.SCREENWIDTH;
    public static int     finit_height = Doomdef.SCREENHEIGHT - 32;

    public static int     f_x, f_y;
    public static int     f_w, f_h;
    public static int     lightlev;
    public static byte[]  fb;
    public static int     amclock;

    public static final Mpoint m_paninc = new Mpoint();
    public static int     mtof_zoommul;
    public static int     ftom_zoommul;

    public static int     m_x, m_y;
    public static int     m_x2, m_y2;

    public static int     m_w, m_h;

    public static int     min_x, min_y;
    public static int     max_x, max_y;
    public static int     max_w, max_h;
    public static int     min_w, min_h;
    public static int     min_scale_mtof;
    public static int     max_scale_mtof;

    public static int     old_m_w, old_m_h;
    public static int     old_m_x, old_m_y;
    public static final Mpoint f_oldloc = new Mpoint();

    public static int     scale_mtof = INITSCALEMTOF;
    public static int     scale_ftom;

    public static Player  plr;
    public static final Patch[] marknums = new Patch[10];
    public static final Mpoint[] markpoints = makeMarkpoints();
    public static int     markpointnum;
    public static int     followplayer = 1;

    public static final byte[] cheat_amap_seq = {
            (byte) 0xb2, 0x26, 0x26, 0x2e, (byte) 0xff };
    public static final MCheat.Cheatseq cheat_amap = new MCheat.Cheatseq(cheat_amap_seq);

    public static boolean stopped = true;

    private static Mpoint[] makeMarkpoints() {
        Mpoint[] arr = new Mpoint[AM_NUMMARKPOINTS];
        for (int i = 0; i < AM_NUMMARKPOINTS; i++) arr[i] = new Mpoint();
        return arr;
    }

    private AmMap() {
    }

    //
    // FTOM / MTOF / CXMTOF / CYMTOF — am_map.c lines 118–123.
    // Inline helpers; FTOM left-shifts to fixed before scaling.
    //
    public static int ftom(int x) {
        return Fixed.mul(x << Fixed.FRACBITS, scale_ftom);
    }

    public static int mtof(int x) {
        return Fixed.mul(x, scale_mtof) >> Fixed.FRACBITS;
    }

    public static int cxmtof(int x) {
        return f_x + mtof(x - m_x);
    }

    public static int cymtof(int y) {
        return f_y + (f_h - mtof(y - m_y));
    }

    //
    // AM_activateNewScale
    // am_map.c lines 327–337.
    public static void amActivateNewScale() {
        m_x += m_w / 2;
        m_y += m_h / 2;
        m_w = ftom(f_w);
        m_h = ftom(f_h);
        m_x -= m_w / 2;
        m_y -= m_h / 2;
        m_x2 = m_x + m_w;
        m_y2 = m_y + m_h;
    }

    //
    // AM_saveScaleAndLoc / AM_restoreScaleAndLoc
    // am_map.c lines 342–372.
    public static void amSaveScaleAndLoc() {
        old_m_x = m_x; old_m_y = m_y;
        old_m_w = m_w; old_m_h = m_h;
    }

    public static void amRestoreScaleAndLoc() {
        m_w = old_m_w;
        m_h = old_m_h;
        if (followplayer == 0) {
            m_x = old_m_x;
            m_y = old_m_y;
        } else {
            m_x = plr.mo.x - m_w / 2;
            m_y = plr.mo.y - m_h / 2;
        }
        m_x2 = m_x + m_w;
        m_y2 = m_y + m_h;
        scale_mtof = Fixed.div(f_w << Fixed.FRACBITS, m_w);
        scale_ftom = Fixed.div(Fixed.FRACUNIT, scale_mtof);
    }

    //
    // AM_addMark
    // am_map.c lines 377–383.
    public static void amAddMark() {
        markpoints[markpointnum].x = m_x + m_w / 2;
        markpoints[markpointnum].y = m_y + m_h / 2;
        markpointnum = (markpointnum + 1) % AM_NUMMARKPOINTS;
    }

    //
    // AM_findMinMaxBoundaries
    // am_map.c lines 389–423.
    public static void amFindMinMaxBoundaries() {
        min_x = min_y = Integer.MAX_VALUE;
        max_x = max_y = -Integer.MAX_VALUE;

        for (int i = 0; i < PSetup.numvertexes; i++) {
            int vx = PSetup.vertexes[i].x;
            int vy = PSetup.vertexes[i].y;
            if (vx < min_x)      min_x = vx;
            else if (vx > max_x) max_x = vx;
            if (vy < min_y)      min_y = vy;
            else if (vy > max_y) max_y = vy;
        }

        max_w = max_x - min_x;
        max_h = max_y - min_y;
        min_w = 2 * PLAYERRADIUS;
        min_h = 2 * PLAYERRADIUS;

        int a = Fixed.div(f_w << Fixed.FRACBITS, max_w);
        int b = Fixed.div(f_h << Fixed.FRACBITS, max_h);
        min_scale_mtof = a < b ? a : b;
        max_scale_mtof = Fixed.div(f_h << Fixed.FRACBITS, 2 * PLAYERRADIUS);
    }

    //
    // AM_changeWindowLoc
    // am_map.c lines 429–452.
    public static void amChangeWindowLoc() {
        if (m_paninc.x != 0 || m_paninc.y != 0) {
            followplayer = 0;
            f_oldloc.x = Integer.MAX_VALUE;
        }

        m_x += m_paninc.x;
        m_y += m_paninc.y;

        if (m_x + m_w / 2 > max_x) m_x = max_x - m_w / 2;
        else if (m_x + m_w / 2 < min_x) m_x = min_x - m_w / 2;

        if (m_y + m_h / 2 > max_y) m_y = max_y - m_h / 2;
        else if (m_y + m_h / 2 < min_y) m_y = min_y - m_h / 2;

        m_x2 = m_x + m_w;
        m_y2 = m_y + m_h;
    }

    //
    // AM_initVariables
    // am_map.c lines 458–497.
    public static void amInitVariables() {
        DEvent st_notify = new DEvent();
        st_notify.type  = DEvent.EV_KEYUP;
        st_notify.data1 = AM_MSGENTERED;

        automapactive = true;
        fb = VVideo.screens[0];

        f_oldloc.x = Integer.MAX_VALUE;
        amclock = 0;
        lightlev = 0;

        m_paninc.x = m_paninc.y = 0;
        ftom_zoommul = Fixed.FRACUNIT;
        mtof_zoommul = Fixed.FRACUNIT;

        m_w = ftom(f_w);
        m_h = ftom(f_h);

        int pnum = GGame.consoleplayer;
        if (!GGame.playeringame[pnum]) {
            for (pnum = 0; pnum < Doomdef.MAXPLAYERS; pnum++) {
                if (GGame.playeringame[pnum]) break;
            }
        }

        plr = GGame.players[pnum];
        m_x = plr.mo.x - m_w / 2;
        m_y = plr.mo.y - m_h / 2;
        amChangeWindowLoc();

        old_m_x = m_x; old_m_y = m_y;
        old_m_w = m_w; old_m_h = m_h;

        // inform the status bar of the mode flip
        StStuff.stResponder(st_notify);
    }

    //
    // AM_loadPics / AM_unloadPics
    // am_map.c lines 502–522.
    public static void amLoadPics() {
        for (int i = 0; i < 10; i++) {
            try {
                marknums[i] = Patch.fromLump(WWad.wCacheLumpName(
                        "AMMNUM" + i, ZZone.PU_STATIC));
            } catch (IOException | ISystem.DoomError e) {
                // Some shareware WADs ship without the AMMNUMn lumps;
                // marks just don't render then. Same shape as the
                // huStart / stStart guards in deviation 34.
                marknums[i] = null;
            }
        }
    }

    public static void amUnloadPics() {
        // Java GC + cache tag-flip not modelled — leave the slots.
    }

    public static void amClearMarks() {
        for (int i = 0; i < AM_NUMMARKPOINTS; i++) {
            markpoints[i].x = -1;
        }
        markpointnum = 0;
    }

    //
    // AM_LevelInit
    // am_map.c lines 537–552.
    public static void amLevelInit() {
        leveljuststarted = 0;
        f_x = f_y = 0;
        f_w = finit_width;
        f_h = finit_height;
        amClearMarks();
        amFindMinMaxBoundaries();
        scale_mtof = Fixed.div(min_scale_mtof, (int) (0.7 * Fixed.FRACUNIT));
        if (scale_mtof > max_scale_mtof) scale_mtof = min_scale_mtof;
        scale_ftom = Fixed.div(Fixed.FRACUNIT, scale_mtof);
    }

    //
    // AM_Stop
    // am_map.c lines 560–568.
    public static void amStop() {
        DEvent st_notify = new DEvent();
        st_notify.type  = DEvent.EV_KEYUP;
        st_notify.data1 = AM_MSGEXITED;

        amUnloadPics();
        automapactive = false;
        StStuff.stResponder(st_notify);
        stopped = true;
    }

    //
    // AM_Start
    // am_map.c lines 573–587. Caches per-level patch + boundary state
    // so re-entering the automap on the same level skips the work.
    private static int lastlevel   = -1;
    private static int lastepisode = -1;

    public static void amStart() {
        if (!stopped) amStop();
        stopped = false;
        if (lastlevel != Doomstat.gamemap || lastepisode != Doomstat.gameepisode) {
            amLevelInit();
            lastlevel   = Doomstat.gamemap;
            lastepisode = Doomstat.gameepisode;
        }
        amInitVariables();
        amLoadPics();
    }

    //
    // AM_minOutWindowScale / AM_maxOutWindowScale
    // am_map.c lines 592–607.
    public static void amMinOutWindowScale() {
        scale_mtof = min_scale_mtof;
        scale_ftom = Fixed.div(Fixed.FRACUNIT, scale_mtof);
        amActivateNewScale();
    }

    public static void amMaxOutWindowScale() {
        scale_mtof = max_scale_mtof;
        scale_ftom = Fixed.div(Fixed.FRACUNIT, scale_mtof);
        amActivateNewScale();
    }

    // Persistent across responder calls (C statics).
    private static int responder_cheatstate;
    private static int responder_bigstate;

    //
    // AM_Responder
    // am_map.c lines 613–735.
    public static boolean amResponder(DEvent ev) {
        boolean rc = false;

        if (!automapactive) {
            if (ev.type == DEvent.EV_KEYDOWN && ev.data1 == AM_STARTKEY) {
                amStart();
                Doomstat.viewactive = false;
                rc = true;
            }
        } else if (ev.type == DEvent.EV_KEYDOWN) {
            rc = true;
            switch (ev.data1) {
                case AM_PANRIGHTKEY:
                    if (followplayer == 0) m_paninc.x = ftom(F_PANINC); else rc = false;
                    break;
                case AM_PANLEFTKEY:
                    if (followplayer == 0) m_paninc.x = -ftom(F_PANINC); else rc = false;
                    break;
                case AM_PANUPKEY:
                    if (followplayer == 0) m_paninc.y = ftom(F_PANINC); else rc = false;
                    break;
                case AM_PANDOWNKEY:
                    if (followplayer == 0) m_paninc.y = -ftom(F_PANINC); else rc = false;
                    break;
                case AM_ZOOMOUTKEY:
                    mtof_zoommul = M_ZOOMOUT;
                    ftom_zoommul = M_ZOOMIN;
                    break;
                case AM_ZOOMINKEY:
                    mtof_zoommul = M_ZOOMIN;
                    ftom_zoommul = M_ZOOMOUT;
                    break;
                case AM_ENDKEY:
                    responder_bigstate = 0;
                    Doomstat.viewactive = true;
                    amStop();
                    break;
                case AM_GOBIGKEY:
                    responder_bigstate = responder_bigstate == 0 ? 1 : 0;
                    if (responder_bigstate != 0) {
                        amSaveScaleAndLoc();
                        amMinOutWindowScale();
                    } else {
                        amRestoreScaleAndLoc();
                    }
                    break;
                case AM_FOLLOWKEY:
                    followplayer = followplayer == 0 ? 1 : 0;
                    f_oldloc.x = Integer.MAX_VALUE;
                    plr.message = followplayer != 0
                            ? "Follow Mode ON" : "Follow Mode OFF";
                    break;
                case AM_GRIDKEY:
                    grid = grid == 0 ? 1 : 0;
                    plr.message = grid != 0 ? "Grid ON" : "Grid OFF";
                    break;
                case AM_MARKKEY:
                    plr.message = "Marked Spot " + markpointnum;
                    amAddMark();
                    break;
                case AM_CLEARMARKKEY:
                    amClearMarks();
                    plr.message = "All Marks Cleared";
                    break;
                default:
                    responder_cheatstate = 0;
                    rc = false;
                    break;
            }
            if (Doomstat.deathmatch == 0
                    && MCheat.chtCheckCheat(cheat_amap, (char) ev.data1) != 0) {
                rc = false;
                cheating = (cheating + 1) % 3;
            }
        } else if (ev.type == DEvent.EV_KEYUP) {
            rc = false;
            switch (ev.data1) {
                case AM_PANRIGHTKEY:
                case AM_PANLEFTKEY:
                    if (followplayer == 0) m_paninc.x = 0;
                    break;
                case AM_PANUPKEY:
                case AM_PANDOWNKEY:
                    if (followplayer == 0) m_paninc.y = 0;
                    break;
                case AM_ZOOMOUTKEY:
                case AM_ZOOMINKEY:
                    mtof_zoommul = Fixed.FRACUNIT;
                    ftom_zoommul = Fixed.FRACUNIT;
                    break;
                default:
                    break;
            }
        }

        return rc;
    }

    //
    // AM_changeWindowScale
    // am_map.c lines 741–754.
    public static void amChangeWindowScale() {
        scale_mtof = Fixed.mul(scale_mtof, mtof_zoommul);
        scale_ftom = Fixed.div(Fixed.FRACUNIT, scale_mtof);

        if (scale_mtof < min_scale_mtof)      amMinOutWindowScale();
        else if (scale_mtof > max_scale_mtof) amMaxOutWindowScale();
        else                                  amActivateNewScale();
    }

    //
    // AM_doFollowPlayer
    // am_map.c lines 760–779. Snap-to-pixel-grid quantization keeps
    // the player arrow centered without sub-pixel jitter.
    public static void amDoFollowPlayer() {
        if (f_oldloc.x != plr.mo.x || f_oldloc.y != plr.mo.y) {
            m_x = ftom(mtof(plr.mo.x)) - m_w / 2;
            m_y = ftom(mtof(plr.mo.y)) - m_h / 2;
            m_x2 = m_x + m_w;
            m_y2 = m_y + m_h;
            f_oldloc.x = plr.mo.x;
            f_oldloc.y = plr.mo.y;
        }
    }

    //
    // AM_Ticker
    // am_map.c lines 805–827.
    public static void amTicker() {
        if (!automapactive) return;
        amclock++;
        if (followplayer != 0) amDoFollowPlayer();
        if (ftom_zoommul != Fixed.FRACUNIT) amChangeWindowScale();
        if (m_paninc.x != 0 || m_paninc.y != 0) amChangeWindowLoc();
    }

    //
    // AM_clearFB
    // am_map.c lines 833–836.
    public static void amClearFB(int color) {
        byte c = (byte) color;
        int total = f_w * f_h;
        for (int i = 0; i < total; i++) fb[i] = c;
    }

    //
    // AM_clipMline
    // am_map.c lines 846–971. Cohen-Sutherland clipper.
    // The C `DOOUTCODE` macro is inlined here as a helper.
    private static int outcode(int mx, int my) {
        int oc = 0;
        if (my < 0)         oc |= CLIP_TOP;
        else if (my >= f_h) oc |= CLIP_BOTTOM;
        if (mx < 0)         oc |= CLIP_LEFT;
        else if (mx >= f_w) oc |= CLIP_RIGHT;
        return oc;
    }

    public static boolean amClipMline(Mline ml, Fline fl) {
        int outcode1 = 0;
        int outcode2 = 0;
        int outside;

        // trivial reject based on map-y vs window-y.
        if (ml.a.y > m_y2)      outcode1 = CLIP_TOP;
        else if (ml.a.y < m_y)  outcode1 = CLIP_BOTTOM;

        if (ml.b.y > m_y2)      outcode2 = CLIP_TOP;
        else if (ml.b.y < m_y)  outcode2 = CLIP_BOTTOM;

        if ((outcode1 & outcode2) != 0) return false;

        if (ml.a.x < m_x)       outcode1 |= CLIP_LEFT;
        else if (ml.a.x > m_x2) outcode1 |= CLIP_RIGHT;

        if (ml.b.x < m_x)       outcode2 |= CLIP_LEFT;
        else if (ml.b.x > m_x2) outcode2 |= CLIP_RIGHT;

        if ((outcode1 & outcode2) != 0) return false;

        // transform to frame-buffer coordinates.
        fl.a.x = cxmtof(ml.a.x);
        fl.a.y = cymtof(ml.a.y);
        fl.b.x = cxmtof(ml.b.x);
        fl.b.y = cymtof(ml.b.y);

        outcode1 = outcode(fl.a.x, fl.a.y);
        outcode2 = outcode(fl.b.x, fl.b.y);
        if ((outcode1 & outcode2) != 0) return false;

        while ((outcode1 | outcode2) != 0) {
            outside = outcode1 != 0 ? outcode1 : outcode2;
            int tmpx = 0;
            int tmpy = 0;
            int dx, dy;

            if ((outside & CLIP_TOP) != 0) {
                dy = fl.a.y - fl.b.y;
                dx = fl.b.x - fl.a.x;
                tmpx = fl.a.x + (dx * fl.a.y) / dy;
                tmpy = 0;
            } else if ((outside & CLIP_BOTTOM) != 0) {
                dy = fl.a.y - fl.b.y;
                dx = fl.b.x - fl.a.x;
                tmpx = fl.a.x + (dx * (fl.a.y - f_h)) / dy;
                tmpy = f_h - 1;
            } else if ((outside & CLIP_RIGHT) != 0) {
                dy = fl.b.y - fl.a.y;
                dx = fl.b.x - fl.a.x;
                tmpy = fl.a.y + (dy * (f_w - 1 - fl.a.x)) / dx;
                tmpx = f_w - 1;
            } else if ((outside & CLIP_LEFT) != 0) {
                dy = fl.b.y - fl.a.y;
                dx = fl.b.x - fl.a.x;
                tmpy = fl.a.y + (dy * (-fl.a.x)) / dx;
                tmpx = 0;
            }

            if (outside == outcode1) {
                fl.a.x = tmpx;
                fl.a.y = tmpy;
                outcode1 = outcode(fl.a.x, fl.a.y);
            } else {
                fl.b.x = tmpx;
                fl.b.y = tmpy;
                outcode2 = outcode(fl.b.x, fl.b.y);
            }

            if ((outcode1 & outcode2) != 0) return false;
        }
        return true;
    }

    //
    // AM_drawFline
    // am_map.c lines 977–1049. Bresenham line draw into fb[].
    public static void amDrawFline(Fline fl, int color) {
        int x, y, dx, dy, sx, sy, ax, ay, d;

        // For debugging — clip-violating lines bail (matches the C).
        if (fl.a.x < 0 || fl.a.x >= f_w || fl.a.y < 0 || fl.a.y >= f_h
                || fl.b.x < 0 || fl.b.x >= f_w
                || fl.b.y < 0 || fl.b.y >= f_h) {
            return;
        }

        dx = fl.b.x - fl.a.x;
        ax = 2 * (dx < 0 ? -dx : dx);
        sx = dx < 0 ? -1 : 1;
        dy = fl.b.y - fl.a.y;
        ay = 2 * (dy < 0 ? -dy : dy);
        sy = dy < 0 ? -1 : 1;
        x = fl.a.x;
        y = fl.a.y;

        byte c = (byte) color;
        if (ax > ay) {
            d = ay - ax / 2;
            while (true) {
                fb[y * f_w + x] = c;
                if (x == fl.b.x) return;
                if (d >= 0) {
                    y += sy;
                    d -= ax;
                }
                x += sx;
                d += ay;
            }
        } else {
            d = ax - ay / 2;
            while (true) {
                fb[y * f_w + x] = c;
                if (y == fl.b.y) return;
                if (d >= 0) {
                    x += sx;
                    d -= ay;
                }
                y += sy;
                d += ax;
            }
        }
    }

    // Reusable scratch buffers (the C uses file-static `fline_t fl`
    // and `mline_t l`).
    private static final Fline scratchFline = new Fline();
    private static final Mline scratchMline = new Mline();

    //
    // AM_drawMline
    // am_map.c lines 1056–1064.
    public static void amDrawMline(Mline ml, int color) {
        if (amClipMline(ml, scratchFline)) {
            amDrawFline(scratchFline, color);
        }
    }

    //
    // AM_drawGrid
    // am_map.c lines 1071–1111.
    public static void amDrawGrid(int color) {
        int x, y;
        int start, end;
        Mline ml = scratchMline;
        int gridSize = MAPBLOCKUNITS << Fixed.FRACBITS;

        start = m_x;
        if (((start - PSetup.bmaporgx) % gridSize) != 0) {
            start += gridSize - ((start - PSetup.bmaporgx) % gridSize);
        }
        end = m_x + m_w;
        ml.a.y = m_y;
        ml.b.y = m_y + m_h;
        for (x = start; x < end; x += gridSize) {
            ml.a.x = x;
            ml.b.x = x;
            amDrawMline(ml, color);
        }

        start = m_y;
        if (((start - PSetup.bmaporgy) % gridSize) != 0) {
            start += gridSize - ((start - PSetup.bmaporgy) % gridSize);
        }
        end = m_y + m_h;
        ml.a.x = m_x;
        ml.b.x = m_x + m_w;
        for (y = start; y < end; y += gridSize) {
            ml.a.y = y;
            ml.b.y = y;
            amDrawMline(ml, color);
        }
    }

    //
    // AM_drawWalls
    // am_map.c lines 1117–1165.
    public static void amDrawWalls() {
        Mline l = scratchMline;
        for (int i = 0; i < PSetup.numlines; i++) {
            Line ln = PSetup.lines[i];
            l.a.x = ln.v1.x;
            l.a.y = ln.v1.y;
            l.b.x = ln.v2.x;
            l.b.y = ln.v2.y;
            if (cheating != 0 || (ln.flags & Line.ML_MAPPED) != 0) {
                if ((ln.flags & LINE_NEVERSEE) != 0 && cheating == 0) continue;
                if (ln.backsector == null) {
                    amDrawMline(l, WALLCOLORS + lightlev);
                } else {
                    if (ln.special == 39) {
                        // teleporters
                        amDrawMline(l, WALLCOLORS + WALLRANGE / 2);
                    } else if ((ln.flags & Line.ML_SECRET) != 0) {
                        if (cheating != 0) amDrawMline(l, SECRETWALLCOLORS + lightlev);
                        else               amDrawMline(l, WALLCOLORS + lightlev);
                    } else if (ln.backsector.floorheight
                            != ln.frontsector.floorheight) {
                        amDrawMline(l, FDWALLCOLORS + lightlev);
                    } else if (ln.backsector.ceilingheight
                            != ln.frontsector.ceilingheight) {
                        amDrawMline(l, CDWALLCOLORS + lightlev);
                    } else if (cheating != 0) {
                        amDrawMline(l, TSWALLCOLORS + lightlev);
                    }
                }
            } else if (plr.powers[Doomdef.pw_allmap] != 0) {
                if ((ln.flags & LINE_NEVERSEE) == 0) {
                    amDrawMline(l, GRAYS + 3);
                }
            }
        }
    }

    //
    // AM_rotate
    // am_map.c lines 1173–1189. 2-D rotation of (x,y) by angle. The
    // C takes pointer-to-fixed parameters; the Java port uses a
    // 2-element array as the in/out vector.
    public static void amRotate(int[] xy, int a) {
        int idx = (a >>> Tables.ANGLETOFINESHIFT);
        int tmpx = Fixed.mul(xy[0], Tables.finecosine(idx))
                 - Fixed.mul(xy[1], Tables.finesine[idx]);
        xy[1] = Fixed.mul(xy[0], Tables.finesine[idx])
              + Fixed.mul(xy[1], Tables.finecosine(idx));
        xy[0] = tmpx;
    }

    //
    // AM_drawLineCharacter
    // am_map.c lines 1192–1238.
    public static void amDrawLineCharacter(Mline[] lineguy, int scale,
                                           int angle, int color,
                                           int x, int y) {
        int[] vecA = new int[2];
        int[] vecB = new int[2];
        Mline l = new Mline();
        for (int i = 0; i < lineguy.length; i++) {
            vecA[0] = lineguy[i].a.x;
            vecA[1] = lineguy[i].a.y;
            if (scale != 0) {
                vecA[0] = Fixed.mul(scale, vecA[0]);
                vecA[1] = Fixed.mul(scale, vecA[1]);
            }
            if (angle != 0) amRotate(vecA, angle);
            l.a.x = vecA[0] + x;
            l.a.y = vecA[1] + y;

            vecB[0] = lineguy[i].b.x;
            vecB[1] = lineguy[i].b.y;
            if (scale != 0) {
                vecB[0] = Fixed.mul(scale, vecB[0]);
                vecB[1] = Fixed.mul(scale, vecB[1]);
            }
            if (angle != 0) amRotate(vecB, angle);
            l.b.x = vecB[0] + x;
            l.b.y = vecB[1] + y;

            amDrawMline(l, color);
        }
    }

    //
    // AM_drawPlayers
    // am_map.c lines 1240–1282.
    public static void amDrawPlayers() {
        if (!Doomstat.netgame) {
            if (cheating != 0) {
                amDrawLineCharacter(cheat_player_arrow, 0,
                        plr.mo.angle, WHITE, plr.mo.x, plr.mo.y);
            } else {
                amDrawLineCharacter(player_arrow, 0,
                        plr.mo.angle, WHITE, plr.mo.x, plr.mo.y);
            }
            return;
        }
        // Multiplayer roster (single-player port keeps the branch
        // here as a faithful reference — netgame is always false).
        int[] their_colors = { GREENS, GRAYS, BROWNS, REDS };
        int their_color = -1;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            their_color++;
            Player p = GGame.players[i];
            if (Doomstat.deathmatch != 0 && p != plr) continue;
            if (!GGame.playeringame[i]) continue;
            int color = p.powers[Doomdef.pw_invisibility] != 0
                    ? 246 : their_colors[their_color];
            amDrawLineCharacter(player_arrow, 0, p.mo.angle,
                    color, p.mo.x, p.mo.y);
        }
    }

    //
    // AM_drawThings
    // am_map.c lines 1285–1303.
    public static void amDrawThings(int colors, int colorrange) {
        for (int i = 0; i < PSetup.numsectors; i++) {
            Mobj t = PSetup.sectors[i].thinglist;
            while (t != null) {
                amDrawLineCharacter(thintriangle_guy, 16 << Fixed.FRACBITS,
                        t.angle, colors + lightlev, t.x, t.y);
                t = t.snext;
            }
        }
    }

    //
    // AM_drawMarks
    // am_map.c lines 1305–1324.
    public static void amDrawMarks() {
        for (int i = 0; i < AM_NUMMARKPOINTS; i++) {
            if (markpoints[i].x != -1 && marknums[i] != null) {
                int w = 5;  // C uses literals here too — see comment
                int h = 6;
                int fx = cxmtof(markpoints[i].x);
                int fy = cymtof(markpoints[i].y);
                if (fx >= f_x && fx <= f_w - w
                        && fy >= f_y && fy <= f_h - h) {
                    VVideo.vDrawPatch(fx, fy, FB, marknums[i]);
                }
            }
        }
    }

    //
    // AM_drawCrosshair
    // am_map.c lines 1326–1330. Single-pixel dot in the middle.
    public static void amDrawCrosshair(int color) {
        fb[(f_w * (f_h + 1)) / 2] = (byte) color;
    }

    //
    // AM_Drawer
    // am_map.c lines 1332–1349.
    public static void amDrawer() {
        if (!automapactive) return;
        amClearFB(BACKGROUND);
        if (grid != 0) amDrawGrid(GRIDCOLORS);
        amDrawWalls();
        amDrawPlayers();
        if (cheating == 2) amDrawThings(THINGCOLORS, THINGRANGE);
        amDrawCrosshair(XHAIRCOLORS);
        amDrawMarks();
        VVideo.vMarkRect(f_x, f_y, f_w, f_h);
    }

    // Silence unused-import warning from `ISystem` — referenced via
    // exceptions in the future map-load path.
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_ISYSTEM = ISystem.class;
}
