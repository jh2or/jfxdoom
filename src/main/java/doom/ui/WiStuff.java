// Port of linuxdoom-1.10/wi_stuff.c — single-player intermission.
//
// Bridges level completion → next level via:
//   1. Stat-count phase: kills / items / secrets %, time vs par
//      counters tick up from 0 to the level totals, with the
//      barexp/pistol sound effects firing on transitions.
//   2. ShowNextLoc phase (Doom 1 only): "Entering …" + the world
//      map with splat dots on cleared levels and a flashing "You
//      Are Here" pointer on the next destination.
//   3. NoState phase: idle countdown, then call G_WorldDone.
//
// The Doom 2 path skips ShowNextLoc and goes straight from
// StatCount → NoState → G_WorldDone (no world map).
//
// Deferred from the C: WI_initDeathmatchStats /
// WI_updateDeathmatchStats / WI_drawDeathmatchStats /
// WI_initNetgameStats / WI_updateNetgameStats /
// WI_drawNetgameStats — the multiplayer stat tables. Single-player
// Java port only uses the WI_initStats / WI_updateStats /
// WI_drawStats branch.
package doom.ui;

import doom.play.MRandom;
import doom.play.Player;
import doom.render.Patch;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class WiStuff {

    // wi_stuff.h lines 31–37 — stateenum_t.
    public static final int NoState     = -1;
    public static final int StatCount   = 0;
    public static final int ShowNextLoc = 1;

    // wi_stuff.c lines 66–67.
    public static final int NUMEPISODES = 4;
    public static final int NUMMAPS     = 9;

    // wi_stuff.c lines 80–109 — pixel layout.
    public static final int WI_TITLEY  = 2;
    public static final int WI_SPACINGY = 33;
    public static final int SP_STATSX  = 50;
    public static final int SP_STATSY  = 50;
    public static final int SP_TIMEX   = 16;
    public static final int SP_TIMEY   = Doomdef.SCREENHEIGHT - 32;

    // wi_stuff.c line 285 — destination screen for V_DrawPatch calls.
    public static final int FB = 0;

    // wi_stuff.c line 299 — pause in seconds at the "Entering …" hold
    // before the world map dismisses itself.
    public static final int SHOWNEXTLOCDELAY = 4;

    // wi_stuff.c lines 114–120 — animenum_t.
    public static final int ANIM_ALWAYS = 0;
    public static final int ANIM_RANDOM = 1;
    public static final int ANIM_LEVEL  = 2;

    // wi_stuff.c lines 122–127 — point_t.
    public static final class Point {
        public final int x;
        public final int y;
        public Point(int x, int y) { this.x = x; this.y = y; }
    }

    // wi_stuff.c lines 134–174 — anim_t.
    public static final class Anim {
        public final int type;
        public final int period;       // tics between frames
        public final int nanims;       // frame count
        public final Point loc;
        public final int data1;        // ANIM_RANDOM: deviation; ANIM_LEVEL: trigger level
        public final int data2;        // ANIM_RANDOM: base period
        public final Patch[] p = new Patch[3];

        // mutable per-tick state
        public int nexttic;
        public int lastdrawn;
        public int ctr;
        public int state;

        public Anim(int type, int period, int nanims, Point loc) {
            this(type, period, nanims, loc, 0, 0);
        }

        public Anim(int type, int period, int nanims, Point loc,
                    int data1) {
            this(type, period, nanims, loc, data1, 0);
        }

        public Anim(int type, int period, int nanims, Point loc,
                    int data1, int data2) {
            this.type   = type;
            this.period = period;
            this.nanims = nanims;
            this.loc    = loc;
            this.data1  = data1;
            this.data2  = data2;
        }
    }

    // wi_stuff.c lines 177–218 — world-map level node locations.
    public static final Point[][] lnodes = {
            { new Point(185, 164), new Point(148, 143), new Point(69, 122),
              new Point(209, 102), new Point(116, 89),  new Point(166, 55),
              new Point(71, 56),   new Point(135, 29),  new Point(71, 24)  },
            { new Point(254, 25),  new Point(97, 50),   new Point(188, 64),
              new Point(128, 78),  new Point(214, 92),  new Point(133, 130),
              new Point(208, 136), new Point(148, 140), new Point(235, 158) },
            { new Point(156, 168), new Point(48, 154),  new Point(174, 95),
              new Point(265, 75),  new Point(130, 48),  new Point(279, 23),
              new Point(198, 48),  new Point(140, 25),  new Point(281, 136) },
    };

    // wi_stuff.c lines 226–238 — per-episode animation tables.
    public static final Anim[] epsd0animinfo = {
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(224, 104)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(184, 160)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(112, 136)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 72, 112)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 88,  96)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 64,  48)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(192,  40)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(136,  16)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 80,  16)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 64,  24)),
    };

    public static final Anim[] epsd1animinfo = {
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 1),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 2),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 3),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 4),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 5),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 6),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 7),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 3, new Point(192, 144), 8),
            new Anim(ANIM_LEVEL, Doomdef.TICRATE / 3, 1, new Point(128, 136), 8),
    };

    public static final Anim[] epsd2animinfo = {
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(104, 168)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point( 40, 136)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(160,  96)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(104,  80)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 3, 3, new Point(120,  32)),
            new Anim(ANIM_ALWAYS, Doomdef.TICRATE / 4, 3, new Point( 40,   0)),
    };

    public static final Anim[][] anims = { epsd0animinfo, epsd1animinfo, epsd2animinfo };

    //
    // wi_stuff.c lines 304–393 — module state.
    //
    public static int acceleratestage;
    public static int me;
    public static int state;
    public static Wbstartstruct wbs;
    public static Wbstartstruct.WbPlayer[] plrs;
    public static int cnt;
    public static int bcnt;
    public static int firstrefresh;
    public static int sp_state;          // wi_stuff.c line 1316 — single-player counter
    public static final int[] cnt_kills  = new int[Doomdef.MAXPLAYERS];
    public static final int[] cnt_items  = new int[Doomdef.MAXPLAYERS];
    public static final int[] cnt_secret = new int[Doomdef.MAXPLAYERS];
    public static int cnt_time;
    public static int cnt_par;
    public static int cnt_pause;
    public static int NUMCMAPS;
    public static boolean snl_pointeron;

    // Patch caches loaded by wiLoadData.
    public static Patch bg;
    public static final Patch[] yah     = new Patch[2];
    public static Patch splat;
    public static Patch percent;
    public static Patch colon;
    public static final Patch[] num     = new Patch[10];
    public static Patch wiminus;
    public static Patch finished;
    public static Patch entering;
    public static Patch sp_secret;
    public static Patch kills;
    public static Patch secret;
    public static Patch items;
    public static Patch frags;
    public static Patch time;
    public static Patch par;
    public static Patch sucks;
    public static Patch killers;
    public static Patch victims;
    public static Patch total;
    public static Patch[] lnames;

    private WiStuff() {
    }

    //
    // WI_slamBackground
    // wi_stuff.c lines 406–410. Blits screens[1] (held intermission
    // background) onto screens[0] (live framebuffer).
    public static void wiSlamBackground() {
        System.arraycopy(VVideo.screens[1], 0, VVideo.screens[0], 0,
                Doomdef.SCREENWIDTH * Doomdef.SCREENHEIGHT);
        VVideo.vMarkRect(0, 0, Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT);
    }

    //
    // WI_Responder
    // wi_stuff.c lines 414–417. Always false; the ticker reads input
    // via WI_checkForAccelerate.
    public static boolean wiResponder(DEvent ev) {
        return false;
    }

    //
    // WI_drawLF
    // wi_stuff.c lines 421–434. "<Levelname> Finished!" header.
    public static void wiDrawLF() {
        int y = WI_TITLEY;
        Patch ln = lnames[wbs.last];
        VVideo.vDrawPatch((Doomdef.SCREENWIDTH - ln.width) / 2, y, FB, ln);
        y += (5 * ln.height) / 4;
        VVideo.vDrawPatch((Doomdef.SCREENWIDTH - finished.width) / 2, y, FB,
                finished);
    }

    //
    // WI_drawEL
    // wi_stuff.c lines 439–453. "Entering <Levelname>" header.
    public static void wiDrawEL() {
        int y = WI_TITLEY;
        VVideo.vDrawPatch((Doomdef.SCREENWIDTH - entering.width) / 2, y, FB,
                entering);
        Patch ln = lnames[wbs.next];
        y += (5 * ln.height) / 4;
        VVideo.vDrawPatch((Doomdef.SCREENWIDTH - ln.width) / 2, y, FB, ln);
    }

    //
    // WI_drawOnLnode
    // wi_stuff.c lines 455–499. Tries a patch at the world-map node;
    // c[] is a length-2 fallback list and the routine picks the first
    // one that fits inside the screen rect.
    public static void wiDrawOnLnode(int n, Patch[] c) {
        boolean fits = false;
        int i = 0;
        do {
            int left   = lnodes[wbs.epsd][n].x - c[i].leftoffset;
            int top    = lnodes[wbs.epsd][n].y - c[i].topoffset;
            int right  = left + c[i].width;
            int bottom = top + c[i].height;

            if (left >= 0 && right < Doomdef.SCREENWIDTH
                    && top >= 0 && bottom < Doomdef.SCREENHEIGHT) {
                fits = true;
            } else {
                i++;
            }
        } while (!fits && i != 2);

        if (fits && i < 2) {
            VVideo.vDrawPatch(lnodes[wbs.epsd][n].x, lnodes[wbs.epsd][n].y,
                    FB, c[i]);
        }
        // C also has a stderr printf on overflow; we drop it (no
        // headless logger in the port).
    }

    //
    // WI_initAnimatedBack / updateAnimatedBack / drawAnimatedBack
    // wi_stuff.c lines 503–602. World-map background animations
    // (Doom 1 only). Each anim_t has a per-episode pixel position
    // and a 1-3 frame patch cycle; the type field decides between
    // looping (ALWAYS), random-delayed (RANDOM), and level-gated
    // (LEVEL) animation.
    public static void wiInitAnimatedBack() {
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) return;
        if (wbs.epsd > 2) return;

        for (int i = 0; i < anims[wbs.epsd].length; i++) {
            Anim a = anims[wbs.epsd][i];
            a.ctr = -1;
            if (a.type == ANIM_ALWAYS) {
                a.nexttic = bcnt + 1 + (MRandom.mRandom() % a.period);
            } else if (a.type == ANIM_RANDOM) {
                a.nexttic = bcnt + 1 + a.data2 + (MRandom.mRandom() % a.data1);
            } else if (a.type == ANIM_LEVEL) {
                a.nexttic = bcnt + 1;
            }
        }
    }

    public static void wiUpdateAnimatedBack() {
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) return;
        if (wbs.epsd > 2) return;

        for (int i = 0; i < anims[wbs.epsd].length; i++) {
            Anim a = anims[wbs.epsd][i];
            if (bcnt == a.nexttic) {
                switch (a.type) {
                    case ANIM_ALWAYS:
                        if (++a.ctr >= a.nanims) a.ctr = 0;
                        a.nexttic = bcnt + a.period;
                        break;
                    case ANIM_RANDOM:
                        a.ctr++;
                        if (a.ctr == a.nanims) {
                            a.ctr = -1;
                            a.nexttic = bcnt + a.data2
                                    + (MRandom.mRandom() % a.data1);
                        } else {
                            a.nexttic = bcnt + a.period;
                        }
                        break;
                    case ANIM_LEVEL:
                        // gawd-awful hack for level anims
                        if (!(state == StatCount && i == 7)
                                && wbs.next == a.data1) {
                            a.ctr++;
                            if (a.ctr == a.nanims) a.ctr--;
                            a.nexttic = bcnt + a.period;
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    public static void wiDrawAnimatedBack() {
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) return;
        if (wbs.epsd > 2) return;

        for (int i = 0; i < anims[wbs.epsd].length; i++) {
            Anim a = anims[wbs.epsd][i];
            if (a.ctr >= 0 && a.p[a.ctr] != null) {
                VVideo.vDrawPatch(a.loc.x, a.loc.y, FB, a.p[a.ctr]);
            }
        }
    }

    //
    // WI_drawNum
    // wi_stuff.c lines 611–666. Draws an integer right-aligned at x;
    // digits=-1 means use the minimum required. Returns the new x.
    public static int wiDrawNum(int x, int y, int n, int digits) {
        int fontwidth = num[0].width;
        boolean neg;
        int temp;

        if (digits < 0) {
            if (n == 0) {
                digits = 1;
            } else {
                digits = 0;
                temp = n;
                while (temp != 0) {
                    temp /= 10;
                    digits++;
                }
            }
        }

        neg = n < 0;
        if (neg) n = -n;

        if (n == 1994) return 0;

        while (digits-- != 0) {
            x -= fontwidth;
            VVideo.vDrawPatch(x, y, FB, num[n % 10]);
            n /= 10;
        }

        if (neg) {
            x -= 8;
            VVideo.vDrawPatch(x, y, FB, wiminus);
        }

        return x;
    }

    //
    // WI_drawPercent
    // wi_stuff.c lines 668–679.
    public static void wiDrawPercent(int x, int y, int p) {
        if (p < 0) return;
        VVideo.vDrawPatch(x, y, FB, percent);
        wiDrawNum(x, y, p, -1);
    }

    //
    // WI_drawTime
    // wi_stuff.c lines 687–721. Splits seconds into HH:MM:SS-ish
    // groups divided by 60 — the loop iterates until `t/div == 0`,
    // printing each two-digit block with a colon separator.
    public static void wiDrawTime(int x, int y, int t) {
        if (t < 0) return;

        if (t <= 61 * 59) {
            int div = 1;
            do {
                int n = (t / div) % 60;
                x = wiDrawNum(x, y, n, 2) - colon.width;
                div *= 60;
                if (div == 60 || t / div != 0) {
                    VVideo.vDrawPatch(x, y, FB, colon);
                }
            } while (t / div != 0);
        } else {
            // "sucks"
            VVideo.vDrawPatch(x - sucks.width, y, FB, sucks);
        }
    }

    //
    // WI_End
    // wi_stuff.c lines 724–728.
    public static void wiEnd() {
        wiUnloadData();
    }

    //
    // WI_initNoState / WI_updateNoState
    // wi_stuff.c lines 730–747.
    public static void wiInitNoState() {
        state = NoState;
        acceleratestage = 0;
        cnt = 10;
    }

    public static void wiUpdateNoState() {
        wiUpdateAnimatedBack();
        if (--cnt == 0) {
            wiEnd();
            // G_WorldDone — sets gameaction=ga_worlddone, which
            // GGame.gTicker reads to flip back to GS_LEVEL +
            // ga_loadlevel for the next map.
            GGame.gameaction = GGame.ga_worlddone;
        }
    }

    //
    // WI_initShowNextLoc / WI_updateShowNextLoc / WI_drawShowNextLoc
    // wi_stuff.c lines 752–810.
    public static void wiInitShowNextLoc() {
        state = ShowNextLoc;
        acceleratestage = 0;
        cnt = SHOWNEXTLOCDELAY * Doomdef.TICRATE;
        wiInitAnimatedBack();
    }

    public static void wiUpdateShowNextLoc() {
        wiUpdateAnimatedBack();
        if (--cnt == 0 || acceleratestage != 0) {
            wiInitNoState();
        } else {
            snl_pointeron = (cnt & 31) < 20;
        }
    }

    public static void wiDrawShowNextLoc() {
        wiSlamBackground();
        wiDrawAnimatedBack();

        if (Doomstat.gamemode != Doomstat.GameMode.commercial) {
            if (wbs.epsd > 2) {
                wiDrawEL();
                return;
            }
            int last = (wbs.last == 8) ? wbs.next - 1 : wbs.last;
            for (int i = 0; i <= last; i++) {
                wiDrawOnLnode(i, new Patch[] { splat, splat });
            }
            if (wbs.didsecret) {
                wiDrawOnLnode(8, new Patch[] { splat, splat });
            }
            if (snl_pointeron) {
                wiDrawOnLnode(wbs.next, yah);
            }
        }

        if (Doomstat.gamemode != Doomstat.GameMode.commercial
                || wbs.next != 30) {
            wiDrawEL();
        }
    }

    public static void wiDrawNoState() {
        snl_pointeron = true;
        wiDrawShowNextLoc();
    }

    //
    // WI_initStats / WI_updateStats / WI_drawStats
    // wi_stuff.c lines 1318–1468. Single-player level summary.
    public static void wiInitStats() {
        state = StatCount;
        acceleratestage = 0;
        sp_state = 1;
        cnt_kills[0] = cnt_items[0] = cnt_secret[0] = -1;
        cnt_time = cnt_par = -1;
        cnt_pause = Doomdef.TICRATE;
        wiInitAnimatedBack();
    }

    public static void wiUpdateStats() {
        wiUpdateAnimatedBack();

        if (acceleratestage != 0 && sp_state != 10) {
            acceleratestage = 0;
            cnt_kills[0]  = (plrs[me].skills  * 100) / wbs.maxkills;
            cnt_items[0]  = (plrs[me].sitems  * 100) / wbs.maxitems;
            cnt_secret[0] = (plrs[me].ssecret * 100) / wbs.maxsecret;
            cnt_time      = plrs[me].stime / Doomdef.TICRATE;
            cnt_par       = wbs.partime / Doomdef.TICRATE;
            SSound.sStartSound(null, Sounds.sfx_barexp);
            sp_state = 10;
        }

        if (sp_state == 2) {
            cnt_kills[0] += 2;
            if ((bcnt & 3) == 0) SSound.sStartSound(null, Sounds.sfx_pistol);
            if (cnt_kills[0] >= (plrs[me].skills * 100) / wbs.maxkills) {
                cnt_kills[0] = (plrs[me].skills * 100) / wbs.maxkills;
                SSound.sStartSound(null, Sounds.sfx_barexp);
                sp_state++;
            }
        } else if (sp_state == 4) {
            cnt_items[0] += 2;
            if ((bcnt & 3) == 0) SSound.sStartSound(null, Sounds.sfx_pistol);
            if (cnt_items[0] >= (plrs[me].sitems * 100) / wbs.maxitems) {
                cnt_items[0] = (plrs[me].sitems * 100) / wbs.maxitems;
                SSound.sStartSound(null, Sounds.sfx_barexp);
                sp_state++;
            }
        } else if (sp_state == 6) {
            cnt_secret[0] += 2;
            if ((bcnt & 3) == 0) SSound.sStartSound(null, Sounds.sfx_pistol);
            if (cnt_secret[0] >= (plrs[me].ssecret * 100) / wbs.maxsecret) {
                cnt_secret[0] = (plrs[me].ssecret * 100) / wbs.maxsecret;
                SSound.sStartSound(null, Sounds.sfx_barexp);
                sp_state++;
            }
        } else if (sp_state == 8) {
            if ((bcnt & 3) == 0) SSound.sStartSound(null, Sounds.sfx_pistol);
            cnt_time += 3;
            if (cnt_time >= plrs[me].stime / Doomdef.TICRATE) {
                cnt_time = plrs[me].stime / Doomdef.TICRATE;
            }
            cnt_par += 3;
            if (cnt_par >= wbs.partime / Doomdef.TICRATE) {
                cnt_par = wbs.partime / Doomdef.TICRATE;
                if (cnt_time >= plrs[me].stime / Doomdef.TICRATE) {
                    SSound.sStartSound(null, Sounds.sfx_barexp);
                    sp_state++;
                }
            }
        } else if (sp_state == 10) {
            if (acceleratestage != 0) {
                SSound.sStartSound(null, Sounds.sfx_sgcock);
                if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                    wiInitNoState();
                } else {
                    wiInitShowNextLoc();
                }
            }
        } else if ((sp_state & 1) != 0) {
            if (--cnt_pause == 0) {
                sp_state++;
                cnt_pause = Doomdef.TICRATE;
            }
        }
    }

    public static void wiDrawStats() {
        int lh = (3 * num[0].height) / 2;

        wiSlamBackground();
        wiDrawAnimatedBack();
        wiDrawLF();

        VVideo.vDrawPatch(SP_STATSX, SP_STATSY, FB, kills);
        wiDrawPercent(Doomdef.SCREENWIDTH - SP_STATSX, SP_STATSY, cnt_kills[0]);

        VVideo.vDrawPatch(SP_STATSX, SP_STATSY + lh, FB, items);
        wiDrawPercent(Doomdef.SCREENWIDTH - SP_STATSX, SP_STATSY + lh, cnt_items[0]);

        VVideo.vDrawPatch(SP_STATSX, SP_STATSY + 2 * lh, FB, sp_secret);
        wiDrawPercent(Doomdef.SCREENWIDTH - SP_STATSX, SP_STATSY + 2 * lh,
                cnt_secret[0]);

        VVideo.vDrawPatch(SP_TIMEX, SP_TIMEY, FB, time);
        wiDrawTime(Doomdef.SCREENWIDTH / 2 - SP_TIMEX, SP_TIMEY, cnt_time);

        if (wbs.epsd < 3) {
            VVideo.vDrawPatch(Doomdef.SCREENWIDTH / 2 + SP_TIMEX, SP_TIMEY,
                    FB, par);
            wiDrawTime(Doomdef.SCREENWIDTH - SP_TIMEX, SP_TIMEY, cnt_par);
        }
    }

    //
    // WI_checkForAccelerate
    // wi_stuff.c lines 1470–1498. Reads each player's BT_ATTACK /
    // BT_USE presses (edge-triggered) so the intermission skips
    // ahead.
    public static void wiCheckForAccelerate() {
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            Player p = GGame.players[i];
            if (GGame.playeringame[i]) {
                if ((p.cmd.buttons & Doomdef.BT_ATTACK) != 0) {
                    if (p.attackdown == 0) acceleratestage = 1;
                    p.attackdown = 1;
                } else {
                    p.attackdown = 0;
                }
                if ((p.cmd.buttons & Doomdef.BT_USE) != 0) {
                    if (p.usedown == 0) acceleratestage = 1;
                    p.usedown = 1;
                } else {
                    p.usedown = 0;
                }
            }
        }
    }

    //
    // WI_Ticker
    // wi_stuff.c lines 1503–1536.
    public static void wiTicker() {
        bcnt++;

        if (bcnt == 1) {
            if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                SSound.sChangeMusic(Sounds.mus_dm2int, 1);
            } else {
                SSound.sChangeMusic(Sounds.mus_inter, 1);
            }
        }

        wiCheckForAccelerate();

        switch (state) {
            case StatCount:
                // Deathmatch / netgame variants deferred; single-
                // player only path.
                wiUpdateStats();
                break;
            case ShowNextLoc:
                wiUpdateShowNextLoc();
                break;
            case NoState:
                wiUpdateNoState();
                break;
            default:
                break;
        }
    }

    //
    // WI_loadData
    // wi_stuff.c lines 1538–1706. Loads every patch the intermission
    // can reference for the current episode.
    public static void wiLoadData() {
        String name;
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            name = "INTERPIC";
        } else {
            name = "WIMAP" + wbs.epsd;
        }
        if (Doomstat.gamemode == Doomstat.GameMode.retail && wbs.epsd == 3) {
            name = "INTERPIC";
        }

        try {
            bg = Patch.fromLump(WWad.wCacheLumpName(name, ZZone.PU_CACHE));
            // C uses V_DrawPatch into screens[1] to stamp the
            // intermission background, which slamBackground later
            // memcpys into screens[0] each frame.
            VVideo.vDrawPatch(0, 0, 1, bg);

            if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                NUMCMAPS = 32;
                lnames = new Patch[NUMCMAPS];
                for (int i = 0; i < NUMCMAPS; i++) {
                    String n = String.format("CWILV%02d", i);
                    lnames[i] = Patch.fromLump(WWad.wCacheLumpName(n, ZZone.PU_STATIC));
                }
            } else {
                lnames = new Patch[NUMMAPS];
                for (int i = 0; i < NUMMAPS; i++) {
                    lnames[i] = Patch.fromLump(WWad.wCacheLumpName(
                            "WILV" + wbs.epsd + i, ZZone.PU_STATIC));
                }
                yah[0] = Patch.fromLump(WWad.wCacheLumpName("WIURH0", ZZone.PU_STATIC));
                yah[1] = Patch.fromLump(WWad.wCacheLumpName("WIURH1", ZZone.PU_STATIC));
                splat  = Patch.fromLump(WWad.wCacheLumpName("WISPLAT", ZZone.PU_STATIC));

                if (wbs.epsd < 3) {
                    for (int j = 0; j < anims[wbs.epsd].length; j++) {
                        Anim a = anims[wbs.epsd][j];
                        for (int i = 0; i < a.nanims; i++) {
                            if (wbs.epsd != 1 || j != 8) {
                                String n = String.format("WIA%d%02d%02d",
                                        wbs.epsd, j, i);
                                a.p[i] = Patch.fromLump(WWad.wCacheLumpName(
                                        n, ZZone.PU_STATIC));
                            } else {
                                // HACK ALERT! Reuse the index-4 anim's
                                // patches for the index-8 hidden anim
                                // (a faithful translation of the
                                // C source's "MONDO HACK!" comment).
                                a.p[i] = anims[1][4].p[i];
                            }
                        }
                    }
                }
            }

            wiminus    = Patch.fromLump(WWad.wCacheLumpName("WIMINUS",  ZZone.PU_STATIC));
            for (int i = 0; i < 10; i++) {
                num[i] = Patch.fromLump(WWad.wCacheLumpName("WINUM" + i, ZZone.PU_STATIC));
            }
            percent    = Patch.fromLump(WWad.wCacheLumpName("WIPCNT",   ZZone.PU_STATIC));
            finished   = Patch.fromLump(WWad.wCacheLumpName("WIF",      ZZone.PU_STATIC));
            entering   = Patch.fromLump(WWad.wCacheLumpName("WIENTER",  ZZone.PU_STATIC));
            kills      = Patch.fromLump(WWad.wCacheLumpName("WIOSTK",   ZZone.PU_STATIC));
            secret     = Patch.fromLump(WWad.wCacheLumpName("WIOSTS",   ZZone.PU_STATIC));
            sp_secret  = Patch.fromLump(WWad.wCacheLumpName("WISCRT2",  ZZone.PU_STATIC));
            items      = Patch.fromLump(WWad.wCacheLumpName("WIOSTI",   ZZone.PU_STATIC));
            frags      = Patch.fromLump(WWad.wCacheLumpName("WIFRGS",   ZZone.PU_STATIC));
            colon      = Patch.fromLump(WWad.wCacheLumpName("WICOLON",  ZZone.PU_STATIC));
            time       = Patch.fromLump(WWad.wCacheLumpName("WITIME",   ZZone.PU_STATIC));
            sucks      = Patch.fromLump(WWad.wCacheLumpName("WISUCKS",  ZZone.PU_STATIC));
            par        = Patch.fromLump(WWad.wCacheLumpName("WIPAR",    ZZone.PU_STATIC));
            killers    = Patch.fromLump(WWad.wCacheLumpName("WIKILRS",  ZZone.PU_STATIC));
            victims    = Patch.fromLump(WWad.wCacheLumpName("WIVCTMS",  ZZone.PU_STATIC));
            total      = Patch.fromLump(WWad.wCacheLumpName("WIMSTT",   ZZone.PU_STATIC));
            // STFST01 / STFDEAD0 / STPBn / WIBPn drop until netgame
            // stats land (single-player Java port doesn't paint them).
        } catch (IOException e) {
            throw ISystem.iError("WI: lump load failed: %s", e.getMessage());
        }
    }

    //
    // WI_unloadData
    // wi_stuff.c lines 1708–1770. Java has no ZZone-level "release
    // patches" call needed by the GC; the cache stays warm until
    // overwritten by the next load.
    public static void wiUnloadData() {
        // Intentionally empty — the patches stay referenced by their
        // module-static fields; GC + ZZone tag-flip not modelled.
    }

    //
    // WI_Drawer
    // wi_stuff.c lines 1772–1793.
    public static void wiDrawer() {
        switch (state) {
            case StatCount:
                wiDrawStats();
                break;
            case ShowNextLoc:
                wiDrawShowNextLoc();
                break;
            case NoState:
                wiDrawNoState();
                break;
            default:
                break;
        }
    }

    //
    // WI_initVariables
    // wi_stuff.c lines 1796–1836.
    public static void wiInitVariables(Wbstartstruct wbstartstruct) {
        wbs = wbstartstruct;

        acceleratestage = 0;
        cnt = bcnt = 0;
        firstrefresh = 1;
        me = wbs.pnum;
        plrs = wbs.plyr;

        if (wbs.maxkills  == 0) wbs.maxkills  = 1;
        if (wbs.maxitems  == 0) wbs.maxitems  = 1;
        if (wbs.maxsecret == 0) wbs.maxsecret = 1;

        if (Doomstat.gamemode != Doomstat.GameMode.retail
                && wbs.epsd > 2) {
            wbs.epsd -= 3;
        }
    }

    //
    // WI_Start
    // wi_stuff.c lines 1838–1850. The Java port wraps wiLoadData in a
    // try/catch — playsim unit tests call gDoCompleted without booting
    // the engine and would otherwise fail loading WIMAPn / CWILVnn.
    // On WAD-load failure the intermission degrades into an immediate
    // ga_worlddone transition, mirroring the same "graphics not
    // loaded" guards in huStart / stStart (deviation 34).
    public static void wiStart(Wbstartstruct wbstartstruct) {
        wiInitVariables(wbstartstruct);
        try {
            wiLoadData();
        } catch (ISystem.DoomError e) {
            // No WAD context (test harness) — skip the intermission
            // entirely so the engine can still advance levels. Also
            // flip gamestate back so DMain.dDisplay doesn't try to
            // call wiDrawer against unloaded patches.
            GGame.gameaction   = GGame.ga_worlddone;
            Doomstat.gamestate = Doomstat.GS_LEVEL;
            return;
        }
        wiInitStats();
    }
}
