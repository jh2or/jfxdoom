// Port of linuxdoom-1.10/g_game.c — partial.
//
// What this carries:
//   - Player roster globals (playeringame[], players[], consoleplayer)
//   - Level-exit latches (G_ExitLevel / G_SecretExitLevel,
//     gameaction enum + ga_completed transition)
//   - Stubbed G_PlayerReborn (full body needs ammo[]/weaponowned[]
//     restore that p_inter has nearly satisfied)
//   - G_Responder (input event → gamekeydown[] / gameaction transitions)
//   - G_BuildTiccmd (gamekeydown[] → ticcmd_t scaling tables)
//   - G_Ticker (per-tic dispatch — copies ticcmd into players[i].cmd
//     and calls P_Ticker; full body's intermission / finale / demo
//     branches stay deferred until those modules port)
//
// Deferred (waits on its named module):
//   - G_DoCompleted / G_DoLoadGame / G_DoSaveGame / G_DoNewGame /
//     G_DoPlayDemo / G_DeferedPlayDemo / G_TimeDemo
//   - G_RecordDemo / G_BeginRecording / demo read/write
//   - G_DeathMatchSpawnPlayer / G_DoReborn (waits on full G_InitNew)
//   - Mouse / joystick branches in G_BuildTiccmd
package doom.system;

import doom.play.Player;
import doom.play.PTick;
import doom.play.Ticcmd;

public final class GGame {

    // g_game.c lines 118–121 — player roster globals.
    // playeringame[] is the in-game flag set by D_DoomMain (or by
    // demo loaders); players[] holds each player's persistent state
    // across levels; consoleplayer is the local seat number.
    // All four MAXPLAYERS slots get a Player so the array is safely
    // indexable before any spawn — the C zero-initializes them.
    public static final boolean[] playeringame = new boolean[Doomdef.MAXPLAYERS];
    public static final Player[] players = new Player[Doomdef.MAXPLAYERS];
    public static int consoleplayer;

    static {
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            players[i] = new Player();
        }
    }

    // g_game.c lines 38–54 — gameaction_t. The full enum.
    public static final int ga_nothing      = 0;
    public static final int ga_loadlevel    = 1;
    public static final int ga_newgame      = 2;
    public static final int ga_loadgame     = 3;
    public static final int ga_savegame     = 4;
    public static final int ga_playdemo     = 5;
    public static final int ga_completed    = 6;
    public static final int ga_victory      = 7;
    public static final int ga_worlddone    = 8;
    public static final int ga_screenshot   = 9;
    // Aliased back into ga_completed by G_Ticker; PSwitch /
    // PSpec dispatchers latch it via gSecretExitLevel.
    public static final int ga_secretexit   = 10;

    // g_game.c — gameaction latch. G_DoCompleted reads it to choose
    // between normal-exit and secret-exit transitions.
    public static int gameaction = ga_nothing;

    // g_game.c — secret-exit flag (set by G_SecretExitLevel).
    public static int secretexit;

    // g_game.c — G_ExitLevel.
    // Sets gameaction = ga_completed; the next G_Ticker pumps the
    // intermission. Stub: we just flip the latch.
    public static void gExitLevel() {
        secretexit = 0;
        gameaction = ga_completed;
    }

    // g_game.c — G_SecretExitLevel.
    public static void gSecretExitLevel() {
        // The C clears `secretexit` first then conditionally re-enables
        // it when the IWAD has the secret level. Without DMain to know
        // the IWAD, we just set the flag and trust the call site.
        secretexit = 1;
        gameaction = ga_completed;
    }

    // g_game.c lines 800–841 — G_PlayerReborn.
    // Stubbed: the full body memsets player_t and re-applies starting
    // ammo/weapons/health, which needs the ammo[]/weaponowned[]/
    // readyweapon/maxammo fields (none ported until p_inter / p_user).
    // For the current set of consumers (P_SpawnPlayer's PST_REBORN
    // branch) we just flip playerstate back to LIVE so subsequent
    // ticks behave as if the player respawned cleanly. The ammo/weapon
    // restore comes online when those fields land.
    public static void gPlayerReborn(int player) {
        Player p = players[player];
        p.playerstate = Player.PST_LIVE;
        p.health = 100; // doomdef.h MAXHEALTH
    }

    //
    // -------- input → ticcmd plumbing --------
    //

    // g_game.c — gamekeydown[NUMKEYS]. Tracks per-key held state.
    // Set true on EV_KEYDOWN, cleared on EV_KEYUP.
    public static final boolean[] gamekeydown = new boolean[Doomdef.NUMKEYS];

    // g_game.c — keymap. The C uses globals so M_Menu can rebind;
    // the Java port hard-codes WASD-friendly defaults via the
    // keycodes Doomdef carries (KEY_LEFTARROW etc.).
    public static int keyRight      = Doomdef.KEY_RIGHTARROW;
    public static int keyLeft       = Doomdef.KEY_LEFTARROW;
    public static int keyUp         = Doomdef.KEY_UPARROW;
    public static int keyDown       = Doomdef.KEY_DOWNARROW;
    public static int keyStrafeleft = Doomdef.KEY_COMMA;
    public static int keyStraferight = Doomdef.KEY_PERIOD;
    public static int keyFire       = Doomdef.KEY_RCTRL;
    public static int keyUse        = Doomdef.KEY_SPACE;
    public static int keyStrafe     = Doomdef.KEY_RALT;
    public static int keySpeed      = Doomdef.KEY_RSHIFT;

    // g_game.c lines 105–107 — movement scaling tables.
    // forwardmove / sidemove indexed by [walk=0, run=1].
    // angleturn indexed by [slow=0, normal=1, fast=2].
    public static final int[] forwardmove = { 0x19, 0x32 };
    public static final int[] sidemove    = { 0x18, 0x28 };
    public static final int[] angleturn   = { 640, 1280, 320 };

    // g_game.c — MAXPLMOVE / SLOWTURNTICS.
    public static final int MAXPLMOVE    = 0x32;
    public static final int SLOWTURNTICS = 6;

    // g_game.c — turn-acceleration counter. Bumped each tic the
    // turn key is held; resets when released. < SLOWTURNTICS uses
    // the slow-turn (tspeed=2) rate.
    public static int turnheld;

    // g_game.c — pause / save-game special-button latches. Set by
    // G_Responder (BTS_PAUSE on KEY_PAUSE, BTS_SAVEGAME on save
    // request); G_BuildTiccmd flips them into the next ticcmd.
    public static boolean sendpause;
    public static boolean sendsave;
    public static int     savegameslot;

    //
    // G_Responder
    //
    // g_game.c lines 504–597. The full body's spy-mode / demo-eats /
    // M_Responder / HU_Responder / ST_Responder / AM_Responder /
    // F_Responder branches stay deferred until those modules port;
    // the keydown/keyup paths land verbatim.
    public static boolean gResponder(DEvent ev) {
        switch (ev.type) {
            case DEvent.EV_KEYDOWN:
                if (ev.data1 == Doomdef.KEY_PAUSE) {
                    sendpause = true;
                    return true;
                }
                if (ev.data1 < Doomdef.NUMKEYS) {
                    gamekeydown[ev.data1] = true;
                }
                return true;       // eat key down events

            case DEvent.EV_KEYUP:
                if (ev.data1 < Doomdef.NUMKEYS) {
                    gamekeydown[ev.data1] = false;
                }
                return false;      // always let key up events filter down

            // EV_MOUSE / EV_JOYSTICK branches deferred — the JavaFX
            // shell only posts keyboard events for now.
            default:
                return false;
        }
    }

    //
    // G_BuildTiccmd
    //
    // g_game.c lines 237–437. Reads gamekeydown[] / sendpause /
    // sendsave + the per-tic turn accelerator and emits a fresh
    // ticcmd_t. Mouse / joystick / chainsaw-override / chat-char /
    // double-click branches deferred.
    public static void gBuildTiccmd(Ticcmd cmd) {
        boolean strafe;
        int speed;
        int tspeed;
        int forward;
        int side;

        // Reset cmd to the empty base.
        Ticcmd base = ISystem.iBaseTiccmd();
        cmd.forwardmove = base.forwardmove;
        cmd.sidemove = base.sidemove;
        cmd.angleturn = base.angleturn;
        cmd.consistancy = base.consistancy;
        cmd.chatchar = base.chatchar;
        cmd.buttons = base.buttons;

        strafe = gamekeydown[keyStrafe];
        speed  = gamekeydown[keySpeed] ? 1 : 0;

        forward = side = 0;

        // two-stage accelerative turning
        if (gamekeydown[keyRight] || gamekeydown[keyLeft]) {
            turnheld++;
        } else {
            turnheld = 0;
        }

        if (turnheld < SLOWTURNTICS) {
            tspeed = 2;             // slow turn
        } else {
            tspeed = speed;
        }

        // movement keys cancel each other out
        if (strafe) {
            if (gamekeydown[keyRight]) {
                side += sidemove[speed];
            }
            if (gamekeydown[keyLeft]) {
                side -= sidemove[speed];
            }
        } else {
            if (gamekeydown[keyRight]) {
                cmd.angleturn -= angleturn[tspeed];
            }
            if (gamekeydown[keyLeft]) {
                cmd.angleturn += angleturn[tspeed];
            }
        }

        if (gamekeydown[keyUp]) {
            forward += forwardmove[speed];
        }
        if (gamekeydown[keyDown]) {
            forward -= forwardmove[speed];
        }
        if (gamekeydown[keyStraferight]) {
            side += sidemove[speed];
        }
        if (gamekeydown[keyStrafeleft]) {
            side -= sidemove[speed];
        }

        // buttons
        if (gamekeydown[keyFire]) {
            cmd.buttons |= Doomdef.BT_ATTACK;
        }
        if (gamekeydown[keyUse]) {
            cmd.buttons |= Doomdef.BT_USE;
        }

        // chainsaw / weapon-change overrides — gamekeydown['1'+i].
        for (int i = 0; i < Doomdef.NUMWEAPONS - 1; i++) {
            if (gamekeydown['1' + i]) {
                cmd.buttons |= Doomdef.BT_CHANGE;
                cmd.buttons |= i << Doomdef.BT_WEAPONSHIFT;
                break;
            }
        }

        // clamp move scalars
        if (forward > MAXPLMOVE) {
            forward = MAXPLMOVE;
        } else if (forward < -MAXPLMOVE) {
            forward = -MAXPLMOVE;
        }
        if (side > MAXPLMOVE) {
            side = MAXPLMOVE;
        } else if (side < -MAXPLMOVE) {
            side = -MAXPLMOVE;
        }

        cmd.forwardmove += forward;
        cmd.sidemove += side;

        // special buttons
        if (sendpause) {
            sendpause = false;
            cmd.buttons = Doomdef.BT_SPECIAL | Doomdef.BTS_PAUSE;
        }
        if (sendsave) {
            sendsave = false;
            cmd.buttons = Doomdef.BT_SPECIAL | Doomdef.BTS_SAVEGAME
                    | (savegameslot << Doomdef.BTS_SAVESHIFT);
        }
    }

    // g_game.c — secret-exit + level-completion latches the
    // gameaction switch reads. secretexit is already declared
    // above; ga_completed shortcuts to ga_loadlevel until the
    // intermission (wi_stuff) ports.
    public static int startepisode = 1;
    public static int startmap     = 1;
    public static int startskill   = Doomstat.sk_medium;

    // g_game.c lines 1328–1330 — deferred-init parameters latched by
    // G_DeferedInitNew and consumed by G_DoNewGame on the next tic.
    public static int dSkill   = Doomstat.sk_medium;
    public static int dEpisode = 1;
    public static int dMap     = 1;

    //
    // G_DeferedInitNew
    //
    // g_game.c lines 1332–1342. Latches the requested start state and
    // sets gameaction = ga_newgame so gTicker fires gDoNewGame on the
    // next pass. Used by ST_Responder's `idclev` cheat.
    public static void gDeferedInitNew(int skill, int episode, int map) {
        dSkill     = skill;
        dEpisode   = episode;
        dMap       = map;
        gameaction = ga_newgame;
    }

    // g_game.c — ga_loadgame / ga_savegame deferred parameters
    // (filename + slot description). Latched by gLoadGame /
    // gSaveGame and consumed by gDoLoadGame / gDoSaveGame on the
    // next tic.
    public static String saveName;    // savegame file path
    public static int    saveSlot;
    public static String saveDescription;

    /**
     * G_LoadGame — queues a save-restore from `name`.
     * g_game.c lines 1206–1213.
     */
    public static void gLoadGame(String name) {
        saveName = name;
        gameaction = ga_loadgame;
    }

    /**
     * G_SaveGame — queues a save-write to `slot` with `description`.
     * g_game.c lines 1262–1269.
     */
    public static void gSaveGame(int slot, String description) {
        saveSlot = slot;
        saveDescription = description;
        gameaction = ga_savegame;
    }

    /**
     * G_DoSaveGame — actually writes the save file. Run from
     * gTicker's ga_savegame transition. Java port uses
     * `PSaveg.save()` + a length-prefixed file format: first byte
     * is the description length, then the description, then the
     * PSaveg byte stream.
     */
    public static void gDoSaveGame() {
        try {
            byte[] body = doom.play.PSaveg.save();
            byte[] desc = saveDescription == null
                    ? new byte[0]
                    : saveDescription.getBytes("UTF-8");
            byte[] out = new byte[1 + desc.length + body.length];
            out[0] = (byte) Math.min(255, desc.length);
            System.arraycopy(desc, 0, out, 1, out[0] & 0xff);
            System.arraycopy(body, 0, out, 1 + (out[0] & 0xff), body.length);
            java.nio.file.Files.write(java.nio.file.Paths.get(saveName), out);
            players[consoleplayer].message = "game saved.";
        } catch (java.io.IOException e) {
            players[consoleplayer].message = "save failed: " + e.getMessage();
        }
        gameaction = ga_nothing;
    }

    /**
     * G_DoLoadGame — actually reads the save file. Run from gTicker's
     * ga_loadgame transition. Strips the description prefix, then
     * hands the body to PSaveg.load().
     */
    public static void gDoLoadGame() {
        try {
            byte[] all = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(saveName));
            int descLen = all[0] & 0xff;
            byte[] body = new byte[all.length - 1 - descLen];
            System.arraycopy(all, 1 + descLen, body, 0, body.length);
            doom.play.PSaveg.load(body);
            players[consoleplayer].message = "game loaded.";
        } catch (java.io.IOException e) {
            players[consoleplayer].message = "load failed: " + e.getMessage();
        }
        gameaction = ga_nothing;
    }

    /**
     * G_ReadSaveFileDescription — peek the description prefix of a
     * save file without actually loading the body. Used by the menu
     * to populate the load-game slot strings.
     */
    public static String gReadSaveFileDescription(String path) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(path);
            if (!java.nio.file.Files.exists(p)) return null;
            byte[] header = java.nio.file.Files.readAllBytes(p);
            if (header.length < 1) return null;
            int descLen = header[0] & 0xff;
            if (header.length < 1 + descLen) return null;
            return new String(header, 1, descLen, "UTF-8");
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * G_ScreenShot — stub. g_game.c line 970.
     */
    public static void gScreenShot() {
        gameaction = ga_screenshot;
    }

    //
    // G_DoNewGame
    //
    // g_game.c lines 1345–1358. Tears down netgame / deathmatch /
    // demo flags, restores single-player defaults, then calls
    // G_InitNew with the deferred parameters.
    public static void gDoNewGame() {
        // demoplayback / netdemo deferred (demo system not ported).
        Doomstat.netgame    = false;
        Doomstat.deathmatch = 0;
        playeringame[1] = playeringame[2] = playeringame[3] = false;
        // The C separately clears `respawnparm` (the -respawn cmdline
        // flag) and `respawnmonsters` (which gInitNew re-derives from
        // skill). The Java port only carries respawnmonsters; gInitNew
        // overwrites it below so no clear here is needed.
        Doomstat.respawnmonsters = false;
        Doomstat.fastparm        = false;
        Doomstat.nomonsters      = false;
        consoleplayer = 0;
        gInitNew(dSkill, dEpisode, dMap);
        gameaction = ga_nothing;
    }

    //
    // G_InitNew
    //
    // g_game.c lines 1393–1469. Queues a level load. Caller sets
    // skill + episode + map; G_Ticker picks ga_loadlevel up on the
    // next tic.
    public static void gInitNew(int skill, int episode, int map) {
        Doomstat.gameskill = skill;
        Doomstat.gameepisode = episode;
        Doomstat.gamemap = map;
        startskill = skill;
        startepisode = episode;
        startmap = map;
        Doomstat.respawnmonsters = (skill == Doomstat.sk_nightmare);
        gameaction = ga_loadlevel;
    }

    //
    // G_DoLoadLevel
    //
    // g_game.c lines 445–497. Sets the per-map sky, flips dead
    // players to PST_REBORN, calls P_SetupLevel, resets per-tic
    // input state.
    public static void gDoLoadLevel() {
        // Sky-flat number — looked up by name (lump "F_SKY1").
        doom.render.RSky.skyflatnum = doom.render.RData.rFlatNumForName(
                doom.render.RSky.SKYFLATNAME);

        // Pick the sky texture per (gamemode, episode/map).
        // Commercial / tnt / plut use SKY1 / SKY2 / SKY3 bands; for
        // now the port handles commercial + the Doom 1 episodes
        // (1=SKY1, 2=SKY2, 3=SKY3, 4=SKY4 in Ultimate Doom).
        String skyname;
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            if (Doomstat.gamemap < 12) {
                skyname = "SKY1";
            } else if (Doomstat.gamemap < 21) {
                skyname = "SKY2";
            } else {
                skyname = "SKY3";
            }
        } else {
            // Doom 1: episode 1 → SKY1, episode 2 → SKY2, etc.
            skyname = "SKY" + Doomstat.gameepisode;
        }
        doom.render.RSky.skytexture =
                doom.render.RData.rTextureNumForName(skyname);

        // Flip dead players to PST_REBORN; reset frags.
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (playeringame[i] && players[i].playerstate == Player.PST_DEAD) {
                players[i].playerstate = Player.PST_REBORN;
            }
            for (int j = 0; j < Doomdef.MAXPLAYERS; j++) {
                players[i].frags[j] = 0;
            }
        }

        try {
            doom.play.PSetup.pSetupLevel(Doomstat.gameepisode,
                    Doomstat.gamemap, 0, Doomstat.gameskill);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        gameaction = ga_nothing;
        Doomstat.gamestate  = Doomstat.GS_LEVEL;
        Doomstat.usergame   = true;
        Doomstat.viewactive = true;

        // S_Start — kill any leftover SFX channels and queue the
        // per-map music.
        doom.sound.SSound.sStart();

        // Clear per-tic input state so a held key from the previous
        // level doesn't carry over.
        for (int i = 0; i < Doomdef.NUMKEYS; i++) {
            gamekeydown[i] = false;
        }
        sendpause = sendsave = false;
        Doomstat.paused = false;
    }

    // g_game.c lines 977–984 — DOOM 1 par-time table (seconds /
    // episode / map). Index 0 of each sub-array is dead (par tables
    // index from 1; the C source includes the leading 0 too).
    public static final int[][] pars = {
            { 0 },
            { 0, 30,  75, 120,  90, 165, 180, 180,  30, 165 },
            { 0, 90,  90,  90, 120,  90, 360, 240,  30, 170 },
            { 0, 90,  45,  90, 150,  90,  90, 165,  30, 135 },
    };

    // g_game.c lines 987–993 — DOOM 2 par-time table.
    public static final int[] cpars = {
             30,  90, 120, 120,  90, 150, 120, 120, 270,  90,  //  1-10
            210, 150, 150, 150, 210, 150, 420, 150, 210, 150,  // 11-20
            240, 150, 180, 150, 150, 300, 330, 420, 300, 180,  // 21-30
            120,  30                                            // 31-32
    };

    // g_game.c — single shared wminfo passed to WI_Start. The C
    // declares it file-static; the Java port keeps the same shape so
    // the C-style `&wminfo` pointer pattern translates cleanly.
    public static final doom.ui.Wbstartstruct wminfo = new doom.ui.Wbstartstruct();

    //
    // G_DoCompleted
    //
    // g_game.c lines 1020–1141. Full intermission hand-off: snapshot
    // each player's stats into wminfo, work out `last` / `next` map
    // (with secret-level routing for both Doom 1 and Doom 2), latch
    // gamestate=GS_INTERMISSION, call WI_Start.
    public static void gDoCompleted() {
        int i;
        gameaction = ga_nothing;

        // G_PlayerFinishLevel: take away cards / powers / message.
        // Deferred — needs the full G_PlayerFinishLevel port. The
        // intermission doesn't read those fields, so skip for now.

        if (doom.ui.AmMap.automapactive) {
            doom.ui.AmMap.amStop();
        }

        // Doom 1 end-of-episode victory short-circuit (map 8 → cast
        // call / finale, map 9 → mark secret-cleared).
        if (Doomstat.gamemode != Doomstat.GameMode.commercial) {
            if (Doomstat.gamemap == 8) {
                gameaction = ga_victory;
                return;
            }
            if (Doomstat.gamemap == 9) {
                for (i = 0; i < Doomdef.MAXPLAYERS; i++) {
                    players[i].didsecret = true;
                }
            }
        }

        wminfo.didsecret = players[consoleplayer].didsecret;
        wminfo.epsd      = Doomstat.gameepisode - 1;
        wminfo.last      = Doomstat.gamemap - 1;

        // wminfo.next is 0-based (gamemap is 1-based).
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            if (secretexit != 0) {
                switch (Doomstat.gamemap) {
                    case 15: wminfo.next = 30; break;
                    case 31: wminfo.next = 31; break;
                    default: wminfo.next = Doomstat.gamemap; break;
                }
            } else {
                switch (Doomstat.gamemap) {
                    case 31:
                    case 32: wminfo.next = 15; break;
                    default: wminfo.next = Doomstat.gamemap; break;
                }
            }
        } else {
            if (secretexit != 0) {
                wminfo.next = 8;  // go to secret level
            } else if (Doomstat.gamemap == 9) {
                // returning from secret level
                switch (Doomstat.gameepisode) {
                    case 1: wminfo.next = 3; break;
                    case 2: wminfo.next = 5; break;
                    case 3: wminfo.next = 6; break;
                    case 4: wminfo.next = 2; break;
                    default: break;
                }
            } else {
                wminfo.next = Doomstat.gamemap;
            }
        }

        wminfo.maxkills  = Doomstat.totalkills;
        wminfo.maxitems  = Doomstat.totalitems;
        wminfo.maxsecret = Doomstat.totalsecret;
        wminfo.maxfrags  = 0;
        if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            wminfo.partime = 35 * cpars[Doomstat.gamemap - 1];
        } else {
            wminfo.partime = 35 * pars[Doomstat.gameepisode][Doomstat.gamemap];
        }
        wminfo.pnum = consoleplayer;

        for (i = 0; i < Doomdef.MAXPLAYERS; i++) {
            wminfo.plyr[i].in      = playeringame[i];
            wminfo.plyr[i].skills  = players[i].killcount;
            wminfo.plyr[i].sitems  = players[i].itemcount;
            wminfo.plyr[i].ssecret = players[i].secretcount;
            wminfo.plyr[i].stime   = doom.play.PTick.leveltime;
            System.arraycopy(players[i].frags, 0,
                    wminfo.plyr[i].frags, 0, Doomdef.MAXPLAYERS);
        }

        Doomstat.gamestate  = Doomstat.GS_INTERMISSION;
        Doomstat.viewactive = false;
        doom.ui.AmMap.automapactive = false;
        doom.ui.WiStuff.wiStart(wminfo);
    }

    //
    // G_Ticker
    //
    // g_game.c lines 605–748. Per-tic dispatcher with full
    // gameaction transition switch.
    public static void gTicker() {
        // Process pending game-action transitions before the tick.
        while (gameaction != ga_nothing) {
            switch (gameaction) {
                case ga_loadlevel:
                    gDoLoadLevel();
                    break;
                case ga_completed:
                    gDoCompleted();
                    break;
                case ga_victory:
                    // F_StartFinale flips gamestate=GS_FINALE and
                    // clears gameaction itself.
                    doom.ui.FFinale.fStartFinale();
                    break;
                case ga_secretexit:
                    secretexit = 1;
                    gameaction = ga_completed;
                    break;
                case ga_newgame:
                    gDoNewGame();
                    break;
                case ga_worlddone:
                    // g_game.c lines 1147–1185: G_WorldDone advances
                    // gamemap/episode using the same routing wminfo
                    // computed (secretexit → secret level; secret
                    // exit → main path). The full C also fires F_StartFinale
                    // for Doom 2 cutscene maps (6, 11, 20, 30, 15, 31)
                    // — handled here so commercial post-map cutscenes
                    // still play after the intermission.
                    gameaction = ga_nothing;
                    Doomstat.gamestate = Doomstat.GS_LEVEL;
                    if (secretexit != 0) {
                        players[consoleplayer].didsecret = true;
                    }
                    if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
                        switch (Doomstat.gamemap) {
                            case 15:
                            case 31:
                                if (secretexit == 0) break;
                                /* fall through */
                            case 6:
                            case 11:
                            case 20:
                            case 30:
                                doom.ui.FFinale.fStartFinale();
                                return;
                            default:
                                break;
                        }
                    }
                    // Apply the (already-computed) wminfo.next +
                    // wminfo.epsd back onto gameepisode / gamemap (the
                    // intermission used 0-based values; gInitNew
                    // expects 1-based). gInitNew also resets
                    // secretexit indirectly via gameaction.
                    Doomstat.gameepisode = wminfo.epsd + 1;
                    Doomstat.gamemap     = wminfo.next + 1;
                    secretexit = 0;
                    gameaction = ga_loadlevel;
                    break;
                case ga_loadgame:
                    gDoLoadGame();
                    break;
                case ga_savegame:
                    gDoSaveGame();
                    break;
                case ga_playdemo:
                case ga_screenshot:
                    // ga_screenshot / demo playback waiting on more
                    // dependent ports.
                    gameaction = ga_nothing;
                    break;
                default:
                    gameaction = ga_nothing;
                    break;
            }
        }

        // Build the local seat's ticcmd from input state and copy
        // into players[consoleplayer].cmd. Other seats' ticcmds
        // would arrive via netcmds[] in multiplayer — single-player
        // skips that.
        if (playeringame[consoleplayer]) {
            gBuildTiccmd(players[consoleplayer].cmd);
        }

        // Special-button latches.
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (!playeringame[i]) continue;
            if ((players[i].cmd.buttons & Doomdef.BT_SPECIAL) != 0) {
                int sub = players[i].cmd.buttons & Doomdef.BT_SPECIALMASK;
                if (sub == Doomdef.BTS_PAUSE) {
                    Doomstat.paused = !Doomstat.paused;
                    if (Doomstat.paused) {
                        doom.sound.SSound.sPauseSound();
                    } else {
                        doom.sound.SSound.sResumeSound();
                    }
                }
                // BTS_SAVEGAME deferred (G_DoSaveGame not in scope)
            }
        }

        // Gamestate-based per-tic dispatch. GS_INTERMISSION still
        // deferred (wi_stuff not ported); the level → finale path
        // skips it. AM_Ticker also deferred.
        switch (Doomstat.gamestate) {
            case Doomstat.GS_LEVEL:
                PTick.pTicker();
                // ST_Ticker drives the face animation, palette flash
                // counter, and per-tic widget-state refresh.
                // HU_Ticker drains player.message into the SText
                // widget + ticks the message timeout. AM_Ticker
                // updates the pan / zoom / follow-player state when
                // the automap is showing. M_Ticker animates the
                // menu skull cursor. S_UpdateSounds re-runs the
                // per-channel distance / pan attenuation against
                // the consoleplayer's current position.
                doom.ui.StStuff.stTicker();
                doom.ui.HuStuff.huTicker();
                doom.ui.AmMap.amTicker();
                doom.ui.MMenu.mTicker();
                doom.sound.SSound.sUpdateSounds(
                        players[consoleplayer].mo);
                break;
            case Doomstat.GS_INTERMISSION:
                doom.ui.WiStuff.wiTicker();
                break;
            case Doomstat.GS_FINALE:
                doom.ui.FFinale.fTicker();
                break;
            case Doomstat.GS_DEMOSCREEN:
                doom.system.DMain.dPageTicker();
                break;
            default:
                break;
        }
        PTick.gametic++;
    }

    private GGame() {
    }
}
