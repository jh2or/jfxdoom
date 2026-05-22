// Port of linuxdoom-1.10/d_main.c — partial.
//
// Top-level orchestration: argument parsing, IWAD identification,
// subsystem boot (V_Init / Z_Init / W_InitFile / R_Init / P_Init /
// I_Init / S_Init), initial level load (G_InitNew → P_SetupLevel),
// and the per-tic D_DoomLoop frame driver.
//
// Deferred (waits on the modules they reach into):
//   - D_Display (full): needs ST_Drawer / HU_Drawer / WI_Drawer /
//     F_Drawer / AM_Drawer / FWipe screen-wipe machinery
//   - D_PostEvent / D_ProcessEvents: needs M_Responder / G_Responder
//     event chain (m_menu / g_game)
//   - D_StartTitle / D_AdvanceDemo / D_PageDrawer: title-screen
//     demo loop
//   - G_BuildTiccmd / G_Ticker / G_InitNew: g_game.c, big port
//   - The -file / -warp / -skill / -timer / -respawn / -fast /
//     -nomonsters / -altdeath / -deathmatch parsers (M_CheckParm-
//     based) work but the parameters they set are ignored until
//     g_game ports.
//
// What this commit provides:
//   - dDoomMain(String[] args) — parses -iwad and -warp, boots all
//     ported subsystems, loads the requested level via
//     PSetup.pSetupLevelGeometry + PSetup.pLoadThings.
//   - dDoomLoop() — runs the per-frame skeleton: I_StartFrame →
//     I_StartTic → D_ProcessEvents (no-op) → PTick.pTicker →
//     D_Display (just calls RMain.rRenderPlayerView + IVideo) →
//     I_UpdateSound. Single-tic / TryRunTics arbitration is
//     simplified (synchronous: one tic per frame).
package doom.system;

import doom.play.PSpec;
import doom.play.PSwitch;
import doom.play.Player;
import doom.render.RData;
import doom.render.RDraw;
import doom.render.RMain;
import doom.render.RThings;
import doom.sound.ISound;
import doom.ui.IVideo;
import doom.ui.VVideo;
import doom.wad.WWad;
import doom.wad.ZZone;

import java.io.IOException;

public final class DMain {

    // d_main.c lines 92, 124–127 — wad-file list + primary WAD.
    public static final int MAXWADFILES = 20;
    public static final String[] wadfiles = new String[MAXWADFILES];
    public static int numwadfiles;

    // d_main.c line 102 — debug flag for synchronous tic mode.
    public static boolean singletics = false;

    // d_main.c lines 95–98 — command-line flags. Ported but
    // currently inert until g_game lands.
    public static boolean devparm;
    // Note: nomonsters / respawnparm / fastparm already live on
    // Doomstat — DMain just sets them during arg parsing.

    // d_main.c lines 112–115 — initial-level state.
    public static int     startskill;
    public static int     startepisode;
    public static int     startmap;
    public static boolean autostart;

    // d_main.c lines 119 / 414–416 — title-screen demo loop state.
    // The C cycles through TITLEPIC → demo1 → CREDIT → demo2 →
    // TITLEPIC/HELP2 → demo3 → [retail] demo4. Demo playback is
    // deferred (G_DeferedPlayDemo not ported); the Java port walks
    // the static-page subset of the cycle.
    public static boolean advancedemo;
    public static int     demosequence;
    public static int     pagetic;
    public static String  pagename;

    // d_main.c lines 141–143 — event ring buffer.
    public static final int MAXEVENTS = 64;
    public static final DEvent[] events = new DEvent[MAXEVENTS];
    public static int eventhead;
    public static int eventtail;

    static {
        for (int i = 0; i < MAXEVENTS; i++) {
            events[i] = new DEvent();
        }
    }

    private DMain() {
    }

    //
    // D_PostEvent
    // Called by the I/O layer (JfxDoomApp) when input is detected.
    //
    // d_main.c lines 150–154.
    public static void dPostEvent(DEvent ev) {
        DEvent slot = events[eventhead];
        slot.type = ev.type;
        slot.data1 = ev.data1;
        slot.data2 = ev.data2;
        slot.data3 = ev.data3;
        eventhead = (eventhead + 1) & (MAXEVENTS - 1);
    }

    //
    // D_ProcessEvents
    //
    // d_main.c lines 161–177. Send all pending events down the
    // responder chain (M_Responder → G_Responder; the M_Responder
    // half waits on m_menu — for now G_Responder gets everything).
    public static void dProcessEvents() {
        while (eventtail != eventhead) {
            DEvent ev = events[eventtail];
            // Responder priority (matches the C d_main.c chain):
            //   1. M_Responder — menu navigation / F-keys eat all
            //      input when the menu is up or an F-shortcut fires.
            //   2. F_Responder (during GS_FINALE) — cast call eats
            //      keypresses (forces the death frame).
            //   3. AM_Responder — automap pan / zoom / mark keys
            //      when active.
            //   4. ST_Responder — cheat decoder.
            //   5. G_Responder — gamekeydown[] for the playsim.
            if (doom.ui.MMenu.mResponder(ev)) {
                eventtail = (eventtail + 1) & (MAXEVENTS - 1);
                continue;
            }
            if (Doomstat.gamestate == Doomstat.GS_FINALE
                    && doom.ui.FFinale.fResponder(ev)) {
                eventtail = (eventtail + 1) & (MAXEVENTS - 1);
                continue;
            }
            if (doom.ui.AmMap.amResponder(ev)) {
                eventtail = (eventtail + 1) & (MAXEVENTS - 1);
                continue;
            }
            doom.ui.StStuff.stResponder(ev);
            GGame.gResponder(ev);
            eventtail = (eventtail + 1) & (MAXEVENTS - 1);
        }
    }

    //
    // D_AddFile
    //
    // d_main.c line 543. Append a WAD path to the load list.
    public static void dAddFile(String file) {
        if (numwadfiles >= MAXWADFILES) {
            ISystem.iError("Too many wadfiles");
        }
        wadfiles[numwadfiles++] = file;
    }

    //
    // D_DoomMain
    //
    // d_main.c lines 796–1170. Argument parsing + subsystem boot.
    // Returns once everything is initialized; the caller drops into
    // dDoomLoop().
    public static void dDoomMain(String[] args) throws IOException {
        // Cache arg vector for M_CheckParm-style scans.
        MArgv.myargc = args.length + 1;
        MArgv.myargv = new String[MArgv.myargc];
        MArgv.myargv[0] = "doom";
        System.arraycopy(args, 0, MArgv.myargv, 1, args.length);

        // Defaults (skill / episode / map come from m_misc; for
        // now hard-code the C defaults).
        startskill = Doomstat.sk_medium;
        startepisode = 1;
        startmap = 1;
        autostart = false;

        // Load persisted user defaults (volumes, gamma, screen
        // size, etc.) from default.cfg. Honoured before the
        // boolean flags so explicit cmdline switches override.
        MMisc.mLoadDefaults();

        // Boolean flags.
        Doomstat.nomonsters = MArgv.checkParm("-nomonsters") > 0;
        Doomstat.respawnmonsters = MArgv.checkParm("-respawn") > 0;
        Doomstat.fastparm = MArgv.checkParm("-fast") > 0;
        devparm = MArgv.checkParm("-devparm") > 0;
        if (MArgv.checkParm("-altdeath") > 0) {
            Doomstat.deathmatch = 2;
        } else if (MArgv.checkParm("-deathmatch") > 0) {
            Doomstat.deathmatch = 1;
        }

        // -iwad <path> — the primary WAD. The C uses IdentifyVersion
        // to scan a list of standard locations; the Java port
        // requires an explicit path until that arrives.
        int p = MArgv.checkParm("-iwad");
        if (p > 0 && p < MArgv.myargc - 1) {
            dAddFile(MArgv.myargv[p + 1]);
        }

        // -file <wad> [<wad> ...] — additional PWADs.
        p = MArgv.checkParm("-file");
        if (p > 0) {
            // Doomstat doesn't track modifiedgame yet — add when
            // m_misc port settles.
            int i = p + 1;
            while (i < MArgv.myargc && !MArgv.myargv[i].startsWith("-")) {
                dAddFile(MArgv.myargv[i]);
                i++;
            }
        }

        // -skill 1..5
        p = MArgv.checkParm("-skill");
        if (p > 0 && p < MArgv.myargc - 1) {
            startskill = MArgv.myargv[p + 1].charAt(0) - '1';
            autostart = true;
        }

        // -episode 1..4
        p = MArgv.checkParm("-episode");
        if (p > 0 && p < MArgv.myargc - 1) {
            startepisode = MArgv.myargv[p + 1].charAt(0) - '0';
            startmap = 1;
            autostart = true;
        }

        // -warp E M (Doom 1) or -warp M (Doom 2)
        p = MArgv.checkParm("-warp");
        if (p > 0 && p < MArgv.myargc - 1) {
            if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                startmap = Integer.parseInt(MArgv.myargv[p + 1]);
            } else if (p < MArgv.myargc - 2) {
                startepisode = MArgv.myargv[p + 1].charAt(0) - '0';
                startmap = MArgv.myargv[p + 2].charAt(0) - '0';
            }
            autostart = true;
        }

        // V_Init: allocate screens.
        VVideo.vInit();

        // Z_Init: allocation tracker.
        ZZone.zInit();

        // W_Init: load the WAD chain. wInitMultipleFiles also
        // allocates lumpcache after every wAddFile completes.
        if (numwadfiles == 0) {
            ISystem.iError("Need -iwad <path-to-doom.wad>");
        }
        // wInitMultipleFiles expects a null-terminated argv-style
        // array (the C reads until the first NULL pointer).
        String[] paths = new String[numwadfiles + 1];
        System.arraycopy(wadfiles, 0, paths, 0, numwadfiles);
        WWad.wInitMultipleFiles(paths);

        // d_main.c IdentifyVersion — the C scans for known IWAD
        // filenames (doom.wad, doom2.wad, doomu.wad, etc.) to
        // decide gamemode. WAD files get renamed in the wild, so
        // sniff the loaded lump set instead:
        //   MAP01 → commercial (Doom 2 / Final Doom)
        //   E4M1  → retail (Ultimate Doom)
        //   E2M1  → registered (Doom)
        //   else  → shareware
        if (WWad.wCheckNumForName("MAP01") >= 0) {
            Doomstat.gamemode = Doomstat.GameMode.commercial;
        } else if (WWad.wCheckNumForName("E4M1") >= 0) {
            Doomstat.gamemode = Doomstat.GameMode.retail;
        } else if (WWad.wCheckNumForName("E2M1") >= 0) {
            Doomstat.gamemode = Doomstat.GameMode.registered;
        } else {
            Doomstat.gamemode = Doomstat.GameMode.shareware;
        }

        // R_Init: textures + flats + sprites + colormap + viewangletox /
        // xtoviewangle / yslope / scalelight / zlight, calls
        // RDraw.rInitTranslationTables / RPlane.rInitPlanes /
        // RSky.rInitSkyMap.
        RData.rInitData();
        RMain.rInit();

        // P_Init: switch / animation tables + sprite-defs (from
        // info.c sprnames).
        PSwitch.pInitSwitchList();
        PSpec.pInitPicAnims();
        RThings.rInitSprites(doom.play.Info.sprnames);

        // I_Init: audio init (tactile is a no-op).
        ISystem.iInit();
        doom.sound.ISound.iInitSound();
        doom.sound.ISound.iInitMusic();

        // D_CheckNetGame: single-player no-op.
        DNet.dQuitNetGame();        // closes any leftover state

        // S_Init: allocate the 16-channel mixer + caches.
        doom.sound.SSound.sInit(doom.sound.SSound.snd_SfxVolume,
                                doom.sound.SSound.snd_MusicVolume);

        // HU_Init: load the font + initialise the message widget.
        doom.ui.HuStuff.huInit();

        // ST_Init: load status-bar graphics + allocate the BG layer.
        doom.ui.StStuff.stInit();

        // M_Init: wire the menu state machine (no patches loaded
        // until the user opens a menu — they're cached on first
        // V_DrawPatchDirect call).
        doom.ui.MMenu.mInit();

        // -record <name> arms demo recording. G_RecordDemo
        // allocates the buffer; the header lands on the next
        // G_InitNew call.
        p = MArgv.checkParm("-record");
        if (p > 0 && p < MArgv.myargc - 1) {
            GGame.gRecordDemo(MArgv.myargv[p + 1]);
            autostart = true;
        }

        // -playdemo <name> queues a demo replay (single-shot).
        p = MArgv.checkParm("-playdemo");
        if (p > 0 && p < MArgv.myargc - 1) {
            Doomstat.singledemo = true;
            GGame.gDeferedPlayDemo(MArgv.myargv[p + 1]);
        }

        // -timedemo <name> is like -playdemo but bails out to a
        // timing-info message at end (singletics + nodrawers in
        // the C). The Java port just routes to playdemo for now.
        p = MArgv.checkParm("-timedemo");
        if (p > 0 && p < MArgv.myargc - 1) {
            Doomstat.singledemo = true;
            GGame.gDeferedPlayDemo(MArgv.myargv[p + 1]);
        }

        // Auto-start a level if -warp / -episode / -skill given.
        if (autostart) {
            // Seed playeringame[0]=true so the playsim has at
            // least one active seat (D_CheckNetGame's job in the
            // C; the Java single-player port hardcodes it).
            GGame.playeringame[0] = true;
            GGame.consoleplayer = 0;

            // Queue a level load via G_InitNew → ga_loadlevel.
            // The first dDoomLoop call's gTicker pumps the
            // ga_loadlevel transition which runs gDoLoadLevel →
            // PSetup.pSetupLevel.
            GGame.gInitNew(startskill, startepisode, startmap);
            // Drain the gameaction transition now so the engine
            // is in GS_LEVEL state before the first frame.
            GGame.gTicker();
            // Wake the HUD message widget now that the player
            // exists.
            doom.ui.HuStuff.huStart();
            // Wake the status bar (initialises widgets pointing at
            // the live player state).
            doom.ui.StStuff.stStart();
        } else {
            // No -warp / -episode / -skill on the command line:
            // bring up the title-screen demo loop.
            dStartTitle();
        }
    }

    //
    // D_DoomLoop
    //
    // d_main.c lines 354–407. Per-frame driver. Loops forever in
    // the C; Java runs one iteration per call so the JavaFX shell
    // (AnimationTimer) drives cadence.
    //
    // The full C body interleaves G_Ticker / M_Ticker / G_BuildTiccmd
    // and the screen-wipe machinery. Until those land, this is the
    // skeleton: pump audio + start a frame + run one tic via
    // PTick.pTicker + render the player view + finish update.
    public static void dDoomLoop() {
        IVideo.iStartFrame();
        IVideo.iStartTic();

        // Drain queued input events (JavaFX shell posts them via
        // dPostEvent on each Scene key press / release).
        dProcessEvents();

        // Title-screen demo loop: drain the advance latch before
        // the per-tic dispatch so a page-time-out flips to the
        // next page on the same frame.
        if (advancedemo) {
            dDoAdvanceDemo();
        }

        // Single-tic: pump the playsim once via G_Ticker, which
        // builds the consoleplayer's ticcmd from gamekeydown[],
        // copies into players[].cmd, and calls P_Ticker. The C's
        // TryRunTics does multi-tic catch-up; single-player Java
        // uses the JavaFX AnimationTimer's 35 Hz schedule to keep
        // the engine tick-locked.
        GGame.gTicker();

        dDisplay();

        ISound.iUpdateSound();
        ISound.iSubmitSound();
    }

    // Screen-wipe transition state. The C blocks D_Display inside a
    // tight wipe loop pumping wipeScreenWipe + I_FinishUpdate until
    // done. Java runs at 35 Hz from the JavaFX AnimationTimer so the
    // wipe is spread across consecutive dDoomLoop calls: when a
    // gamestate transition is detected, wipeStartScreen captures
    // screens[0] before the new view paints; once the new view is
    // up, wipeEndScreen captures the target + restores the start so
    // the per-frame wipeScreenWipe can melt from start → end.
    public static boolean wipeActive;

    // Border-redraw counter. r_draw paints the FLOOR7_2 tiled border
    // into screens[1] (the "back screen"). The C copies sections of
    // screens[1] onto screens[0] for `borderdrawcount` frames after a
    // size change so the old viewport's pixels don't bleed through
    // the new smaller view — that's the "remnants of the old viewport"
    // symptom this fixes. C: static int borderdrawcount in d_main.c.
    private static int borderdrawcount;

    // Track the previous gamestate + viewheight so dDisplay can fire
    // off a one-shot back-screen fill and HUD redraw whenever either
    // transitions. C: static gamestate_t oldgamestate, static boolean
    // fullscreen in d_main.c's D_Display.
    private static int oldGameState = -1;
    private static boolean prevFullscreen;

    //
    // D_Display
    //
    // d_main.c lines 193–349. Massive routine in the C — handles
    // gamestate switch, screen-wipe machinery, HUD / automap /
    // intermission / finale drawers, status-bar redraw, view-size
    // change response.
    public static void dDisplay() {
        boolean sizeChanged = RMain.setsizeneeded;
        if (sizeChanged) {
            RMain.rExecuteSetViewSize();
            // C: oldgamestate = -1 (force background redraw) +
            // borderdrawcount = 3 — three frames of border copy so
            // the freshly-revealed status-bar / border area replaces
            // whatever the old (larger) view had drawn there.
            oldGameState = -1;
            borderdrawcount = 3;
        }

        // Detect a gamestate transition: capture the current frame
        // as the wipe's start before the new gamestate paints over
        // it. wipegamestate==-1 forces a wipe (FFinale sets that to
        // mark a transition into the static-art finale page).
        boolean startingWipe = !wipeActive
                && (Doomstat.gamestate != Doomstat.wipegamestate);
        if (startingWipe) {
            doom.ui.FWipe.wipeStartScreen(0, 0,
                    Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT);
        }

        // Top-level gamestate dispatch.
        switch (Doomstat.gamestate) {
            case Doomstat.GS_LEVEL: {
                if (doom.ui.AmMap.automapactive) {
                    // Automap replaces the player-view paint; the
                    // status bar still draws below it.
                    doom.ui.AmMap.amDrawer();
                } else {
                    Player p = GGame.players[GGame.consoleplayer];
                    if (p != null && p.mo != null) {
                        RMain.rRenderPlayerView(p);
                    }
                }
                // C: redrawsbar = wipe || (viewheight != 200 && fullscreen),
                // i.e. force a status-bar refresh when the prior frame
                // was fullscreen and we just shrank into a windowed
                // view. Without this, the HUD region is still showing
                // the old viewport's pixels.
                boolean fullscreen = RDraw.viewheight == Doomdef.SCREENHEIGHT;
                boolean redrawsbar = startingWipe
                        || (!fullscreen && prevFullscreen)
                        || sizeChanged;
                // Status bar (health / ammo / face / keys / armor).
                // Drawn before HUD overlay so the pickup-message
                // banner paints on top.
                doom.ui.StStuff.stDrawer(fullscreen, redrawsbar);
                prevFullscreen = fullscreen;
                // HUD overlay (pickup-message banner). Full HU_Drawer
                // (chat / level title) lands when those widgets port.
                doom.ui.HuStuff.huDrawer();
                break;
            }
            case Doomstat.GS_INTERMISSION:
                doom.ui.WiStuff.wiDrawer();
                break;
            case Doomstat.GS_FINALE:
                doom.ui.FFinale.fDrawer();
                break;
            case Doomstat.GS_DEMOSCREEN:
                dPageDrawer();
                break;
            default:
                break;
        }

        // Border / back-screen handling. C: when entering GS_LEVEL
        // from another gamestate, paint the FLOOR7_2 + brdr_* tile
        // pattern into screens[1] once. Then for borderdrawcount
        // frames after a size change (or whenever the view isn't
        // active), copy the border strip from screens[1] over the
        // exposed regions of screens[0] to wipe the old viewport
        // remnants.
        if (Doomstat.gamestate == Doomstat.GS_LEVEL
                && oldGameState != Doomstat.GS_LEVEL) {
            try {
                RDraw.rFillBackScreen();
            } catch (IOException ignored) {
                // Missing border lumps — degrade silently.
            }
        }
        if (Doomstat.gamestate == Doomstat.GS_LEVEL
                && !doom.ui.AmMap.automapactive
                && RDraw.scaledviewwidth != Doomdef.SCREENWIDTH) {
            if (borderdrawcount > 0) {
                RDraw.rDrawViewBorder();
                borderdrawcount--;
            }
        }
        oldGameState = Doomstat.gamestate;

        // Menu overlay always paints last so it sits on top of the
        // live view + status bar + HUD. M_Drawer no-ops when the
        // menu is closed.
        doom.ui.MMenu.mDrawer();

        // Latch the just-painted screen as the wipe's destination
        // and arm the per-frame wipe pump. wipeEndScreen restores
        // screens[0] to the original start image, so the actual
        // melted view appears on subsequent dDoomLoop calls.
        if (startingWipe) {
            doom.ui.FWipe.wipeEndScreen(0, 0,
                    Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT);
            Doomstat.wipegamestate = Doomstat.gamestate;
            wipeActive = true;
        }

        // While the wipe is running, advance it one tic per frame.
        // The C blocks D_Display in a tight inner loop; the Java
        // port slices the wipe across multiple dDoomLoop calls so
        // the AnimationTimer cadence stays 35 Hz.
        if (wipeActive) {
            int done = doom.ui.FWipe.wipeScreenWipe(
                    doom.ui.FWipe.WIPE_MELT, 0, 0,
                    Doomdef.SCREENWIDTH, Doomdef.SCREENHEIGHT, 1);
            if (done != 0) {
                wipeActive = false;
            }
            // Menu paints on top of the wipe too — matches the C
            // comment "menu is drawn even on top of wipes".
            doom.ui.MMenu.mDrawer();
        }

        IVideo.iUpdateNoBlit();
        IVideo.iFinishUpdate();
    }

    //
    // D_PageTicker
    // d_main.c lines 423–427.
    public static void dPageTicker() {
        if (--pagetic < 0) {
            dAdvanceDemo();
        }
    }

    //
    // D_PageDrawer
    // d_main.c lines 434–437.
    public static void dPageDrawer() {
        if (pagename == null) return;
        try {
            byte[] lump = doom.wad.WWad.wCacheLumpName(pagename,
                    doom.wad.ZZone.PU_CACHE);
            doom.ui.VVideo.vDrawPatch(0, 0, 0,
                    doom.render.Patch.fromLump(lump));
        } catch (java.io.IOException | ISystem.DoomError e) {
            // Page art missing — paint nothing.
        }
    }

    //
    // D_AdvanceDemo
    // d_main.c lines 444–447. Just latches the flag; the loop driver
    // calls dDoAdvanceDemo on the next tick.
    public static void dAdvanceDemo() {
        advancedemo = true;
    }

    //
    // D_DoAdvanceDemo
    // d_main.c lines 454–518. Cycle through the demo sequence.
    // Demo playback (G_DeferedPlayDemo) is deferred — the Java port
    // skips the demo cases and pumps straight on to the next page.
    public static void dDoAdvanceDemo() {
        GGame.players[GGame.consoleplayer].playerstate = doom.play.Player.PST_LIVE;
        advancedemo = false;
        Doomstat.usergame = false;
        Doomstat.paused = false;
        GGame.gameaction = GGame.ga_nothing;

        int wrap = (Doomstat.gamemode == Doomstat.GameMode.retail) ? 7 : 6;
        demosequence = (demosequence + 1) % wrap;

        switch (demosequence) {
            case 0:
                pagetic = (Doomstat.gamemode == Doomstat.GameMode.commercial)
                        ? 35 * 11 : 170;
                Doomstat.gamestate = Doomstat.GS_DEMOSCREEN;
                pagename = "TITLEPIC";
                if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                    doom.sound.SSound.sStartMusic(doom.sound.Sounds.mus_dm2ttl);
                } else {
                    doom.sound.SSound.sStartMusic(doom.sound.Sounds.mus_intro);
                }
                break;
            case 1:
                GGame.gDeferedPlayDemo("demo1");
                break;
            case 2:
                pagetic = 200;
                Doomstat.gamestate = Doomstat.GS_DEMOSCREEN;
                pagename = "CREDIT";
                break;
            case 3:
                GGame.gDeferedPlayDemo("demo2");
                break;
            case 4:
                Doomstat.gamestate = Doomstat.GS_DEMOSCREEN;
                if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                    pagetic = 35 * 11;
                    pagename = "TITLEPIC";
                    doom.sound.SSound.sStartMusic(doom.sound.Sounds.mus_dm2ttl);
                } else {
                    pagetic = 200;
                    pagename = (Doomstat.gamemode == Doomstat.GameMode.retail)
                            ? "CREDIT" : "HELP2";
                }
                break;
            case 5:
                GGame.gDeferedPlayDemo("demo3");
                break;
            case 6:
                // Retail Ultimate Doom adds a fourth demo.
                GGame.gDeferedPlayDemo("demo4");
                break;
            default:
                break;
        }
    }

    //
    // D_StartTitle
    // d_main.c lines 525–530.
    public static void dStartTitle() {
        GGame.gameaction = GGame.ga_nothing;
        demosequence = -1;
        dAdvanceDemo();
    }
}
