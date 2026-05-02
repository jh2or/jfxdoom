// Port of linuxdoom-1.10/g_game.c — stub.
//
// g_game.c is the ~2200-line top-level game-loop coordinator: G_Ticker
// (per-frame event dispatch), G_Responder (input → cmd_t), G_PlayerReborn,
// G_DoLoadLevel, G_BuildTiccmd, demo record/playback, save/load, and
// the level-exit / game-end transitions. Most of it sits on top of
// p_setup, p_user, m_menu and the platform input layer — none ported.
//
// This stub carries the two level-exit hooks consumed by the EV_
// trigger dispatchers in p_switch.c / p_spec.c, plus the cross-module
// player-roster globals (`playeringame[]`, `players[]`, `consoleplayer`)
// that p_setup / p_mobj read when spawning, plus a no-op
// `gPlayerReborn` so P_SpawnPlayer can dispatch through it.
package doom.system;

import doom.play.Player;

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

    private GGame() {
    }
}
