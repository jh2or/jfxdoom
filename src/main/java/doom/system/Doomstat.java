// Port of linuxdoom-1.10/doomstat.h — cross-module game-state globals.
// Only the fields other ported modules read are populated here; the rest
// land as their owning module gets ported.
package doom.system;

public final class Doomstat {

    // GameMode_t (doomdef.h lines 38–47).
    public enum GameMode {
        shareware,   // DOOM 1 shareware, E1, M9
        registered,  // DOOM 1 registered, E3, M27
        commercial,  // DOOM 2 retail, E1 M34
        retail,      // DOOM 1 retail, E4, M36
        indetermined // well, indetermined.
    }

    // doomstat.h line 60 — identify IWAD as shareware, retail etc.
    // D_DoomMain sets this after WAD identification; default matches the
    // C runtime init order (zero-init == shareware == enum value 0).
    public static GameMode gamemode = GameMode.shareware;

    // Port extension: use the generated arena for user games in this session.
    public static boolean dynamicLevels;
    public static long dynamicSeed;

    // doomstat.h — true while replaying a demo (set by G_DoPlayDemo).
    public static boolean demoplayback;
    // doomstat.h — true while a recorded demo is playing in a
    // netgame slot (multiplayer .lmp).
    public static boolean netdemo;
    // doomstat.h — true when -playdemo on the cmdline so I_Quit
    // fires at demo end (vs. cycling through the title-screen
    // demo loop).
    public static boolean singledemo;

    // doomstat.h — 0 = co-op, 1 = old deathmatch, 2 = altdeath.
    // Set from the -deathmatch / -altdeath command-line switches.
    public static int deathmatch;

    // doomstat.h — secret-sector counter, incremented by P_SpawnSpecials
    // for each sector with special == 9; decremented by P_PlayerInSpecialSector
    // when a player first enters one.
    public static int totalsecret;

    // doomdef.h skill_t — game difficulty, set by D_DoomMain from
    // -skill / -nightmare. P_SpawnMobj and P_SpawnMapThing branch on
    // it (sk_baby = double pickups, sk_nightmare = monsters use
    // reactiontime 0 + respawn).
    public static final int sk_baby      = 0;
    public static final int sk_easy      = 1;
    public static final int sk_medium    = 2;
    public static final int sk_hard      = 3;
    public static final int sk_nightmare = 4;

    public static int gameskill;

    // doomstat.h — true under -respawn or -nightmare. P_NightmareRespawn
    // and P_MobjThinker gate on this for the monster-respawn loop.
    public static boolean respawnparm; // -respawn / demo header request
    public static boolean respawnmonsters; // derived from skill and respawnparm

    // doomstat.h — true when running multiplayer (-net), false for
    // single player. P_SpawnMapThing's "skip cooperative spots in
    // single-player" branch reads this.
    public static boolean netgame;

    // doomstat.h — true under -nomonsters; P_SpawnMapThing skips
    // skull/COUNTKILL spawns when set.
    public static boolean nomonsters;

    // doomstat.h — true under -fast (or implicit on sk_nightmare).
    // p_enemy.c reads this to skip the per-tic A_Chase movement-
    // delay branch (faster monster chase + faster missile-fire
    // cadence).
    public static boolean fastparm;

    // doomstat.h — pause flag set by the BTS_PAUSE special-button
    // chord. P_Ticker bails immediately when set.
    public static boolean paused;

    // doomstat.h — running totals updated as P_SpawnMapThing creates
    // counted things; consumed by the intermission screen (wi_stuff).
    public static int totalkills;
    public static int totalitems;

    // doomstat.h — current map number (1..32 for Doom 2; 1..9 per
    // episode for Doom 1). PIT_StompThing reads this for the
    // boss-level (level 30) telefrag exception.
    public static int gamemap;

    // doomstat.h — current episode (1..4 for Doom; ignored on Doom 2).
    // A_BossDeath reads this to pick the per-episode boss-trigger
    // tag/action.
    public static int gameepisode;

    // doomdef.h lines 127–133 — gamestate_t. The C uses an enum;
    // the Java port carries the values as int constants so the
    // existing switch statements translate unchanged.
    public static final int GS_LEVEL        = 0;
    public static final int GS_INTERMISSION = 1;
    public static final int GS_FINALE       = 2;
    public static final int GS_DEMOSCREEN   = 3;

    // doomstat.h line 193 — current game state, set by the various
    // start/exit handlers (G_DoLoadLevel → GS_LEVEL,
    // F_StartFinale → GS_FINALE, etc.). D_Display dispatches the
    // top-level drawer based on it.
    public static int gamestate = GS_LEVEL;

    // doomstat.h line 137 — true while the playsim is the active
    // backdrop. F_StartFinale clears it so the play view stops
    // updating during the end-of-episode text screen.
    public static boolean viewactive;

    // doomstat.h lines 251–253 — last gamestate the wipe machinery
    // observed. -1 forces a wipe on the next D_Display pass.
    public static int wipegamestate = GS_LEVEL;

    // doomstat.h — true while a level is loaded and the user is
    // playing (not at the title screen / intermission). M_SaveGame
    // / M_EndGame gate on this so save/end-game refuse to fire on
    // the title screen.
    public static boolean usergame;

    private Doomstat() {
    }
}
