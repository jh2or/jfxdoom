package doom.system;

import doom.play.Player;
import doom.ui.MMenu;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic: boot to the title screen (no -warp), open the
 * menu, pick episode 1 + difficulty, verify the player ends
 * up alive with the pistol after a few tics. Reproduces the
 * user-reported "starting a new game from the menu doesn't
 * load the level" issue.
 *
 * Armed via `-De1m1.probe=1`.
 */
class E1M1MenuStartProbe {

    private static final Path WAD_PATH = doom.testing.TestWads.find();

    @Test
    void menuPickStartsLevelWithHealthAndWeapon() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getProperty("e1m1.probe")),
                "Diagnostic disabled; enable with -De1m1.probe=1");
        doom.testing.TestWads.require(WAD_PATH);

        // Boot with no -warp — engine sits on the title-screen
        // demo loop.
        DMain.dDoomMain(new String[] {
                "-iwad", WAD_PATH.toString() });

        // Let one frame pump to drain `advancedemo` — the C
        // sequence does this before the menu opens too.
        DMain.dDoomLoop();
        System.err.printf("after first loop: advancedemo=%b gameaction=%d gamestate=%d%n",
                DMain.advancedemo, GGame.gameaction, Doomstat.gamestate);

        // Simulate the user letting the title-screen demo loop
        // tick through to demo1 — this is exactly the wait that
        // reproduces "no control of the player" after the menu
        // pick. With demoplayback latched true, gTicker keeps
        // overwriting the live ticcmd with demo bytes.
        Doomstat.demoplayback = true;
        GGame.demoBuffer = new byte[] { 0, 0, 0, 0, (byte) GGame.DEMOMARKER };
        System.err.printf("simulated demoplayback: demoplayback=%b%n",
                Doomstat.demoplayback);

        // Walk the menu state machine the same way mResponder
        // would: pick episode 1 (choice 0) → pick HURTME skill
        // (the default difficulty).
        MMenu.mEpisode(0);
        MMenu.mChooseSkill(MMenu.hurtme);

        System.err.printf("after pick: gameaction=%d dSkill=%d dEpisode=%d dMap=%d%n",
                GGame.gameaction, GGame.dSkill, GGame.dEpisode, GGame.dMap);

        for (int i = 0; i < 5; i++) {
            DMain.dDoomLoop();
            System.err.printf("after loop %d: gameaction=%d gamestate=%d "
                            + "playeringame[0]=%b mo=%s%n",
                    i, GGame.gameaction, Doomstat.gamestate,
                    GGame.playeringame[0],
                    GGame.players[0].mo != null ? "set" : "null");
        }

        // gDeferedInitNew set gameaction=ga_newgame. Already
        // drained in the per-frame loop above.

        // Verify the player ended up in a healthy starting state.
        Player p = GGame.players[0];
        if (p == null || p.mo == null) {
            throw new AssertionError("no player mobj after menu start");
        }
        System.err.printf(
                "menu-start: x=%d y=%d health=%d/%d mo.health=%d "
                        + "fist=%b pistol=%b ammo[clip]=%d gamemode=%s%n",
                p.mo.x >> 16, p.mo.y >> 16,
                p.health, 100, p.mo.health,
                p.weaponowned[Doomdef.wp_fist],
                p.weaponowned[Doomdef.wp_pistol],
                p.ammo[Doomdef.am_clip],
                Doomstat.gamemode);
        if (p.health != 100) {
            throw new AssertionError("expected 100 health, got " + p.health);
        }
        if (!p.weaponowned[Doomdef.wp_pistol]) {
            throw new AssertionError("pistol should be owned at spawn");
        }
        if (p.ammo[Doomdef.am_clip] != 50) {
            throw new AssertionError("expected 50 bullets, got " + p.ammo[Doomdef.am_clip]);
        }
        // gDoNewGame must have cleared the simulated demoplayback —
        // otherwise the user has no input control.
        if (Doomstat.demoplayback) {
            throw new AssertionError("demoplayback should be cleared after gDoNewGame");
        }
        // Gamemode should be retail since DOOM.WAD has E4M1.
        if (Doomstat.gamemode != Doomstat.GameMode.retail
                && Doomstat.gamemode != Doomstat.GameMode.registered) {
            throw new AssertionError("expected retail/registered gamemode, got "
                    + Doomstat.gamemode);
        }
    }
}
