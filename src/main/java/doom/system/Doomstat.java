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

    // doomstat.h — true while replaying a demo (set by G_DoPlayDemo).
    public static boolean demoplayback;

    // doomstat.h — 0 = co-op, 1 = old deathmatch, 2 = altdeath.
    // Set from the -deathmatch / -altdeath command-line switches.
    public static int deathmatch;

    // doomstat.h — secret-sector counter, incremented by P_SpawnSpecials
    // for each sector with special == 9; decremented by P_PlayerInSpecialSector
    // when a player first enters one.
    public static int totalsecret;

    private Doomstat() {
    }
}
