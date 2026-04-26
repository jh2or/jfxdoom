// Port of linuxdoom-1.10/g_game.c — stub.
//
// g_game.c is the ~2200-line top-level game-loop coordinator: G_Ticker
// (per-frame event dispatch), G_Responder (input → cmd_t), G_PlayerReborn,
// G_DoLoadLevel, G_BuildTiccmd, demo record/playback, save/load, and
// the level-exit / game-end transitions. Most of it sits on top of
// p_setup, p_user, m_menu and the platform input layer — none ported.
//
// This stub carries only the two level-exit hooks consumed by the
// EV_ trigger dispatchers in p_switch.c / p_spec.c. Both flip a
// `secret` flag that G_DoCompleted later reads, but for the port
// the call sites just need to record that they fired (tests can
// observe the latch through `gameaction`).
package doom.system;

public final class GGame {

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

    private GGame() {
    }
}
