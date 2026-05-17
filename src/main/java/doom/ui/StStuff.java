// Port of linuxdoom-1.10/st_stuff.c — full status-bar code.
//
// Drives the 320×32 status bar at the bottom of the play view:
// ready-weapon ammo, health %, armor %, ammo+maxammo per type,
// frags (deathmatch), weapon-ownership icons, key-card icons,
// the face-animation widget, and the damage / bonus / radiation
// palette flashes. ST_Responder also intercepts cheats.
//
// Architecture mirrors st_lib's: per-widget Number / Percent /
// MultIcon / BinIcon instances point at caller-owned int[1] /
// boolean[1] holders so they can re-read live game state every
// frame. The "address-of a boolean" pattern from the C
// (`(int *) &plyr->weaponowned[i+1]`) translates to a parallel
// `int[1]` view that stUpdateWidgets refreshes from the player
// each tic — see comments in stUpdateWidgets.
//
// Deferred: foreign-key shiftxform tables (not present in this
// C source either, since the cheat scrambler obviates them) and
// the dead chat-window state-machine (st_chat, st_cursoron) —
// the m_menu chat input it gated on never lands. The two fields
// stay so the cheat-message-popup gate in stUpdateWidgets is
// faithful.
package doom.ui;

import doom.play.DItems;
import doom.play.MCheat;
import doom.play.MRandom;
import doom.play.PInter;
import doom.play.Player;
import doom.render.Patch;
import doom.render.RMain;
import doom.render.Tables;
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

public final class StStuff {

    // st_stuff.h lines 32–34. SCREEN_MUL is 1 in the port (see
    // Doomdef), so 32 * SCREEN_MUL folds to 32 without losing the
    // macro shape.
    public static final int ST_HEIGHT = 32 * Doomdef.SCREEN_MUL;
    public static final int ST_WIDTH = Doomdef.SCREENWIDTH;
    public static final int ST_Y = Doomdef.SCREENHEIGHT - ST_HEIGHT;

    // st_stuff.c lines 71–76 — palette indices for damage / bonus /
    // radiation tinting (offsets into the PLAYPAL lump).
    public static final int STARTREDPALS    = 1;
    public static final int STARTBONUSPALS  = 9;
    public static final int NUMREDPALS      = 8;
    public static final int NUMBONUSPALS    = 4;
    public static final int RADIATIONPAL    = 13;

    // st_stuff.c line 80 — N/256 chance the straight-ahead face
    // changes orientation each tic.
    public static final int ST_FACEPROBABILITY = 96;

    // st_stuff.c lines 86–94 — status-bar layout constants.
    public static final int ST_X   = 0;
    public static final int ST_X2  = 104;
    public static final int ST_FX  = 143;
    public static final int ST_FY  = 169;

    // st_stuff.c lines 97–115 — face-strip indexing.
    public static final int ST_NUMPAINFACES     = 5;
    public static final int ST_NUMSTRAIGHTFACES = 3;
    public static final int ST_NUMTURNFACES     = 2;
    public static final int ST_NUMSPECIALFACES  = 3;
    public static final int ST_FACESTRIDE =
            ST_NUMSTRAIGHTFACES + ST_NUMTURNFACES + ST_NUMSPECIALFACES;
    public static final int ST_NUMEXTRAFACES    = 2;
    public static final int ST_NUMFACES =
            ST_FACESTRIDE * ST_NUMPAINFACES + ST_NUMEXTRAFACES;
    public static final int ST_TURNOFFSET     = ST_NUMSTRAIGHTFACES;
    public static final int ST_OUCHOFFSET     = ST_TURNOFFSET + ST_NUMTURNFACES;
    public static final int ST_EVILGRINOFFSET = ST_OUCHOFFSET + 1;
    public static final int ST_RAMPAGEOFFSET  = ST_EVILGRINOFFSET + 1;
    public static final int ST_GODFACE        = ST_NUMPAINFACES * ST_FACESTRIDE;
    public static final int ST_DEADFACE       = ST_GODFACE + 1;

    public static final int ST_FACESX = 143;
    public static final int ST_FACESY = 168;

    // st_stuff.c lines 120–126 — face-state durations (in tics).
    public static final int ST_EVILGRINCOUNT     = 2 * Doomdef.TICRATE;
    public static final int ST_STRAIGHTFACECOUNT = Doomdef.TICRATE / 2;
    public static final int ST_TURNCOUNT         = Doomdef.TICRATE;
    public static final int ST_OUCHCOUNT         = Doomdef.TICRATE;
    public static final int ST_RAMPAGEDELAY      = 2 * Doomdef.TICRATE;
    public static final int ST_MUCHPAIN          = 20;

    // st_stuff.c lines 138–235 — widget pixel positions.
    public static final int ST_AMMOWIDTH   = 3;
    public static final int ST_AMMOX       = 44;
    public static final int ST_AMMOY       = 171;

    public static final int ST_HEALTHWIDTH = 3;
    public static final int ST_HEALTHX     = 90;
    public static final int ST_HEALTHY     = 171;

    public static final int ST_ARMSX       = 111;
    public static final int ST_ARMSY       = 172;
    public static final int ST_ARMSBGX     = 104;
    public static final int ST_ARMSBGY     = 168;
    public static final int ST_ARMSXSPACE  = 12;
    public static final int ST_ARMSYSPACE  = 10;

    public static final int ST_FRAGSX      = 138;
    public static final int ST_FRAGSY      = 171;
    public static final int ST_FRAGSWIDTH  = 2;

    public static final int ST_ARMORWIDTH  = 3;
    public static final int ST_ARMORX      = 221;
    public static final int ST_ARMORY      = 171;

    public static final int ST_KEY0X = 239, ST_KEY0Y = 171;
    public static final int ST_KEY1X = 239, ST_KEY1Y = 181;
    public static final int ST_KEY2X = 239, ST_KEY2Y = 191;

    public static final int ST_AMMO0WIDTH = 3;
    public static final int ST_AMMO0X = 288, ST_AMMO0Y = 173;
    public static final int ST_AMMO1WIDTH = ST_AMMO0WIDTH;
    public static final int ST_AMMO1X = 288, ST_AMMO1Y = 179;
    public static final int ST_AMMO2WIDTH = ST_AMMO0WIDTH;
    public static final int ST_AMMO2X = 288, ST_AMMO2Y = 191;
    public static final int ST_AMMO3WIDTH = ST_AMMO0WIDTH;
    public static final int ST_AMMO3X = 288, ST_AMMO3Y = 185;

    public static final int ST_MAXAMMO0WIDTH = 3;
    public static final int ST_MAXAMMO0X = 314, ST_MAXAMMO0Y = 173;
    public static final int ST_MAXAMMO1WIDTH = ST_MAXAMMO0WIDTH;
    public static final int ST_MAXAMMO1X = 314, ST_MAXAMMO1Y = 179;
    public static final int ST_MAXAMMO2WIDTH = ST_MAXAMMO0WIDTH;
    public static final int ST_MAXAMMO2X = 314, ST_MAXAMMO2Y = 191;
    public static final int ST_MAXAMMO3WIDTH = ST_MAXAMMO0WIDTH;
    public static final int ST_MAXAMMO3X = 314, ST_MAXAMMO3Y = 185;

    public static final int ST_MSGWIDTH  = 52;
    public static final int ST_MSGHEIGHT = 1;

    // st_stuff.c lines 261–267 — st_stateenum_t / st_chatstateenum_t.
    public static final int AutomapState     = 0;
    public static final int FirstPersonState = 1;
    public static final int StartChatState   = 0;
    public static final int WaitDestState    = 1;
    public static final int GetChatState     = 2;

    // st_stuff.c lines 270–393 — static state.
    public static Player plyr;
    public static boolean stFirstTime;
    public static int    stClock;
    public static int    stMsgCounter;
    public static int    stChatState;
    public static int    stGameState;
    public static boolean[] stStatusbarOn = new boolean[] { true };
    public static boolean stChat;
    public static boolean stOldChat;
    public static boolean stCursorOn;
    public static boolean[] stNotDeathmatch = new boolean[1];
    public static boolean[] stArmsOn        = new boolean[1];
    public static boolean[] stFragsOn       = new boolean[1];

    // Patch caches — populated by stLoadGraphics.
    public static int    luPalette;
    public static Patch  sbar;
    public static Patch[] tallnum    = new Patch[10];
    public static Patch  tallpercent;
    public static Patch[] shortnum   = new Patch[10];
    public static Patch[] keys       = new Patch[Doomdef.NUMCARDS];
    public static Patch[] faces      = new Patch[ST_NUMFACES];
    public static Patch  faceback;
    public static Patch  armsbg;
    public static Patch[][] arms     = new Patch[6][2];

    // Widget instances.
    public static StLib.Number   wReady    = new StLib.Number();
    public static StLib.Number   wFrags    = new StLib.Number();
    public static StLib.Percent  wHealth   = new StLib.Percent();
    public static StLib.BinIcon  wArmsBg   = new StLib.BinIcon();
    public static StLib.MultIcon[] wArms   = makeArr(6);
    public static StLib.MultIcon wFaces    = new StLib.MultIcon();
    public static StLib.MultIcon[] wKeyboxes = makeArr(3);
    public static StLib.Percent  wArmor    = new StLib.Percent();
    public static StLib.Number[] wAmmo     = makeNum(4);
    public static StLib.Number[] wMaxAmmo  = makeNum(4);

    private static StLib.MultIcon[] makeArr(int n) {
        StLib.MultIcon[] a = new StLib.MultIcon[n];
        for (int i = 0; i < n; i++) a[i] = new StLib.MultIcon();
        return a;
    }

    private static StLib.Number[] makeNum(int n) {
        StLib.Number[] a = new StLib.Number[n];
        for (int i = 0; i < n; i++) a[i] = new StLib.Number();
        return a;
    }

    // Per-frame holders for live game state. Each is the C "address
    // of a primitive" — STlib widgets read `[0]` every frame. Refilled
    // in stUpdateWidgets (or stCreateWidgets for fixed-source values).
    //
    // Java has no `&plyr->weaponowned[i+1]` cast trick the C uses to
    // expose a boolean as an int* to STlib_initMultIcon — instead, we
    // mirror live state into these int[1] / boolean[1] views every
    // tic so the widget paint loop sees the change.
    public static int[][]  armsOwnedView = new int[6][1];
    public static int[][]  keyboxesView  = new int[3][1];
    public static int[]    readyAmmoView = new int[1];
    public static int[]    healthView    = new int[1];
    public static int[]    armorView     = new int[1];
    public static int[]    faceIndexView = new int[1];
    public static int[]    fragsCountView = new int[1];
    public static int[][]  ammoView      = new int[4][1];
    public static int[][]  maxAmmoView   = new int[4][1];

    // st_stuff.c lines 375–393 — per-tic / per-frame logic state.
    public static int    stFragsCount;
    public static int    stOldHealth = -1;
    public static boolean[] oldWeaponsOwned = new boolean[Doomdef.NUMWEAPONS];
    public static int    stFaceCount;
    public static int    stFaceIndex;
    public static int[]  keyboxes = new int[3];
    public static int    stRandomNumber;

    // st_stuff.c lines 729–743 — stCalcPainOffset persistent state.
    // Public so unit tests can reset between cases (the C `static` is
    // per-process; Java tests share one JVM).
    public static int painLastCalc;
    public static int painOldHealth = -1;

    // st_stuff.c lines 757–758 — stUpdateFaceWidget persistent state.
    public static int faceLastAttackDown = -1;
    public static int facePriority       = 0;

    // st_stuff.c line 998 — current palette slot, so stDoPaletteStuff
    // only re-uploads when it changes.
    public static int stPalette = 0;

    // st_stuff.c lines 1441, 276 — ST_Start/Stop sentinels.
    public static boolean stStopped = true;
    public static boolean veryFirstTime = true;

    //
    // st_stuff.c lines 400–487 — cheat sequences.
    //
    // The C ends each sequence with a 0xff sentinel; intermediate
    // bytes 0x01 / 0x00 mark a parameter slot (filled by
    // cht_GetParam). The scrambled byte values come straight from
    // the C source.
    public static final byte[] cheatMusSeq = {
            (byte) 0xb2, 0x26, (byte) 0xb6, (byte) 0xae, (byte) 0xea, 1, 0, 0, (byte) 0xff,
    };
    public static final byte[] cheatChoppersSeq = {
            (byte) 0xb2, 0x26, (byte) 0xe2, 0x32, (byte) 0xf6, 0x2a, 0x2a,
            (byte) 0xa6, 0x6a, (byte) 0xea, (byte) 0xff,
    };
    public static final byte[] cheatGodSeq = {
            (byte) 0xb2, 0x26, 0x26, (byte) 0xaa, 0x26, (byte) 0xff,
    };
    public static final byte[] cheatAmmoSeq = {
            (byte) 0xb2, 0x26, (byte) 0xf2, 0x66, (byte) 0xa2, (byte) 0xff,
    };
    public static final byte[] cheatAmmonokeySeq = {
            (byte) 0xb2, 0x26, 0x66, (byte) 0xa2, (byte) 0xff,
    };
    public static final byte[] cheatNoclipSeq = {
            (byte) 0xb2, 0x26, (byte) 0xea, 0x2a, (byte) 0xb2,
            (byte) 0xea, 0x2a, (byte) 0xf6, 0x2a, 0x26, (byte) 0xff,
    };
    public static final byte[] cheatCommercialNoclipSeq = {
            (byte) 0xb2, 0x26, (byte) 0xe2, 0x36, (byte) 0xb2, 0x2a, (byte) 0xff,
    };

    public static final byte[][] cheatPowerupSeq = new byte[][] {
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, 0x6e, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, (byte) 0xea, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, (byte) 0xb2, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, 0x6a, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, (byte) 0xa2, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, 0x36, (byte) 0xff },
            { (byte) 0xb2, 0x26, 0x62, (byte) 0xa6, 0x32, (byte) 0xf6, 0x36, 0x26, (byte) 0xff },
    };

    public static final byte[] cheatClevSeq = {
            (byte) 0xb2, 0x26, (byte) 0xe2, 0x36, (byte) 0xa6, 0x6e, 1, 0, 0, (byte) 0xff,
    };
    public static final byte[] cheatMyposSeq = {
            (byte) 0xb2, 0x26, (byte) 0xb6, (byte) 0xba, 0x2a, (byte) 0xf6, (byte) 0xea, (byte) 0xff,
    };

    public static final MCheat.Cheatseq cheatMus              = new MCheat.Cheatseq(cheatMusSeq);
    public static final MCheat.Cheatseq cheatGod              = new MCheat.Cheatseq(cheatGodSeq);
    public static final MCheat.Cheatseq cheatAmmo             = new MCheat.Cheatseq(cheatAmmoSeq);
    public static final MCheat.Cheatseq cheatAmmonokey        = new MCheat.Cheatseq(cheatAmmonokeySeq);
    public static final MCheat.Cheatseq cheatNoclip           = new MCheat.Cheatseq(cheatNoclipSeq);
    public static final MCheat.Cheatseq cheatCommercialNoclip = new MCheat.Cheatseq(cheatCommercialNoclipSeq);
    public static final MCheat.Cheatseq[] cheatPowerup        = makePowerup();
    public static final MCheat.Cheatseq cheatChoppers         = new MCheat.Cheatseq(cheatChoppersSeq);
    public static final MCheat.Cheatseq cheatClev             = new MCheat.Cheatseq(cheatClevSeq);
    public static final MCheat.Cheatseq cheatMypos            = new MCheat.Cheatseq(cheatMyposSeq);

    private static MCheat.Cheatseq[] makePowerup() {
        MCheat.Cheatseq[] a = new MCheat.Cheatseq[7];
        for (int i = 0; i < 7; i++) a[i] = new MCheat.Cheatseq(cheatPowerupSeq[i]);
        return a;
    }

    //
    // ST_refreshBackground
    // st_stuff.c lines 499–512.
    public static void stRefreshBackground() {
        if (stStatusbarOn[0]) {
            VVideo.vDrawPatch(ST_X, 0, StLib.BG, sbar);

            if (Doomstat.netgame) {
                VVideo.vDrawPatch(ST_FX, 0, StLib.BG, faceback);
            }

            VVideo.vCopyRect(ST_X, 0, StLib.BG, ST_WIDTH, ST_HEIGHT,
                    ST_X, ST_Y, StLib.FG);
        }
    }

    //
    // ST_Responder
    // st_stuff.c lines 517–725. Handles automap mode-flip events plus
    // the cheat decoder. Returns false always in the C (returning
    // false lets the menu / game responders see the event too).
    public static boolean stResponder(DEvent ev) {
        int i;
        char key = (char) (ev.data1 & 0xFFFF);

        // Filter automap on/off.
        if (ev.type == DEvent.EV_KEYUP
                && ((ev.data1 & 0xffff0000) == AmMap.AM_MSGHEADER)) {
            if (ev.data1 == AmMap.AM_MSGENTERED) {
                stGameState  = AutomapState;
                stFirstTime  = true;
            } else if (ev.data1 == AmMap.AM_MSGEXITED) {
                stGameState  = FirstPersonState;
            }
        }
        // if a user keypress...
        else if (ev.type == DEvent.EV_KEYDOWN) {
            if (!Doomstat.netgame) {
                // 'dqd' cheat for toggleable god mode
                if (MCheat.chtCheckCheat(cheatGod, key) != 0) {
                    plyr.cheats ^= Player.CF_GODMODE;
                    if ((plyr.cheats & Player.CF_GODMODE) != 0) {
                        if (plyr.mo != null) {
                            plyr.mo.health = 100;
                        }
                        plyr.health = 100;
                        plyr.message = Dstrings.STSTR_DQDON;
                    } else {
                        plyr.message = Dstrings.STSTR_DQDOFF;
                    }
                }
                // 'fa' cheat for killer fucking arsenal
                else if (MCheat.chtCheckCheat(cheatAmmonokey, key) != 0) {
                    plyr.armorpoints = 200;
                    plyr.armortype = 2;
                    for (i = 0; i < Doomdef.NUMWEAPONS; i++) {
                        plyr.weaponowned[i] = true;
                    }
                    for (i = 0; i < Doomdef.NUMAMMO; i++) {
                        plyr.ammo[i] = plyr.maxammo[i];
                    }
                    plyr.message = Dstrings.STSTR_FAADDED;
                }
                // 'kfa' cheat for key full ammo
                else if (MCheat.chtCheckCheat(cheatAmmo, key) != 0) {
                    plyr.armorpoints = 200;
                    plyr.armortype = 2;
                    for (i = 0; i < Doomdef.NUMWEAPONS; i++) {
                        plyr.weaponowned[i] = true;
                    }
                    for (i = 0; i < Doomdef.NUMAMMO; i++) {
                        plyr.ammo[i] = plyr.maxammo[i];
                    }
                    for (i = 0; i < Doomdef.NUMCARDS; i++) {
                        plyr.cards[i] = true;
                    }
                    plyr.message = Dstrings.STSTR_KFAADDED;
                }
                // 'mus' cheat for changing music
                else if (MCheat.chtCheckCheat(cheatMus, key) != 0) {
                    byte[] buf = new byte[3];
                    int musnum;

                    plyr.message = Dstrings.STSTR_MUS;
                    MCheat.chtGetParam(cheatMus, buf);

                    if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                        musnum = Sounds.mus_runnin + (buf[0] - '0') * 10 + buf[1] - '0' - 1;
                        if (((buf[0] - '0') * 10 + buf[1] - '0') > 35) {
                            plyr.message = Dstrings.STSTR_NOMUS;
                        } else {
                            SSound.sChangeMusic(musnum, 1);
                        }
                    } else {
                        musnum = Sounds.mus_e1m1 + (buf[0] - '1') * 9 + (buf[1] - '1');
                        if (((buf[0] - '1') * 9 + buf[1] - '1') > 31) {
                            plyr.message = Dstrings.STSTR_NOMUS;
                        } else {
                            SSound.sChangeMusic(musnum, 1);
                        }
                    }
                }
                // Simplified, accepting both "noclip" and "idspispopd".
                else if (MCheat.chtCheckCheat(cheatNoclip, key) != 0
                        || MCheat.chtCheckCheat(cheatCommercialNoclip, key) != 0) {
                    plyr.cheats ^= Player.CF_NOCLIP;
                    plyr.message = (plyr.cheats & Player.CF_NOCLIP) != 0
                            ? Dstrings.STSTR_NCON
                            : Dstrings.STSTR_NCOFF;
                }
                // 'behold?' power-up cheats
                for (i = 0; i < 6; i++) {
                    if (MCheat.chtCheckCheat(cheatPowerup[i], key) != 0) {
                        if (plyr.powers[i] == 0) {
                            PInter.pGivePower(plyr, i);
                        } else if (i != Doomdef.pw_strength) {
                            plyr.powers[i] = 1;
                        } else {
                            plyr.powers[i] = 0;
                        }
                        plyr.message = Dstrings.STSTR_BEHOLDX;
                    }
                }

                // 'behold' power-up menu
                if (MCheat.chtCheckCheat(cheatPowerup[6], key) != 0) {
                    plyr.message = Dstrings.STSTR_BEHOLD;
                }
                // 'choppers' invulnerability & chainsaw
                else if (MCheat.chtCheckCheat(cheatChoppers, key) != 0) {
                    plyr.weaponowned[Doomdef.wp_chainsaw] = true;
                    plyr.powers[Doomdef.pw_invulnerability] = 1;
                    plyr.message = Dstrings.STSTR_CHOPPERS;
                }
                // 'mypos' for player position
                else if (MCheat.chtCheckCheat(cheatMypos, key) != 0) {
                    plyr.message = String.format("ang=0x%x;x,y=(0x%x,0x%x)",
                            GGame.players[GGame.consoleplayer].mo.angle,
                            GGame.players[GGame.consoleplayer].mo.x,
                            GGame.players[GGame.consoleplayer].mo.y);
                }
            }

            // 'clev' change-level cheat
            if (MCheat.chtCheckCheat(cheatClev, key) != 0) {
                byte[] buf = new byte[3];
                int epsd;
                int map;

                MCheat.chtGetParam(cheatClev, buf);

                if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                    epsd = 0;
                    map = (buf[0] - '0') * 10 + buf[1] - '0';
                } else {
                    epsd = buf[0] - '0';
                    map = buf[1] - '0';
                }

                // Catch invalid maps.
                if (epsd < 1) return false;
                if (map < 1) return false;
                if (Doomstat.gamemode == Doomstat.GameMode.retail
                        && (epsd > 4 || map > 9)) return false;
                if (Doomstat.gamemode == Doomstat.GameMode.registered
                        && (epsd > 3 || map > 9)) return false;
                if (Doomstat.gamemode == Doomstat.GameMode.shareware
                        && (epsd > 1 || map > 9)) return false;
                if (Doomstat.gamemode == Doomstat.GameMode.commercial
                        && (epsd > 1 || map > 34)) return false;

                plyr.message = Dstrings.STSTR_CLEV;
                GGame.gDeferedInitNew(Doomstat.gameskill, epsd, map);
            }
        }
        return false;
    }

    //
    // ST_calcPainOffset
    // st_stuff.c lines 729–743.
    public static int stCalcPainOffset() {
        int health = plyr.health > 100 ? 100 : plyr.health;

        if (health != painOldHealth) {
            painLastCalc = ST_FACESTRIDE * (((100 - health) * ST_NUMPAINFACES) / 101);
            painOldHealth = health;
        }
        return painLastCalc;
    }

    //
    // ST_updateFaceWidget
    // st_stuff.c lines 752–922. Drives the face mug; priority order is
    // dead > evil grin > turned head > rampage > godface > straight.
    public static void stUpdateFaceWidget() {
        int i;
        int badguyangle;
        int diffang;
        boolean doevilgrin;

        if (facePriority < 10) {
            // dead
            if (plyr.health == 0) {
                facePriority = 9;
                stFaceIndex  = ST_DEADFACE;
                stFaceCount  = 1;
            }
        }

        if (facePriority < 9) {
            if (plyr.bonuscount != 0) {
                // picking up bonus
                doevilgrin = false;

                for (i = 0; i < Doomdef.NUMWEAPONS; i++) {
                    if (oldWeaponsOwned[i] != plyr.weaponowned[i]) {
                        doevilgrin = true;
                        oldWeaponsOwned[i] = plyr.weaponowned[i];
                    }
                }
                if (doevilgrin) {
                    facePriority = 8;
                    stFaceCount  = ST_EVILGRINCOUNT;
                    stFaceIndex  = stCalcPainOffset() + ST_EVILGRINOFFSET;
                }
            }
        }

        if (facePriority < 8) {
            if (plyr.damagecount != 0
                    && plyr.attacker != null
                    && plyr.attacker != plyr.mo) {
                // being attacked
                facePriority = 7;

                if (plyr.health - stOldHealth > ST_MUCHPAIN) {
                    stFaceCount = ST_TURNCOUNT;
                    stFaceIndex = stCalcPainOffset() + ST_OUCHOFFSET;
                } else {
                    badguyangle = RMain.rPointToAngle2(plyr.mo.x, plyr.mo.y,
                            plyr.attacker.x, plyr.attacker.y);

                    // angle_t comparisons are unsigned 32-bit in the C.
                    if (Integer.compareUnsigned(badguyangle, plyr.mo.angle) > 0) {
                        // whether right or left
                        diffang = badguyangle - plyr.mo.angle;
                        i = Integer.compareUnsigned(diffang, Tables.ANG180) > 0 ? 1 : 0;
                    } else {
                        diffang = plyr.mo.angle - badguyangle;
                        i = Integer.compareUnsigned(diffang, Tables.ANG180) <= 0 ? 1 : 0;
                    }

                    stFaceCount = ST_TURNCOUNT;
                    stFaceIndex = stCalcPainOffset();

                    if (Integer.compareUnsigned(diffang, Tables.ANG45) < 0) {
                        // head-on
                        stFaceIndex += ST_RAMPAGEOFFSET;
                    } else if (i != 0) {
                        // turn face right
                        stFaceIndex += ST_TURNOFFSET;
                    } else {
                        // turn face left
                        stFaceIndex += ST_TURNOFFSET + 1;
                    }
                }
            }
        }

        if (facePriority < 7) {
            // getting hurt because of your own damn stupidity
            if (plyr.damagecount != 0) {
                if (plyr.health - stOldHealth > ST_MUCHPAIN) {
                    facePriority = 7;
                    stFaceCount  = ST_TURNCOUNT;
                    stFaceIndex  = stCalcPainOffset() + ST_OUCHOFFSET;
                } else {
                    facePriority = 6;
                    stFaceCount  = ST_TURNCOUNT;
                    stFaceIndex  = stCalcPainOffset() + ST_RAMPAGEOFFSET;
                }
            }
        }

        if (facePriority < 6) {
            // rapid firing
            if (plyr.attackdown != 0) {
                if (faceLastAttackDown == -1) {
                    faceLastAttackDown = ST_RAMPAGEDELAY;
                } else if (--faceLastAttackDown == 0) {
                    facePriority       = 5;
                    stFaceIndex        = stCalcPainOffset() + ST_RAMPAGEOFFSET;
                    stFaceCount        = 1;
                    faceLastAttackDown = 1;
                }
            } else {
                faceLastAttackDown = -1;
            }
        }

        if (facePriority < 5) {
            // invulnerability
            if ((plyr.cheats & Player.CF_GODMODE) != 0
                    || plyr.powers[Doomdef.pw_invulnerability] != 0) {
                facePriority = 4;
                stFaceIndex  = ST_GODFACE;
                stFaceCount  = 1;
            }
        }

        // look left / right when the facecount has timed out
        if (stFaceCount == 0) {
            stFaceIndex  = stCalcPainOffset() + (stRandomNumber % 3);
            stFaceCount  = ST_STRAIGHTFACECOUNT;
            facePriority = 0;
        }

        stFaceCount--;
    }

    //
    // ST_updateWidgets
    // st_stuff.c lines 924–986.
    public static void stUpdateWidgets() {
        // C uses a static "n/a" magic-value 1994 to mean "draw no
        // digits for the chainsaw / fist (am_noammo) ready weapon".
        // STlib_drawNum tests `num == 1994` and bails. The Number
        // widget reads from `wReady.num[0]` so we just stuff 1994
        // into the holder when the current weapon has no ammo.
        if (DItems.weaponinfo[plyr.readyweapon].ammo == Doomdef.am_noammo) {
            readyAmmoView[0] = 1994;
        } else {
            readyAmmoView[0] = plyr.ammo[DItems.weaponinfo[plyr.readyweapon].ammo];
        }
        wReady.data = plyr.readyweapon;

        // Refresh live state into the per-widget holders.
        healthView[0] = plyr.health;
        armorView[0]  = plyr.armorpoints;
        for (int j = 0; j < Doomdef.NUMAMMO; j++) {
            ammoView[j][0]    = plyr.ammo[j];
            maxAmmoView[j][0] = plyr.maxammo[j];
        }

        // update keycard multiple widgets
        for (int i = 0; i < 3; i++) {
            keyboxes[i] = plyr.cards[i] ? i : -1;
            if (plyr.cards[i + 3]) {
                keyboxes[i] = i + 3;
            }
            keyboxesView[i][0] = keyboxes[i];
        }

        // refresh weapon-ownership view
        for (int i = 0; i < 6; i++) {
            armsOwnedView[i][0] = plyr.weaponowned[i + 1] ? 1 : 0;
        }

        // refresh everything if this is him coming back to life
        stUpdateFaceWidget();
        faceIndexView[0] = stFaceIndex;

        // used by the w_armsbg widget
        stNotDeathmatch[0] = Doomstat.deathmatch == 0;

        // used by w_arms[] widgets
        stArmsOn[0]  = stStatusbarOn[0] && Doomstat.deathmatch == 0;

        // used by w_frags widget
        stFragsOn[0] = Doomstat.deathmatch != 0 && stStatusbarOn[0];
        stFragsCount = 0;

        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (i != GGame.consoleplayer) {
                stFragsCount += plyr.frags[i];
            } else {
                stFragsCount -= plyr.frags[i];
            }
        }
        fragsCountView[0] = stFragsCount;

        // get rid of chat window if up because of message
        if (--stMsgCounter == 0) {
            stChat = stOldChat;
        }
    }

    //
    // ST_Ticker
    // st_stuff.c lines 988–996.
    public static void stTicker() {
        stClock++;
        stRandomNumber = MRandom.mRandom();
        stUpdateWidgets();
        stOldHealth = plyr.health;
    }

    //
    // ST_doPaletteStuff
    // st_stuff.c lines 1000–1052.
    public static void stDoPaletteStuff() {
        int palette;
        int cnt = plyr.damagecount;
        int bzc;

        if (plyr.powers[Doomdef.pw_strength] != 0) {
            // slowly fade the berserk out
            bzc = 12 - (plyr.powers[Doomdef.pw_strength] >> 6);
            if (bzc > cnt) cnt = bzc;
        }

        if (cnt != 0) {
            palette = (cnt + 7) >> 3;
            if (palette >= NUMREDPALS) palette = NUMREDPALS - 1;
            palette += STARTREDPALS;
        } else if (plyr.bonuscount != 0) {
            palette = (plyr.bonuscount + 7) >> 3;
            if (palette >= NUMBONUSPALS) palette = NUMBONUSPALS - 1;
            palette += STARTBONUSPALS;
        } else if (plyr.powers[Doomdef.pw_ironfeet] > 4 * 32
                || (plyr.powers[Doomdef.pw_ironfeet] & 8) != 0) {
            palette = RADIATIONPAL;
        } else {
            palette = 0;
        }

        if (palette != stPalette) {
            stPalette = palette;
            byte[] pal;
            try {
                pal = WWad.wCacheLumpNum(luPalette, ZZone.PU_CACHE);
            } catch (IOException e) {
                throw ISystem.iError("ST: PLAYPAL cache failed: %s", e.getMessage());
            }
            // C does `pal + palette*768` to skip 256-colour-triple
            // sub-palettes; copy out the slice.
            byte[] slice = new byte[768];
            System.arraycopy(pal, palette * 768, slice, 0, 768);
            IVideo.iSetPalette(slice);
        }
    }

    //
    // ST_drawWidgets
    // st_stuff.c lines 1054–1087.
    public static void stDrawWidgets(boolean refresh) {
        // used by w_arms[] widgets
        stArmsOn[0]  = stStatusbarOn[0] && Doomstat.deathmatch == 0;
        // used by w_frags widget
        stFragsOn[0] = Doomstat.deathmatch != 0 && stStatusbarOn[0];

        StLib.stLibUpdateNum(wReady, refresh);

        for (int i = 0; i < 4; i++) {
            StLib.stLibUpdateNum(wAmmo[i], refresh);
            StLib.stLibUpdateNum(wMaxAmmo[i], refresh);
        }

        StLib.stLibUpdatePercent(wHealth, refresh ? 1 : 0);
        StLib.stLibUpdatePercent(wArmor, refresh ? 1 : 0);

        StLib.stLibUpdateBinIcon(wArmsBg, refresh);

        for (int i = 0; i < 6; i++) {
            StLib.stLibUpdateMultIcon(wArms[i], refresh);
        }

        StLib.stLibUpdateMultIcon(wFaces, refresh);

        for (int i = 0; i < 3; i++) {
            StLib.stLibUpdateMultIcon(wKeyboxes[i], refresh);
        }

        StLib.stLibUpdateNum(wFrags, refresh);
    }

    //
    // ST_doRefresh / ST_diffDraw / ST_Drawer
    // st_stuff.c lines 1089–1122.
    public static void stDoRefresh() {
        stFirstTime = false;
        stRefreshBackground();
        stDrawWidgets(true);
    }

    public static void stDiffDraw() {
        stDrawWidgets(false);
    }

    public static void stDrawer(boolean fullscreen, boolean refresh) {
        stStatusbarOn[0] = !fullscreen || AmMap.automapactive;
        stFirstTime      = stFirstTime || refresh;

        // Do red-/gold-shifts from damage/items
        stDoPaletteStuff();

        // If just after ST_Start(), refresh all
        if (stFirstTime) {
            stDoRefresh();
        } else {
            stDiffDraw();
        }
    }

    //
    // ST_loadGraphics
    // st_stuff.c lines 1124–1199.
    public static void stLoadGraphics() throws IOException {
        int facenum;

        // Load the numbers, tall and short
        for (int i = 0; i < 10; i++) {
            tallnum[i]  = Patch.fromLump(WWad.wCacheLumpName("STTNUM"  + i, ZZone.PU_STATIC));
            shortnum[i] = Patch.fromLump(WWad.wCacheLumpName("STYSNUM" + i, ZZone.PU_STATIC));
        }

        // Load percent key. Note: why not load STMINUS here too?
        tallpercent = Patch.fromLump(WWad.wCacheLumpName("STTPRCNT", ZZone.PU_STATIC));

        // key cards
        for (int i = 0; i < Doomdef.NUMCARDS; i++) {
            keys[i] = Patch.fromLump(WWad.wCacheLumpName("STKEYS" + i, ZZone.PU_STATIC));
        }

        // arms background
        armsbg = Patch.fromLump(WWad.wCacheLumpName("STARMS", ZZone.PU_STATIC));

        // arms ownership widgets
        for (int i = 0; i < 6; i++) {
            // gray #
            arms[i][0] = Patch.fromLump(WWad.wCacheLumpName("STGNUM" + (i + 2), ZZone.PU_STATIC));
            // yellow #
            arms[i][1] = shortnum[i + 2];
        }

        // face backgrounds for different colour players
        faceback = Patch.fromLump(WWad.wCacheLumpName("STFB" + GGame.consoleplayer, ZZone.PU_STATIC));

        // status bar background bits
        sbar = Patch.fromLump(WWad.wCacheLumpName("STBAR", ZZone.PU_STATIC));

        // face states
        facenum = 0;
        for (int i = 0; i < ST_NUMPAINFACES; i++) {
            for (int j = 0; j < ST_NUMSTRAIGHTFACES; j++) {
                faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFST" + i + j, ZZone.PU_STATIC));
            }
            faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFTR" + i + "0", ZZone.PU_STATIC));  // turn right
            faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFTL" + i + "0", ZZone.PU_STATIC));  // turn left
            faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFOUCH" + i, ZZone.PU_STATIC));      // ouch!
            faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFEVL"  + i, ZZone.PU_STATIC));      // evil grin ;)
            faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFKILL" + i, ZZone.PU_STATIC));      // pissed off
        }
        faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFGOD0",  ZZone.PU_STATIC));
        faces[facenum++] = Patch.fromLump(WWad.wCacheLumpName("STFDEAD0", ZZone.PU_STATIC));
    }

    //
    // ST_loadData
    // st_stuff.c lines 1201–1205.
    public static void stLoadData() throws IOException {
        luPalette = WWad.wGetNumForName("PLAYPAL");
        stLoadGraphics();
    }

    //
    // ST_initData
    // st_stuff.c lines 1249–1278.
    public static void stInitData() {
        stFirstTime  = true;
        plyr         = GGame.players[GGame.consoleplayer];

        stClock      = 0;
        stChatState  = StartChatState;
        stGameState  = FirstPersonState;

        stStatusbarOn[0] = true;
        stOldChat = stChat = false;
        stCursorOn = false;

        stFaceIndex  = 0;
        stPalette    = -1;

        stOldHealth  = -1;

        for (int i = 0; i < Doomdef.NUMWEAPONS; i++) {
            oldWeaponsOwned[i] = plyr.weaponowned[i];
        }

        for (int i = 0; i < 3; i++) {
            keyboxes[i] = -1;
        }

        StLib.stLibInit();
    }

    //
    // ST_createWidgets
    // st_stuff.c lines 1282–1439. Wires the widget pointers to the
    // per-frame holders. Most C "&plyr->field" pointers translate to
    // the matching *_View holders that stUpdateWidgets refills.
    public static void stCreateWidgets() {
        // ready weapon ammo
        StLib.stLibInitNum(wReady, ST_AMMOX, ST_AMMOY, tallnum,
                readyAmmoView, stStatusbarOn, ST_AMMOWIDTH);

        // the last weapon type
        wReady.data = plyr.readyweapon;

        // health percentage
        StLib.stLibInitPercent(wHealth, ST_HEALTHX, ST_HEALTHY, tallnum,
                healthView, stStatusbarOn, tallpercent);

        // arms background
        StLib.stLibInitBinIcon(wArmsBg, ST_ARMSBGX, ST_ARMSBGY,
                armsbg, stNotDeathmatch, stStatusbarOn);

        // weapons owned
        for (int i = 0; i < 6; i++) {
            StLib.stLibInitMultIcon(wArms[i],
                    ST_ARMSX + (i % 3) * ST_ARMSXSPACE,
                    ST_ARMSY + (i / 3) * ST_ARMSYSPACE,
                    arms[i], armsOwnedView[i], stArmsOn);
        }

        // frags sum
        StLib.stLibInitNum(wFrags, ST_FRAGSX, ST_FRAGSY, tallnum,
                fragsCountView, stFragsOn, ST_FRAGSWIDTH);

        // faces
        StLib.stLibInitMultIcon(wFaces, ST_FACESX, ST_FACESY, faces,
                faceIndexView, stStatusbarOn);

        // armor percentage - should be coloured later
        StLib.stLibInitPercent(wArmor, ST_ARMORX, ST_ARMORY, tallnum,
                armorView, stStatusbarOn, tallpercent);

        // keyboxes 0-2
        StLib.stLibInitMultIcon(wKeyboxes[0], ST_KEY0X, ST_KEY0Y,
                keys, keyboxesView[0], stStatusbarOn);
        StLib.stLibInitMultIcon(wKeyboxes[1], ST_KEY1X, ST_KEY1Y,
                keys, keyboxesView[1], stStatusbarOn);
        StLib.stLibInitMultIcon(wKeyboxes[2], ST_KEY2X, ST_KEY2Y,
                keys, keyboxesView[2], stStatusbarOn);

        // ammo count (all four kinds)
        StLib.stLibInitNum(wAmmo[0], ST_AMMO0X, ST_AMMO0Y, shortnum,
                ammoView[0], stStatusbarOn, ST_AMMO0WIDTH);
        StLib.stLibInitNum(wAmmo[1], ST_AMMO1X, ST_AMMO1Y, shortnum,
                ammoView[1], stStatusbarOn, ST_AMMO1WIDTH);
        StLib.stLibInitNum(wAmmo[2], ST_AMMO2X, ST_AMMO2Y, shortnum,
                ammoView[2], stStatusbarOn, ST_AMMO2WIDTH);
        StLib.stLibInitNum(wAmmo[3], ST_AMMO3X, ST_AMMO3Y, shortnum,
                ammoView[3], stStatusbarOn, ST_AMMO3WIDTH);

        // max ammo count (all four kinds)
        StLib.stLibInitNum(wMaxAmmo[0], ST_MAXAMMO0X, ST_MAXAMMO0Y, shortnum,
                maxAmmoView[0], stStatusbarOn, ST_MAXAMMO0WIDTH);
        StLib.stLibInitNum(wMaxAmmo[1], ST_MAXAMMO1X, ST_MAXAMMO1Y, shortnum,
                maxAmmoView[1], stStatusbarOn, ST_MAXAMMO1WIDTH);
        StLib.stLibInitNum(wMaxAmmo[2], ST_MAXAMMO2X, ST_MAXAMMO2Y, shortnum,
                maxAmmoView[2], stStatusbarOn, ST_MAXAMMO2WIDTH);
        StLib.stLibInitNum(wMaxAmmo[3], ST_MAXAMMO3X, ST_MAXAMMO3Y, shortnum,
                maxAmmoView[3], stStatusbarOn, ST_MAXAMMO3WIDTH);
    }

    //
    // ST_Start
    // st_stuff.c lines 1444–1454.
    public static void stStart() {
        if (!stStopped) {
            stStop();
        }
        // Java-only guard: in the C, ST_Init runs unconditionally
        // during D_DoomMain before any ST_Start fires, so the patch
        // caches are always loaded. Several playsim tests call
        // pSpawnPlayer (→ stStart) without booting the engine and
        // therefore never load the WAD — skip the widget init in that
        // case so the test can still exercise the spawn path. Same
        // shape as HuStuff.huStart's huFont[0]==null check
        // (deviation 34).
        if (sbar == null) {
            return;
        }
        stInitData();
        stCreateWidgets();
        stStopped = false;
    }

    //
    // ST_Stop
    // st_stuff.c lines 1456–1464. Restores the base palette.
    public static void stStop() {
        if (stStopped) return;

        try {
            byte[] pal = WWad.wCacheLumpNum(luPalette, ZZone.PU_CACHE);
            // The base palette is the first 768-byte sub-palette;
            // I_SetPalette in the C takes a raw byte* so we slice.
            byte[] slice = new byte[768];
            System.arraycopy(pal, 0, slice, 0, 768);
            IVideo.iSetPalette(slice);
        } catch (IOException e) {
            throw ISystem.iError("ST: PLAYPAL cache failed: %s", e.getMessage());
        }
        stStopped = true;
    }

    //
    // ST_Init
    // st_stuff.c lines 1466–1471. Boots once at game start.
    public static void stInit() throws IOException {
        veryFirstTime = false;
        stLoadData();
        // Allocate the status-bar background buffer (screen 4 in the
        // C). VVideo only sizes the first four 320×200 screens, so the
        // status-bar layer gets its own ST_WIDTH × ST_HEIGHT slot.
        VVideo.screens[4] = new byte[ST_WIDTH * ST_HEIGHT];
    }

    private StStuff() {
    }
}
