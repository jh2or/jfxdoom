package doom.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.play.Player;
import doom.play.Ticcmd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GGameTest {

    @BeforeEach
    void resetState() {
        for (int i = 0; i < Doomdef.NUMKEYS; i++) {
            GGame.gamekeydown[i] = false;
        }
        GGame.turnheld = 0;
        GGame.sendpause = false;
        GGame.sendsave = false;
        Doomstat.paused = false;
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        GGame.consoleplayer = 0;
    }

    // ---- G_Responder: keydown sets gamekeydown[], keyup clears ----

    @Test
    void responderKeydownSetsGamekeydown() {
        DEvent ev = new DEvent(DEvent.EV_KEYDOWN, Doomdef.KEY_UPARROW);
        boolean ate = GGame.gResponder(ev);
        assertTrue(ate);
        assertTrue(GGame.gamekeydown[Doomdef.KEY_UPARROW]);
    }

    @Test
    void responderKeyupClearsGamekeydown() {
        GGame.gamekeydown[Doomdef.KEY_UPARROW] = true;
        DEvent ev = new DEvent(DEvent.EV_KEYUP, Doomdef.KEY_UPARROW);
        // C: returns false on keyup so it can filter down (M_Responder
        // catches keyups for menu navigation).
        boolean ate = GGame.gResponder(ev);
        assertFalse(ate);
        assertFalse(GGame.gamekeydown[Doomdef.KEY_UPARROW]);
    }

    // KEY_PAUSE flips sendpause.
    @Test
    void responderPauseLatch() {
        GGame.sendpause = false;
        DEvent ev = new DEvent(DEvent.EV_KEYDOWN, Doomdef.KEY_PAUSE);
        GGame.gResponder(ev);
        assertTrue(GGame.sendpause);
        // gamekeydown[KEY_PAUSE]=0xff is also a valid path; check
        // sendpause specifically to verify the latch fires.
    }

    // ---- G_BuildTiccmd: keys → ticcmd ----

    // Forward arrow → +forwardmove (walk speed = forwardmove[0]).
    @Test
    void buildTiccmdForwardKeySetsWalkForward() {
        GGame.gamekeydown[Doomdef.KEY_UPARROW] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertEquals(GGame.forwardmove[0], cmd.forwardmove);
    }

    // Forward + run modifier → run-speed forwardmove.
    @Test
    void buildTiccmdRunKeyDoublesForward() {
        GGame.gamekeydown[Doomdef.KEY_UPARROW] = true;
        GGame.gamekeydown[Doomdef.KEY_RSHIFT] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertEquals(GGame.forwardmove[1], cmd.forwardmove);
    }

    // Right arrow → angleturn slowturn (tspeed=2) on the first
    // SLOWTURNTICS-1 frames.
    @Test
    void buildTiccmdRightArrowAppliesSlowTurn() {
        GGame.gamekeydown[Doomdef.KEY_RIGHTARROW] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        // Right turns angle counter-clockwise per the C convention
        // (angleturn[2]=320 subtracted).
        assertEquals(-GGame.angleturn[2], cmd.angleturn);
    }

    // After SLOWTURNTICS frames of holding right, the turn rate
    // bumps to angleturn[0] (slow=0 idx, normal walk speed).
    @Test
    void buildTiccmdHeldTurnAcceleratesAfterSlowTurnTics() {
        GGame.gamekeydown[Doomdef.KEY_RIGHTARROW] = true;
        for (int i = 0; i < GGame.SLOWTURNTICS; i++) {
            Ticcmd cmd = new Ticcmd();
            GGame.gBuildTiccmd(cmd);
        }
        // turnheld == SLOWTURNTICS now → tspeed = speed (walk = 0)
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertEquals(-GGame.angleturn[0], cmd.angleturn);
    }

    // Strafe modifier turns left/right keys into sidemove instead.
    @Test
    void buildTiccmdStrafeKeyTurnsLeftRightIntoSidemove() {
        GGame.gamekeydown[Doomdef.KEY_RALT] = true;       // strafe
        GGame.gamekeydown[Doomdef.KEY_RIGHTARROW] = true; // → +sidemove
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertEquals(GGame.sidemove[0], cmd.sidemove);
        assertEquals(0, cmd.angleturn);
    }

    // BT_ATTACK / BT_USE chord assembly.
    @Test
    void buildTiccmdFireSetsBtAttack() {
        GGame.gamekeydown[GGame.keyFire] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertTrue((cmd.buttons & Doomdef.BT_ATTACK) != 0);
    }

    @Test
    void buildTiccmdUseSetsBtUse() {
        GGame.gamekeydown[GGame.keyUse] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertTrue((cmd.buttons & Doomdef.BT_USE) != 0);
    }

    // Number-key weapon-change.
    @Test
    void buildTiccmdWeaponDigitSetsBtChangeWithIndex() {
        GGame.gamekeydown['3'] = true;  // weapon idx 2
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertTrue((cmd.buttons & Doomdef.BT_CHANGE) != 0);
        int idx = (cmd.buttons & Doomdef.BT_WEAPONMASK)
                >> Doomdef.BT_WEAPONSHIFT;
        assertEquals(2, idx);
    }

    // sendpause → BT_SPECIAL | BTS_PAUSE.
    @Test
    void buildTiccmdSendpauseEmitsSpecialButton() {
        GGame.sendpause = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertEquals(Doomdef.BT_SPECIAL | Doomdef.BTS_PAUSE, cmd.buttons);
        // Latch consumed.
        assertFalse(GGame.sendpause);
    }

    // Movement clamping at MAXPLMOVE.
    @Test
    void buildTiccmdForwardCappedAtMaxPlMove() {
        // forward arrow + run gives forwardmove[1]=0x32 = MAXPLMOVE.
        GGame.gamekeydown[Doomdef.KEY_UPARROW] = true;
        GGame.gamekeydown[Doomdef.KEY_RSHIFT] = true;
        Ticcmd cmd = new Ticcmd();
        GGame.gBuildTiccmd(cmd);
        assertTrue(cmd.forwardmove <= GGame.MAXPLMOVE);
    }

    // ---- DMain.dPostEvent / dProcessEvents → G_Responder ----

    @Test
    void postEventQueuesAndProcessEventsDispatches() {
        DMain.eventhead = DMain.eventtail = 0;
        DMain.dPostEvent(new DEvent(DEvent.EV_KEYDOWN, Doomdef.KEY_UPARROW));
        DMain.dProcessEvents();
        assertTrue(GGame.gamekeydown[Doomdef.KEY_UPARROW]);
        // Queue drained.
        assertEquals(DMain.eventhead, DMain.eventtail);
    }

    // ---- gameaction transitions ----

    // gInitNew queues ga_loadlevel and stamps skill/episode/map.
    @Test
    void initNewQueuesLevelLoad() {
        GGame.gameaction = GGame.ga_nothing;
        GGame.gInitNew(Doomstat.sk_hard, 2, 3);
        assertEquals(Doomstat.sk_hard, Doomstat.gameskill);
        assertEquals(2, Doomstat.gameepisode);
        assertEquals(3, Doomstat.gamemap);
        assertEquals(GGame.ga_loadlevel, GGame.gameaction);
    }

    // sk_nightmare auto-enables respawnmonsters.
    @Test
    void initNewNightmareSetsRespawnMonsters() {
        Doomstat.respawnmonsters = false;
        GGame.gInitNew(Doomstat.sk_nightmare, 1, 1);
        assertTrue(Doomstat.respawnmonsters);
    }

    // gDoCompleted populates wminfo with the routing for the next
    // Doom 1 map. The intermission may then succeed (with WAD) or
    // degrade to ga_worlddone (no WAD); these tests only verify the
    // wminfo population that happens unconditionally beforehand.
    @Test
    void doCompletedPopulatesWmInfoForDoom1MidEpisode() {
        Doomstat.gamemode = Doomstat.GameMode.shareware;
        Doomstat.gameepisode = 1;
        Doomstat.gamemap = 3;
        GGame.secretexit = 0;
        GGame.gDoCompleted();
        assertEquals(0, GGame.wminfo.epsd);        // 0-based
        assertEquals(2, GGame.wminfo.last);        // gamemap-1
        assertEquals(3, GGame.wminfo.next);        // gamemap (0-based next)
    }

    // End-of-episode (Doom 1, map 8) → ga_victory (cast call / finale).
    @Test
    void doCompletedDoom1Map8TriggersVictory() {
        Doomstat.gamemode = Doomstat.GameMode.registered;
        Doomstat.gameepisode = 1;
        Doomstat.gamemap = 8;
        GGame.gDoCompleted();
        assertEquals(GGame.ga_victory, GGame.gameaction);
    }

    // Doom 2: gDoCompleted on map 5 routes wminfo.next = gamemap.
    @Test
    void doCompletedCommercialRoutesNextMap() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 5;
        GGame.secretexit = 0;
        GGame.gDoCompleted();
        assertEquals(5, GGame.wminfo.next);  // gamemap (0-based next slot)
    }

    // Doom 2 secret exit on map 15 routes to map 31 (0-based 30).
    @Test
    void doCompletedCommercialSecretExitRoutesToMap31() {
        Doomstat.gamemode = Doomstat.GameMode.commercial;
        Doomstat.gamemap = 15;
        GGame.secretexit = 1;
        GGame.gDoCompleted();
        assertEquals(30, GGame.wminfo.next);
    }
}
