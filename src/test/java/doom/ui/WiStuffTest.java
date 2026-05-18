package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;
import doom.play.Ticcmd;
import doom.render.Patch;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WiStuffTest {

    // Stub Patch — width 1, height 12 — gives wiDrawStats /
    // wiDrawPercent enough to compute layout without touching a WAD.
    private static Patch stubPatch() {
        return Patch.fromLump(new byte[] {
                1, 0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        });
    }

    private static Wbstartstruct makeWbs(int epsd, int last, int next) {
        Wbstartstruct wb = new Wbstartstruct();
        wb.epsd = epsd;
        wb.last = last;
        wb.next = next;
        wb.maxkills = 50;
        wb.maxitems = 20;
        wb.maxsecret = 5;
        wb.partime = 35 * 90;          // 90 seconds
        wb.pnum = 0;
        wb.plyr[0].in = true;
        wb.plyr[0].skills  = 25;       // 50% kills
        wb.plyr[0].sitems  = 10;       // 50% items
        wb.plyr[0].ssecret = 2;        // 40% secrets
        wb.plyr[0].stime   = 35 * 60;  // 60 seconds
        return wb;
    }

    @BeforeEach
    void resetState() {
        WiStuff.bcnt = 0;
        WiStuff.cnt = 0;
        WiStuff.sp_state = 0;
        WiStuff.acceleratestage = 0;
        WiStuff.state = WiStuff.NoState;
        WiStuff.snl_pointeron = false;
        WiStuff.cnt_kills[0] = -1;
        WiStuff.cnt_items[0] = -1;
        WiStuff.cnt_secret[0] = -1;
        WiStuff.cnt_time = -1;
        WiStuff.cnt_par = -1;
        WiStuff.cnt_pause = 0;
        WiStuff.firstrefresh = 0;

        for (int i = 0; i < 10; i++) WiStuff.num[i] = stubPatch();
        WiStuff.percent = stubPatch();
        WiStuff.colon = stubPatch();
        WiStuff.wiminus = stubPatch();
        WiStuff.sucks = stubPatch();

        Doomstat.gamemode = Doomstat.GameMode.shareware;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.players[i].cmd = new Ticcmd();
            GGame.playeringame[i] = false;
        }
        GGame.playeringame[0] = true;
        GGame.consoleplayer = 0;
        GGame.gameaction = GGame.ga_nothing;
    }

    // ---- wiInitVariables ----

    // Stat-snapshot pointer is bound; max* is clamped to 1.
    @Test
    void initVariablesClampsMaxKillsItemsSecret() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        wb.maxkills = 0;
        wb.maxitems = 0;
        wb.maxsecret = 0;
        WiStuff.wiInitVariables(wb);
        assertEquals(1, wb.maxkills);
        assertEquals(1, wb.maxitems);
        assertEquals(1, wb.maxsecret);
        assertEquals(wb.plyr, WiStuff.plrs);
        assertEquals(0, WiStuff.me);
    }

    // Non-retail games clamp epsd > 2 down by 3.
    @Test
    void initVariablesWrapsEpsdForNonRetail() {
        Wbstartstruct wb = makeWbs(3, 0, 1);
        Doomstat.gamemode = Doomstat.GameMode.registered;
        WiStuff.wiInitVariables(wb);
        assertEquals(0, wb.epsd);
    }

    // Retail games leave epsd alone (E4 is valid).
    @Test
    void initVariablesPreservesEpsdForRetail() {
        Wbstartstruct wb = makeWbs(3, 0, 1);
        Doomstat.gamemode = Doomstat.GameMode.retail;
        WiStuff.wiInitVariables(wb);
        assertEquals(3, wb.epsd);
    }

    // ---- wiInitStats / wiUpdateStats ----

    @Test
    void initStatsLatchesCounterStartsAndPauseBudget() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        assertEquals(WiStuff.StatCount, WiStuff.state);
        assertEquals(1, WiStuff.sp_state);
        assertEquals(-1, WiStuff.cnt_kills[0]);
        assertEquals(-1, WiStuff.cnt_items[0]);
        assertEquals(-1, WiStuff.cnt_secret[0]);
        assertEquals(-1, WiStuff.cnt_time);
        assertEquals(-1, WiStuff.cnt_par);
        assertEquals(Doomdef.TICRATE, WiStuff.cnt_pause);
    }

    // sp_state odd & pause expires → bump sp_state by 1 (advances
    // from "wait" into the next counter phase).
    @Test
    void updateStatsOddPauseAdvancesSpState() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.cnt_pause = 1;     // about to flip
        WiStuff.wiUpdateStats();
        assertEquals(2, WiStuff.sp_state);
        assertEquals(Doomdef.TICRATE, WiStuff.cnt_pause);
    }

    // sp_state==2 (kills phase) bumps cnt_kills by 2 each tic.
    @Test
    void updateStatsKillPhaseTicksUp() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.cnt_kills[0] = 10;
        WiStuff.sp_state = 2;
        WiStuff.wiUpdateStats();
        assertEquals(12, WiStuff.cnt_kills[0]);
    }

    // Kill phase clamps at maxkills percentage and advances sp_state.
    @Test
    void updateStatsKillPhaseClampsAndAdvances() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.cnt_kills[0] = 48;   // one tic away from 50%
        WiStuff.sp_state = 2;
        WiStuff.wiUpdateStats();
        assertEquals(50, WiStuff.cnt_kills[0]);
        assertEquals(3, WiStuff.sp_state);
    }

    // ---- acceleratestage shortcut ----

    @Test
    void updateStatsAcceleratesToFinalValues() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.acceleratestage = 1;
        WiStuff.wiUpdateStats();
        assertEquals(50, WiStuff.cnt_kills[0]);
        assertEquals(50, WiStuff.cnt_items[0]);
        assertEquals(40, WiStuff.cnt_secret[0]);
        assertEquals(60, WiStuff.cnt_time);
        assertEquals(90, WiStuff.cnt_par);
        assertEquals(10, WiStuff.sp_state);
    }

    // sp_state == 10 + accelerate → transition out. Commercial →
    // NoState; Doom 1 → ShowNextLoc.
    @Test
    void updateStatsState10AcceleratesToShowNextLoc() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.sp_state = 10;
        WiStuff.acceleratestage = 1;
        WiStuff.wiUpdateStats();
        assertEquals(WiStuff.ShowNextLoc, WiStuff.state);
    }

    @Test
    void updateStatsState10AcceleratesToNoStateOnCommercial() {
        Wbstartstruct wb = makeWbs(0, 0, 1);
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        WiStuff.wiInitVariables(wb);
        WiStuff.wiInitStats();
        WiStuff.sp_state = 10;
        WiStuff.acceleratestage = 1;
        WiStuff.wiUpdateStats();
        assertEquals(WiStuff.NoState, WiStuff.state);
    }

    // ---- wiCheckForAccelerate ----

    // BT_ATTACK edge press lights acceleratestage.
    @Test
    void checkForAccelerateLatchesOnAttackEdge() {
        GGame.players[0].cmd.buttons = Doomdef.BT_ATTACK;
        GGame.players[0].attackdown = 0;
        WiStuff.acceleratestage = 0;
        WiStuff.wiCheckForAccelerate();
        assertEquals(1, WiStuff.acceleratestage);
        assertTrue(GGame.players[0].attackdown != 0);
    }

    // Holding the button across two tics only triggers once.
    @Test
    void checkForAccelerateIgnoresRepeatedAttack() {
        GGame.players[0].cmd.buttons = Doomdef.BT_ATTACK;
        GGame.players[0].attackdown = 1;   // already pressed
        WiStuff.acceleratestage = 0;
        WiStuff.wiCheckForAccelerate();
        assertEquals(0, WiStuff.acceleratestage);
    }

    // Releasing the button clears attackdown so the next press
    // re-edges.
    @Test
    void checkForAccelerateResetsOnRelease() {
        GGame.players[0].cmd.buttons = 0;
        GGame.players[0].attackdown = 1;
        WiStuff.wiCheckForAccelerate();
        assertEquals(0, GGame.players[0].attackdown);
    }

    // ---- wiInitNoState / wiUpdateNoState ----

    // NoState countdown to 0 fires ga_worlddone.
    @Test
    void updateNoStateFiresWorldDoneAtZero() {
        WiStuff.state = WiStuff.NoState;
        WiStuff.cnt = 1;             // one tic away
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        WiStuff.wiUpdateNoState();
        assertEquals(GGame.ga_worlddone, GGame.gameaction);
    }

    // ---- wiInitShowNextLoc / wiUpdateShowNextLoc ----

    // ShowNextLoc countdown flickers the pointer (bit 4 of cnt).
    @Test
    void updateShowNextLocPulsesPointer() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        WiStuff.wbs = makeWbs(0, 0, 1);
        WiStuff.wiInitShowNextLoc();
        // cnt = SHOWNEXTLOCDELAY * TICRATE = 4*35 = 140.
        // 140 & 31 = 12 → pointer on (12 < 20).
        WiStuff.wiUpdateShowNextLoc();
        assertTrue(WiStuff.snl_pointeron);
    }

    // ShowNextLoc countdown hitting 0 transitions to NoState.
    @Test
    void updateShowNextLocTransitionsToNoStateOnZero() {
        WiStuff.state = WiStuff.ShowNextLoc;
        WiStuff.cnt = 1;
        WiStuff.wiUpdateShowNextLoc();
        assertEquals(WiStuff.NoState, WiStuff.state);
    }
}
