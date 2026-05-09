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

    // g_game.c lines 38–54 — gameaction_t.
    // The full enum has more entries (ga_loadlevel, ga_savegame, ...);
    // only the two values flipped by the EV_ exit dispatchers live here
    // until the surrounding game-loop machinery ports.
    public static final int ga_nothing      = 0;
    public static final int ga_completed    = 1;
    public static final int ga_secretexit   = 2;

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

    //
    // G_Ticker
    //
    // g_game.c lines 605–748. Per-tic dispatcher. The C handles
    // gameaction transitions (ga_loadlevel / ga_completed / etc.)
    // before reading per-player ticcmds; the Java port skips that
    // until G_DoLoadLevel etc. port. Builds the consoleplayer's
    // ticcmd via gBuildTiccmd then pumps PTick.pTicker.
    public static void gTicker() {
        // Build the local seat's ticcmd from input state and copy
        // into players[consoleplayer].cmd. Other seats' ticcmds
        // would arrive via netcmds[] in multiplayer — single-player
        // skips that.
        if (playeringame[consoleplayer]) {
            gBuildTiccmd(players[consoleplayer].cmd);
        }

        // Special-button latches: BT_SPECIAL clears via P_PlayerThink,
        // but the pause / save dispatch lives here.
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            if (!playeringame[i]) continue;
            if ((players[i].cmd.buttons & Doomdef.BT_SPECIAL) != 0) {
                int sub = players[i].cmd.buttons & Doomdef.BT_SPECIALMASK;
                if (sub == Doomdef.BTS_PAUSE) {
                    Doomstat.paused = !Doomstat.paused;
                }
                // BTS_SAVEGAME deferred (G_DoSaveGame not in scope)
            }
        }

        // The C dispatches based on gamestate (GS_LEVEL → P_Ticker,
        // GS_INTERMISSION → WI_Ticker, etc.). Without gamestate
        // wiring (waits on wi_stuff / f_finale), the Java port
        // always pumps the playsim — the only state currently
        // implemented.
        PTick.pTicker();
        PTick.gametic++;
    }

    private GGame() {
    }
}
