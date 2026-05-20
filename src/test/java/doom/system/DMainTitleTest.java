package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DMainTitleTest {

    @BeforeEach
    void resetState() {
        DMain.advancedemo = false;
        DMain.demosequence = -1;
        DMain.pagetic = 0;
        DMain.pagename = null;
        Doomstat.gamestate = Doomstat.GS_LEVEL;
        Doomstat.usergame = true;
        Doomstat.paused = false;
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
        }
        GGame.consoleplayer = 0;
        GGame.gameaction = GGame.ga_loadlevel;
    }

    // ---- dStartTitle ----

    // dStartTitle clears gameaction, resets demosequence to -1,
    // and arms advancedemo so the next dDoomLoop pass advances.
    @Test
    void startTitleArmsDemoLoop() {
        DMain.dStartTitle();
        assertEquals(GGame.ga_nothing, GGame.gameaction);
        assertEquals(-1, DMain.demosequence);
        assertTrue(DMain.advancedemo);
    }

    // ---- dDoAdvanceDemo ----

    // Sequence 0 → TITLEPIC + GS_DEMOSCREEN + 170-tic page (Doom 1).
    @Test
    void doAdvanceSequenceZeroTitlePic() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        DMain.demosequence = -1;
        DMain.dDoAdvanceDemo();
        assertEquals(0, DMain.demosequence);
        assertEquals("TITLEPIC", DMain.pagename);
        assertEquals(170, DMain.pagetic);
        assertEquals(Doomstat.GS_DEMOSCREEN, Doomstat.gamestate);
        assertFalse(Doomstat.usergame);
        assertFalse(Doomstat.paused);
    }

    // Doom 2 title page lasts 35*11 tics.
    @Test
    void doAdvanceSequenceZeroDoom2TitlePic() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        DMain.demosequence = -1;
        DMain.dDoAdvanceDemo();
        assertEquals(35 * 11, DMain.pagetic);
        assertEquals("TITLEPIC", DMain.pagename);
    }

    // Sequence 2 → CREDIT for 200 tics.
    @Test
    void doAdvanceSequenceTwoCredit() {
        DMain.demosequence = 1;
        DMain.dDoAdvanceDemo();
        assertEquals(2, DMain.demosequence);
        assertEquals("CREDIT", DMain.pagename);
        assertEquals(200, DMain.pagetic);
    }

    // Sequence 4 (Doom 1 shareware) → HELP2 for 200 tics.
    @Test
    void doAdvanceSequenceFourSharewareHelp2() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        DMain.demosequence = 3;
        DMain.dDoAdvanceDemo();
        assertEquals(4, DMain.demosequence);
        assertEquals("HELP2", DMain.pagename);
        assertEquals(200, DMain.pagetic);
    }

    // Sequence 4 retail (Doom Ultimate) shows CREDIT, not HELP2.
    @Test
    void doAdvanceSequenceFourRetailCredit() {
        Doomstat.gamemode = Doomstat.GameMode.retail;
        DMain.demosequence = 3;
        DMain.dDoAdvanceDemo();
        assertEquals("CREDIT", DMain.pagename);
    }

    // Demo-playback sequences (1, 3, 5) queue ga_playdemo. The
    // demo lump is loaded by gDoPlayDemo on the next tic; if the
    // load fails (no WAD in tests), it bounces back to the title.
    @Test
    void doAdvanceSequenceOneQueuesPlaydemo() {
        DMain.demosequence = 0;
        DMain.dDoAdvanceDemo();
        assertEquals(1, DMain.demosequence);
        assertEquals(GGame.ga_playdemo, GGame.gameaction);
        assertEquals("demo1", GGame.defdemoname);
    }

    // Wrap to 0 after sequence 5 (shareware/registered).
    @Test
    void doAdvanceWrapsAtSixForNonRetail() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        DMain.demosequence = 5;
        DMain.dDoAdvanceDemo();
        assertEquals(0, DMain.demosequence);
    }

    // Retail loop walks one more page (sequence 6 → demo4 → wrap).
    @Test
    void doAdvanceWrapsAtSevenForRetail() {
        Doomstat.gamemode = Doomstat.GameMode.retail;
        DMain.demosequence = 5;
        DMain.dDoAdvanceDemo();
        assertEquals(6, DMain.demosequence);
        // Sequence 6 = demo4 — queues ga_playdemo with that name.
        assertEquals(GGame.ga_playdemo, GGame.gameaction);
        assertEquals("demo4", GGame.defdemoname);
    }

    // ---- dPageTicker ----

    // pagetic counts down each tic, fires dAdvanceDemo at -1.
    @Test
    void pageTickerCountsDownAndAdvances() {
        DMain.pagetic = 1;
        DMain.advancedemo = false;
        DMain.dPageTicker();
        assertEquals(0, DMain.pagetic);
        assertFalse(DMain.advancedemo);
        DMain.dPageTicker();
        assertEquals(-1, DMain.pagetic);
        assertTrue(DMain.advancedemo);
    }
}
