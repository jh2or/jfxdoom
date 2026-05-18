package doom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Info;
import doom.play.Player;
import doom.play.Ticcmd;
import doom.system.DEvent;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.Dstrings;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FFinaleTest {

    @BeforeEach
    void resetState() {
        FFinale.finalestage   = 0;
        FFinale.finalecount   = 0;
        FFinale.finaletext    = null;
        FFinale.finaleflat    = null;
        FFinale.castnum       = 0;
        FFinale.casttics      = 0;
        FFinale.caststate     = null;
        FFinale.castdeath     = false;
        FFinale.castframes    = 0;
        FFinale.castonmelee   = 0;
        FFinale.castattacking = false;

        GGame.gameaction      = GGame.ga_nothing;
        Doomstat.gamestate    = Doomstat.GS_LEVEL;
        Doomstat.viewactive   = true;
        Doomstat.wipegamestate = Doomstat.GS_LEVEL;
        AmMap.automapactive   = false;
        Doomstat.gamemode     = Doomstat.GameMode.shareware;
        Doomstat.gameepisode  = 1;
        Doomstat.gamemap      = 1;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.players[i].cmd = new Ticcmd();
            GGame.playeringame[i] = false;
        }
        GGame.playeringame[0] = true;
        GGame.consoleplayer = 0;
    }

    // ---- F_StartFinale ----

    // Shareware E1 → mus_victor + FLOOR4_8 tile + E1TEXT.
    @Test
    void startFinaleE1AssignsEpisode1ArtAndText() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        Doomstat.gameepisode = 1;
        GGame.gameaction = GGame.ga_victory;
        FFinale.fStartFinale();
        assertEquals(GGame.ga_nothing, GGame.gameaction);
        assertEquals(Doomstat.GS_FINALE, Doomstat.gamestate);
        assertFalse(Doomstat.viewactive);
        assertFalse(AmMap.automapactive);
        assertEquals("FLOOR4_8", FFinale.finaleflat);
        assertSame(Dstrings.E1TEXT, FFinale.finaletext);
        assertEquals(0, FFinale.finalestage);
        assertEquals(0, FFinale.finalecount);
    }

    // Registered E3 → MFLR8_4 / E3TEXT.
    @Test
    void startFinaleE3AssignsEpisode3ArtAndText() {
        Doomstat.gamemode = Doomstat.GameMode.registered;
        Doomstat.gameepisode = 3;
        FFinale.fStartFinale();
        assertEquals("MFLR8_4", FFinale.finaleflat);
        assertSame(Dstrings.E3TEXT, FFinale.finaletext);
    }

    // Commercial map 6 → SLIME16 + C1TEXT (post-Starport text screen).
    @Test
    void startFinaleCommercialMap6PicksC1Text() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 6;
        FFinale.fStartFinale();
        assertEquals("SLIME16", FFinale.finaleflat);
        assertSame(Dstrings.C1TEXT, FFinale.finaletext);
    }

    // Commercial map 30 → RROCK17 + C4TEXT (post-Icon-of-Sin).
    @Test
    void startFinaleCommercialMap30PicksC4Text() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 30;
        FFinale.fStartFinale();
        assertEquals("RROCK17", FFinale.finaleflat);
        assertSame(Dstrings.C4TEXT, FFinale.finaletext);
    }

    // ---- F_Ticker text → art transition ----

    // Doom 1 finale: after `len*TEXTSPEED + TEXTWAIT` tics, finalestage
    // flips 0 → 1 and the wipe latch fires.
    @Test
    void tickerAdvancesFromTextToArtAfterTimeout() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        Doomstat.gameepisode = 1;
        FFinale.fStartFinale();
        int total = FFinale.finaletext.length() * FFinale.TEXTSPEED + FFinale.TEXTWAIT;
        // Pump tics until the transition condition lands.
        for (int i = 0; i <= total; i++) {
            FFinale.fTicker();
        }
        assertEquals(1, FFinale.finalestage);
        assertEquals(0, FFinale.finalecount);
        assertEquals(-1, Doomstat.wipegamestate);
    }

    // Doom 2 finale: button press after 50 tics fires ga_worlddone
    // (or ga_castcall on map 30).
    @Test
    void tickerCommercialButtonPressFiresWorldDone() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 6;
        FFinale.fStartFinale();
        // Pump past the 50-tic gate.
        for (int i = 0; i < 51; i++) {
            FFinale.fTicker();
        }
        // No button held → no transition.
        assertEquals(GGame.ga_nothing, GGame.gameaction);
        GGame.players[0].cmd.buttons = Doomdef.BT_ATTACK;
        FFinale.fTicker();
        assertEquals(GGame.ga_worlddone, GGame.gameaction);
    }

    // Commercial map 30 + button press → cast call (not worlddone).
    @Test
    void tickerCommercialMap30ButtonStartsCastCall() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 30;
        FFinale.fStartFinale();
        for (int i = 0; i < 51; i++) {
            FFinale.fTicker();
        }
        GGame.players[0].cmd.buttons = Doomdef.BT_USE;
        FFinale.fTicker();
        assertEquals(2, FFinale.finalestage);
        assertEquals(GGame.ga_nothing, GGame.gameaction);  // cast call doesn't fire worlddone
    }

    // ---- F_StartCast / cast cycle ----

    // Cast call initialisation walks to the first caster's see state.
    @Test
    void startCastInitialisesFirstCasterAndState() {
        FFinale.castnum = 5;
        FFinale.castdeath = true;
        FFinale.castattacking = true;
        FFinale.fStartCast();
        assertEquals(0, FFinale.castnum);
        assertFalse(FFinale.castdeath);
        assertFalse(FFinale.castattacking);
        assertEquals(2, FFinale.finalestage);
        // First caster is the zombieman → seestate.
        assertNotNull(FFinale.caststate);
        int expected = Info.mobjinfo[FFinale.castorder[0].type].seestate;
        assertSame(Info.states[expected], FFinale.caststate);
        assertEquals(-1, Doomstat.wipegamestate);
    }

    // F_CastResponder eats key presses by forcing the death frame.
    @Test
    void castResponderForcesDeathFrameOnKeyPress() {
        FFinale.fStartCast();
        assertFalse(FFinale.castdeath);
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'x';
        boolean ate = FFinale.fCastResponder(ev);
        assertTrue(ate);
        assertTrue(FFinale.castdeath);
        assertFalse(FFinale.castattacking);
        int expected = Info.mobjinfo[FFinale.castorder[FFinale.castnum].type].deathstate;
        assertSame(Info.states[expected], FFinale.caststate);
    }

    // Second key press during dying frames is consumed but does
    // nothing extra (returns true).
    @Test
    void castResponderConsumesSecondKeyWhileDying() {
        FFinale.fStartCast();
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'x';
        FFinale.fCastResponder(ev);
        assertTrue(FFinale.castdeath);
        assertTrue(FFinale.fCastResponder(ev));  // still returns true
    }

    // Non-keydown events fall through.
    @Test
    void castResponderIgnoresNonKeydownEvents() {
        FFinale.fStartCast();
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYUP;
        ev.data1 = 'x';
        assertFalse(FFinale.fCastResponder(ev));
    }

    // ---- F_Responder dispatch ----

    // Stage 0/1 returns false; stage 2 (cast call) dispatches.
    @Test
    void responderRoutesByStage() {
        FFinale.finalestage = 0;
        DEvent ev = new DEvent();
        ev.type = DEvent.EV_KEYDOWN;
        ev.data1 = 'a';
        assertFalse(FFinale.fResponder(ev));

        FFinale.fStartCast();
        assertTrue(FFinale.fResponder(ev));
    }
}
