// Port of linuxdoom-1.10/f_finale.c — full end-of-game finale code.
//
// Drives the post-victory sequences:
//   - per-episode text screen (Doom 1: E1/E2/E3/E4; Doom 2: between
//     specific maps + at end) — typed-on-screen one char every
//     TEXTSPEED tics, then sits on the static art for a beat
//   - episode-1 retail credit card OR HELP2 screen
//   - episode-2 VICTORY2 patch
//   - episode-3 BunnyScroll (PFUB1/PFUB2 horizontal scroll + END0..6
//     "The End" stamp)
//   - episode-4 ENDPIC patch
//   - Doom 2 cast call: one monster at a time on a BOSSBACK patch,
//     cycling through see → walk frames → attack → death,
//     terminated by a key press; F_CastResponder forces the death
//     state and the cycle advances to the next caster
//
// Sits between G_DoCompleted (sets gameaction=ga_victory) and the
// title-screen demo loop. F_Drawer paints into screens[0] each
// frame; D_Display calls it when gamestate==GS_FINALE.
package doom.ui;

import doom.play.DItems;
import doom.play.Info;
import doom.play.MobjInfo;
import doom.play.Player;
import doom.play.State;
import doom.render.Patch;
import doom.render.RData;
import doom.render.Spritedef;
import doom.render.Spriteframe;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.Dstrings;
import doom.system.GGame;
import doom.system.ISystem;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class FFinale {

    // f_finale.c lines 56–57.
    public static final int TEXTSPEED = 3;
    public static final int TEXTWAIT  = 250;

    // f_finale.c line 52 — stage tracker.
    //   0 = text typing
    //   1 = static art screen
    //   2 = character cast (Doom 2 finale)
    public static int finalestage;
    public static int finalecount;

    // Active text + tiled-background flat. Pointer-shaped in the C;
    // each F_StartFinale assigns one of the *TEXT constants from
    // Dstrings.
    public static String finaletext;
    public static String finaleflat;

    private FFinale() {
    }

    //
    // F_StartFinale
    // f_finale.c lines 96–191.
    public static void fStartFinale() {
        GGame.gameaction      = GGame.ga_nothing;
        Doomstat.gamestate    = Doomstat.GS_FINALE;
        Doomstat.viewactive   = false;
        AmMap.automapactive   = false;

        // Okay - IWAD dependant stuff.
        switch (Doomstat.gamemode) {
            case shareware:
            case registered:
            case retail: {
                SSound.sChangeMusic(Sounds.mus_victor, 1);
                switch (Doomstat.gameepisode) {
                    case 1:
                        finaleflat = "FLOOR4_8";
                        finaletext = Dstrings.E1TEXT;
                        break;
                    case 2:
                        finaleflat = "SFLR6_1";
                        finaletext = Dstrings.E2TEXT;
                        break;
                    case 3:
                        finaleflat = "MFLR8_4";
                        finaletext = Dstrings.E3TEXT;
                        break;
                    case 4:
                        finaleflat = "MFLR8_3";
                        finaletext = Dstrings.E4TEXT;
                        break;
                    default:
                        // Ouch.
                        break;
                }
                break;
            }

            case commercial: {
                SSound.sChangeMusic(Sounds.mus_read_m, 1);
                switch (Doomstat.gamemap) {
                    case 6:
                        finaleflat = "SLIME16";
                        finaletext = Dstrings.C1TEXT;
                        break;
                    case 11:
                        finaleflat = "RROCK14";
                        finaletext = Dstrings.C2TEXT;
                        break;
                    case 20:
                        finaleflat = "RROCK07";
                        finaletext = Dstrings.C3TEXT;
                        break;
                    case 30:
                        finaleflat = "RROCK17";
                        finaletext = Dstrings.C4TEXT;
                        break;
                    case 15:
                        finaleflat = "RROCK13";
                        finaletext = Dstrings.C5TEXT;
                        break;
                    case 31:
                        finaleflat = "RROCK19";
                        finaletext = Dstrings.C6TEXT;
                        break;
                    default:
                        // Ouch.
                        break;
                }
                break;
            }

            default:
                SSound.sChangeMusic(Sounds.mus_read_m, 1);
                finaleflat = "F_SKY1";
                finaletext = Dstrings.C1TEXT;
                break;
        }

        finalestage = 0;
        finalecount = 0;
    }

    //
    // F_Responder
    // f_finale.c lines 195–201.
    public static boolean fResponder(DEvent ev) {
        if (finalestage == 2) {
            return fCastResponder(ev);
        }
        return false;
    }

    //
    // F_Ticker
    // f_finale.c lines 207–249.
    public static void fTicker() {
        // check for skipping
        if (Doomstat.gamemode == Doomstat.GameMode.commercial
                && finalecount > 50) {
            int i;
            // go on to the next level
            for (i = 0; i < Doomdef.MAXPLAYERS; i++) {
                if (GGame.players[i].cmd.buttons != 0) {
                    break;
                }
            }
            if (i < Doomdef.MAXPLAYERS) {
                if (Doomstat.gamemap == 30) {
                    fStartCast();
                } else {
                    GGame.gameaction = GGame.ga_worlddone;
                }
            }
        }

        // advance animation
        finalecount++;

        if (finalestage == 2) {
            fCastTicker();
            return;
        }

        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            return;
        }

        if (finalestage == 0
                && finaletext != null
                && finalecount > finaletext.length() * TEXTSPEED + TEXTWAIT) {
            finalecount = 0;
            finalestage = 1;
            Doomstat.wipegamestate = -1;  // force a wipe
            if (Doomstat.gameepisode == 3) {
                SSound.sStartMusic(Sounds.mus_bunny);
            }
        }
    }

    //
    // F_TextWrite
    // f_finale.c lines 261–327.
    public static void fTextWrite() {
        byte[] src;
        byte[] dest;

        // erase the entire screen to a tiled background
        try {
            src = WWad.wCacheLumpName(finaleflat, ZZone.PU_CACHE);
        } catch (IOException e) {
            throw ISystem.iError("F_TextWrite: %s lump load failed: %s",
                    finaleflat, e.getMessage());
        }
        dest = VVideo.screens[0];

        int destIdx = 0;
        for (int y = 0; y < Doomdef.SCREENHEIGHT; y++) {
            for (int x = 0; x < Doomdef.SCREENWIDTH / 64; x++) {
                System.arraycopy(src, (y & 63) << 6, dest, destIdx, 64);
                destIdx += 64;
            }
            if ((Doomdef.SCREENWIDTH & 63) != 0) {
                System.arraycopy(src, (y & 63) << 6, dest, destIdx,
                        Doomdef.SCREENWIDTH & 63);
                destIdx += Doomdef.SCREENWIDTH & 63;
            }
        }

        VVideo.vMarkRect(0, 0, Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT);

        // draw some of the text onto the screen
        int cx = 10;
        int cy = 10;
        int chIdx = 0;
        int count = (finalecount - 10) / TEXTSPEED;
        if (count < 0) count = 0;
        for (; count != 0; count--) {
            if (chIdx >= finaletext.length()) break;
            char c = finaletext.charAt(chIdx++);

            if (c == '\n') {
                cx = 10;
                cy += 11;
                continue;
            }

            int idx = Character.toUpperCase(c) - HuStuff.HU_FONTSTART;
            if (idx < 0 || idx > HuStuff.HU_FONTSIZE
                    || HuStuff.huFont[idx] == null) {
                cx += 4;
                continue;
            }

            int w = HuStuff.huFont[idx].width;
            if (cx + w > Doomdef.SCREENWIDTH) break;
            VVideo.vDrawPatch(cx, cy, 0, HuStuff.huFont[idx]);
            cx += w;
        }
    }

    //
    // Cast call (Doom 2 finale).
    // f_finale.c lines 334–360 — castinfo_t order.
    public static final class CastInfo {
        public final String name;
        public final int    type;

        public CastInfo(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    public static final CastInfo[] castorder = new CastInfo[] {
            new CastInfo(Dstrings.CC_ZOMBIE,  Info.MT_POSSESSED),
            new CastInfo(Dstrings.CC_SHOTGUN, Info.MT_SHOTGUY),
            new CastInfo(Dstrings.CC_HEAVY,   Info.MT_CHAINGUY),
            new CastInfo(Dstrings.CC_IMP,     Info.MT_TROOP),
            new CastInfo(Dstrings.CC_DEMON,   Info.MT_SERGEANT),
            new CastInfo(Dstrings.CC_LOST,    Info.MT_SKULL),
            new CastInfo(Dstrings.CC_CACO,    Info.MT_HEAD),
            new CastInfo(Dstrings.CC_HELL,    Info.MT_KNIGHT),
            new CastInfo(Dstrings.CC_BARON,   Info.MT_BRUISER),
            new CastInfo(Dstrings.CC_ARACH,   Info.MT_BABY),
            new CastInfo(Dstrings.CC_PAIN,    Info.MT_PAIN),
            new CastInfo(Dstrings.CC_REVEN,   Info.MT_UNDEAD),
            new CastInfo(Dstrings.CC_MANCU,   Info.MT_FATSO),
            new CastInfo(Dstrings.CC_ARCH,    Info.MT_VILE),
            new CastInfo(Dstrings.CC_SPIDER,  Info.MT_SPIDER),
            new CastInfo(Dstrings.CC_CYBER,   Info.MT_CYBORG),
            new CastInfo(Dstrings.CC_HERO,    Info.MT_PLAYER),
            new CastInfo(null,                0),
    };

    public static int     castnum;
    public static int     casttics;
    public static State   caststate;
    public static boolean castdeath;
    public static int     castframes;
    public static int     castonmelee;
    public static boolean castattacking;

    //
    // F_StartCast
    // f_finale.c lines 377–389.
    public static void fStartCast() {
        Doomstat.wipegamestate = -1;            // force a screen wipe
        castnum = 0;
        MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
        caststate = Info.states[mi.seestate];
        casttics = caststate.tics;
        castdeath = false;
        finalestage = 2;
        castframes = 0;
        castonmelee = 0;
        castattacking = false;
        SSound.sChangeMusic(Sounds.mus_evil, 1);
    }

    //
    // F_CastTicker
    // f_finale.c lines 395–495. The C uses a `goto stopattack` jump
    // out of the inner attack-switch — translated as a labelled break
    // with the post-stopattack reset hoisted into a boolean flag.
    public static void fCastTicker() {
        if (--casttics > 0) {
            return;             // not time to change state yet
        }

        if (caststate.tics == -1 || caststate.nextstate == Info.S_NULL) {
            // switch from deathstate to next monster
            castnum++;
            castdeath = false;
            if (castorder[castnum].name == null) {
                castnum = 0;
            }
            MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
            if (mi.seesound != 0) {
                SSound.sStartSound(null, mi.seesound);
            }
            caststate = Info.states[mi.seestate];
            castframes = 0;
        } else {
            // just advance to next state in animation
            // The C "goto stopattack" out of an attack frame falls
            // through the rest of the function — we model it with a
            // boolean toggle.
            boolean forceStopAttack = (caststate == Info.states[Info.S_PLAY_ATK1]);
            int st = caststate.nextstate;
            caststate = Info.states[st];
            castframes++;

            // sound hacks....
            int sfx;
            switch (st) {
                case Info.S_PLAY_ATK1:  sfx = Sounds.sfx_dshtgn; break;
                case Info.S_POSS_ATK2:  sfx = Sounds.sfx_pistol; break;
                case Info.S_SPOS_ATK2:  sfx = Sounds.sfx_shotgn; break;
                case Info.S_VILE_ATK2:  sfx = Sounds.sfx_vilatk; break;
                case Info.S_SKEL_FIST2: sfx = Sounds.sfx_skeswg; break;
                case Info.S_SKEL_FIST4: sfx = Sounds.sfx_skepch; break;
                case Info.S_SKEL_MISS2: sfx = Sounds.sfx_skeatk; break;
                case Info.S_FATT_ATK8:
                case Info.S_FATT_ATK5:
                case Info.S_FATT_ATK2:  sfx = Sounds.sfx_firsht; break;
                case Info.S_CPOS_ATK2:
                case Info.S_CPOS_ATK3:
                case Info.S_CPOS_ATK4:  sfx = Sounds.sfx_shotgn; break;
                case Info.S_TROO_ATK3:  sfx = Sounds.sfx_claw;   break;
                case Info.S_SARG_ATK2:  sfx = Sounds.sfx_sgtatk; break;
                case Info.S_BOSS_ATK2:
                case Info.S_BOS2_ATK2:
                case Info.S_HEAD_ATK2:  sfx = Sounds.sfx_firsht; break;
                case Info.S_SKULL_ATK2: sfx = Sounds.sfx_sklatk; break;
                case Info.S_SPID_ATK2:
                case Info.S_SPID_ATK3:  sfx = Sounds.sfx_shotgn; break;
                case Info.S_BSPI_ATK2:  sfx = Sounds.sfx_plasma; break;
                case Info.S_CYBER_ATK2:
                case Info.S_CYBER_ATK4:
                case Info.S_CYBER_ATK6: sfx = Sounds.sfx_rlaunc; break;
                case Info.S_PAIN_ATK3:  sfx = Sounds.sfx_sklatk; break;
                default:                sfx = 0; break;
            }
            if (sfx != 0) {
                SSound.sStartSound(null, sfx);
            }

            if (forceStopAttack) {
                stopAttack();
                casttics = caststate.tics;
                if (casttics == -1) casttics = 15;
                return;
            }
        }

        if (castframes == 12) {
            // go into attack frame
            castattacking = true;
            MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
            if (castonmelee != 0) {
                caststate = Info.states[mi.meleestate];
            } else {
                caststate = Info.states[mi.missilestate];
            }
            castonmelee ^= 1;
            if (caststate == Info.states[Info.S_NULL]) {
                if (castonmelee != 0) {
                    caststate = Info.states[mi.meleestate];
                } else {
                    caststate = Info.states[mi.missilestate];
                }
            }
        }

        if (castattacking) {
            MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
            if (castframes == 24
                    || caststate == Info.states[mi.seestate]) {
                stopAttack();
            }
        }

        casttics = caststate.tics;
        if (casttics == -1) {
            casttics = 15;
        }
    }

    // f_finale.c label `stopattack:` factored into a helper so the
    // labelled break translation stays clean.
    private static void stopAttack() {
        castattacking = false;
        castframes = 0;
        MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
        caststate = Info.states[mi.seestate];
    }

    //
    // F_CastResponder
    // f_finale.c lines 502–520.
    public static boolean fCastResponder(DEvent ev) {
        if (ev.type != DEvent.EV_KEYDOWN) {
            return false;
        }
        if (castdeath) {
            return true;  // already in dying frames
        }

        // go into death frame
        castdeath = true;
        MobjInfo mi = Info.mobjinfo[castorder[castnum].type];
        caststate = Info.states[mi.deathstate];
        casttics = caststate.tics;
        castframes = 0;
        castattacking = false;
        if (mi.deathsound != 0) {
            SSound.sStartSound(null, mi.deathsound);
        }
        return true;
    }

    //
    // F_CastPrint
    // f_finale.c lines 523–571.
    public static void fCastPrint(String text) {
        // find width
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int idx = Character.toUpperCase(text.charAt(i)) - HuStuff.HU_FONTSTART;
            if (idx < 0 || idx > HuStuff.HU_FONTSIZE
                    || HuStuff.huFont[idx] == null) {
                width += 4;
                continue;
            }
            width += HuStuff.huFont[idx].width;
        }

        // draw it
        int cx = 160 - width / 2;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int idx = Character.toUpperCase(c) - HuStuff.HU_FONTSTART;
            if (idx < 0 || idx > HuStuff.HU_FONTSIZE
                    || HuStuff.huFont[idx] == null) {
                cx += 4;
                continue;
            }
            int w = HuStuff.huFont[idx].width;
            VVideo.vDrawPatch(cx, 180, 0, HuStuff.huFont[idx]);
            cx += w;
        }
    }

    //
    // F_CastDrawer
    // f_finale.c lines 579–603.
    public static void fCastDrawer() {
        // erase the entire screen to a background
        try {
            VVideo.vDrawPatch(0, 0, 0, Patch.fromLump(
                    WWad.wCacheLumpName("BOSSBACK", ZZone.PU_CACHE)));
        } catch (IOException e) {
            throw ISystem.iError("F_CastDrawer: BOSSBACK load failed: %s",
                    e.getMessage());
        }

        fCastPrint(castorder[castnum].name);

        // draw the current frame in the middle of the screen
        Spritedef   sprdef = doom.render.RThings.sprites[caststate.sprite];
        Spriteframe sprframe = sprdef.spriteframes[caststate.frame & doom.play.Mobj.FF_FRAMEMASK];
        int lump = sprframe.lump[0];
        boolean flip = sprframe.flip[0] != 0;

        Patch patch;
        try {
            patch = Patch.fromLump(WWad.wCacheLumpNum(
                    lump + RData.firstspritelump, ZZone.PU_CACHE));
        } catch (IOException e) {
            throw ISystem.iError("F_CastDrawer: sprite load failed: %s",
                    e.getMessage());
        }
        if (flip) {
            VVideo.vDrawPatchFlipped(160, 170, 0, patch);
        } else {
            VVideo.vDrawPatch(160, 170, 0, patch);
        }
    }

    //
    // F_DrawPatchCol
    // f_finale.c lines 609–638. Paints one column of a patch to a
    // full-height screen column. Used by F_BunnyScroll.
    public static void fDrawPatchCol(int x, Patch patch, int col) {
        byte[] desttop = VVideo.screens[0];
        int destIdx = x;
        byte[] data = patch.data;
        int columnIdx = patch.columnofs[col];

        // step through the posts in a column
        while ((data[columnIdx] & 0xFF) != 0xff) {
            int topdelta = data[columnIdx] & 0xFF;
            int length   = data[columnIdx + 1] & 0xFF;
            int sourceIdx = columnIdx + 3;
            int dest = destIdx + topdelta * Doomdef.SCREENWIDTH;
            int count = length;
            while (count-- > 0) {
                desttop[dest] = data[sourceIdx++];
                dest += Doomdef.SCREENWIDTH;
            }
            columnIdx += length + 4;
        }
    }

    // f_finale.c line 652 — bunnyScroll persistent state.
    private static int laststage;

    //
    // F_BunnyScroll
    // f_finale.c lines 644–694.
    public static void fBunnyScroll() {
        Patch p1;
        Patch p2;
        try {
            p1 = Patch.fromLump(WWad.wCacheLumpName("PFUB2", ZZone.PU_LEVEL));
            p2 = Patch.fromLump(WWad.wCacheLumpName("PFUB1", ZZone.PU_LEVEL));
        } catch (IOException e) {
            throw ISystem.iError("F_BunnyScroll: PFUB load failed: %s",
                    e.getMessage());
        }

        VVideo.vMarkRect(0, 0, Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT);

        int scrolled = 320 - (finalecount - 230) / 2;
        if (scrolled > 320) scrolled = 320;
        if (scrolled < 0)   scrolled = 0;

        for (int x = 0; x < Doomdef.SCREENWIDTH; x++) {
            if (x + scrolled < 320) {
                fDrawPatchCol(x, p1, x + scrolled);
            } else {
                fDrawPatchCol(x, p2, x + scrolled - 320);
            }
        }

        if (finalecount < 1130) {
            return;
        }
        if (finalecount < 1180) {
            try {
                VVideo.vDrawPatch((Doomdef.SCREENWIDTH - 13 * 8) / 2,
                        (Doomdef.SCREENHEIGHT - 8 * 8) / 2, 0,
                        Patch.fromLump(WWad.wCacheLumpName("END0",
                                ZZone.PU_CACHE)));
            } catch (IOException e) {
                throw ISystem.iError("F_BunnyScroll: END0 load failed: %s",
                        e.getMessage());
            }
            laststage = 0;
            return;
        }

        int stage = (finalecount - 1180) / 5;
        if (stage > 6) stage = 6;
        if (stage > laststage) {
            SSound.sStartSound(null, Sounds.sfx_pistol);
            laststage = stage;
        }

        try {
            VVideo.vDrawPatch((Doomdef.SCREENWIDTH - 13 * 8) / 2,
                    (Doomdef.SCREENHEIGHT - 8 * 8) / 2, 0,
                    Patch.fromLump(WWad.wCacheLumpName("END" + stage,
                            ZZone.PU_CACHE)));
        } catch (IOException e) {
            throw ISystem.iError("F_BunnyScroll: END%d load failed: %s",
                    stage, e.getMessage());
        }
    }

    //
    // F_Drawer
    // f_finale.c lines 700–736.
    public static void fDrawer() {
        if (finalestage == 2) {
            fCastDrawer();
            return;
        }

        if (finalestage == 0) {
            fTextWrite();
        } else {
            try {
                switch (Doomstat.gameepisode) {
                    case 1:
                        if (Doomstat.gamemode == Doomstat.GameMode.retail) {
                            VVideo.vDrawPatch(0, 0, 0, Patch.fromLump(
                                    WWad.wCacheLumpName("CREDIT",
                                            ZZone.PU_CACHE)));
                        } else {
                            VVideo.vDrawPatch(0, 0, 0, Patch.fromLump(
                                    WWad.wCacheLumpName("HELP2",
                                            ZZone.PU_CACHE)));
                        }
                        break;
                    case 2:
                        VVideo.vDrawPatch(0, 0, 0, Patch.fromLump(
                                WWad.wCacheLumpName("VICTORY2",
                                        ZZone.PU_CACHE)));
                        break;
                    case 3:
                        fBunnyScroll();
                        break;
                    case 4:
                        VVideo.vDrawPatch(0, 0, 0, Patch.fromLump(
                                WWad.wCacheLumpName("ENDPIC",
                                        ZZone.PU_CACHE)));
                        break;
                    default:
                        break;
                }
            } catch (IOException e) {
                throw ISystem.iError("F_Drawer: art lump load failed: %s",
                        e.getMessage());
            }
        }
    }

    // Silence unused-import noise — DItems / Player are referenced
    // only in commented-out code paths the C kept around.
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORTS_DITEMS = DItems.class;
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORTS_PLAYER = Player.class;
}
