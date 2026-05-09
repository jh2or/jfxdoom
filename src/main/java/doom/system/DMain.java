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

import doom.play.PSetup;
import doom.play.PSpec;
import doom.play.PSwitch;
import doom.play.PTick;
import doom.play.Player;
import doom.render.RData;
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

    // d_main.c line 119 — title-screen demo flag.
    public static boolean advancedemo;

    private DMain() {
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

        // I_Init: audio init (deferred), tactile (no-op).
        ISystem.iInit();

        // D_CheckNetGame: single-player no-op.
        DNet.dQuitNetGame();        // closes any leftover state

        // S_Init: sound channel allocation. Deferred (SSound is a
        // stub).

        // HU_Init / ST_Init: HUD + status bar. Deferred until
        // those modules port.

        // Auto-start a level if -warp / -episode / -skill given.
        if (autostart) {
            int lumpnum;
            if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                lumpnum = WWad.wGetNumForName(String.format("MAP%02d", startmap));
            } else {
                lumpnum = WWad.wGetNumForName(
                        String.format("E%dM%d", startepisode, startmap));
            }
            Doomstat.gamemap = startmap;
            Doomstat.gameepisode = startepisode;
            Doomstat.gameskill = startskill;
            // Seed playeringame[0]=true so the playsim has at
            // least one active seat (D_CheckNetGame's job in the
            // C; the Java single-player port hardcodes it).
            GGame.playeringame[0] = true;
            GGame.consoleplayer = 0;

            // P_SetupLevel (geometry + things). Full P_SetupLevel
            // would also call S_Start, R_PrecacheLevel,
            // G_DeathMatchSpawnPlayer; deferred.
            PSetup.pSetupLevelGeometry(lumpnum);
            PSetup.pLoadThings(lumpnum + PSetup.ML_THINGS);
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

        // dProcessEvents() — deferred (needs M_Responder / G_Responder).

        // Single-tic: just pump the playsim once. The C's TryRunTics
        // does multi-tic catch-up; single-player Java uses the
        // JavaFX AnimationTimer's 35Hz schedule to keep the engine
        // tick-locked.
        PTick.pTicker();

        dDisplay();

        ISound.iUpdateSound();
        ISound.iSubmitSound();
    }

    //
    // D_Display
    //
    // d_main.c lines 193–349. Massive routine in the C — handles
    // gamestate switch, screen-wipe machinery, HUD / automap /
    // intermission / finale drawers, status-bar redraw, view-size
    // change response. The Java port currently dispatches the
    // simplest path: render the player view if a player has been
    // spawned.
    public static void dDisplay() {
        if (RMain.setsizeneeded) {
            RMain.rExecuteSetViewSize();
        }

        Player p = GGame.players[GGame.consoleplayer];
        if (p != null && p.mo != null) {
            RMain.rRenderPlayerView(p);
        }

        IVideo.iUpdateNoBlit();
        IVideo.iFinishUpdate();
    }
}
