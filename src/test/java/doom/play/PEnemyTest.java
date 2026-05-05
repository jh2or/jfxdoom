package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Sector;
import doom.render.Subsector;
import doom.system.Doomdef;
import doom.system.Doomstat;
import doom.system.GGame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PEnemyTest {

    private Sector sec;
    private Subsector ss;

    @BeforeEach
    void resetWorld() {
        sec = new Sector();
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.special = 0;
        sec.thinglist = null;
        sec.linecount = 0;
        sec.lines = new doom.render.Line[0];
        sec.tag = 0;

        ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new doom.render.Node[0];
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;
        PSetup.rejectmatrix = new byte[] { 0 };

        PTick.pInitThinkers();
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.fastparm = false;
        Doomstat.netgame = false;
        Doomstat.gamemode = Doomstat.GameMode.shareware;

        // Reset roster. Per the C invariant (D_DoomMain seeds
        // playeringame[0]=true in single-player), at least one
        // seat must be active or P_LookForPlayers spins forever
        // — its termination condition only fires on an active
        // slot. Tests that want "no player to find" leave seat 0
        // dead (health=0) without giving it a Mobj.
        for (int i = 0; i < Doomdef.MAXPLAYERS; i++) {
            GGame.players[i] = new Player();
            GGame.playeringame[i] = false;
        }
        GGame.playeringame[0] = true; // baseline single-player invariant
        // players[0].health = 0 by default (Java) — seat is "dead"
        // until tests upgrade it via makePlayer1.
        GGame.consoleplayer = 0;
    }

    private Mobj makeMonster(int x, int y, int type) {
        return PMobj.pSpawnMobj(x, y, PMobj.ONFLOORZ, type);
    }

    private Player makePlayer1(int x, int y) {
        Mobj mo = PMobj.pSpawnMobj(x, y, PMobj.ONFLOORZ, Info.MT_PLAYER);
        Player p = GGame.players[0];
        // playeringame[0] already true from BeforeEach.
        p.mo = mo;
        mo.player = p;
        p.health = 100;
        return p;
    }

    // --- P_RecursiveSound / P_NoiseAlert ---

    // A single-sector world with no two-sided lines: NoiseAlert
    // floods just the source sector and stops.
    @Test
    void noiseAlertWritesSoundtargetIntoSourceSector() {
        Mobj target = makeMonster(0, 0, Info.MT_PLAYER);
        Mobj emitter = makeMonster(0, 0, Info.MT_PLAYER);

        PEnemy.pNoiseAlert(target, emitter);

        assertSame(target, sec.soundtarget);
    }

    // --- P_CheckMeleeRange ---

    @Test
    void checkMeleeRangeFalseWithoutTarget() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.target = null;
        assertFalse(PEnemy.pCheckMeleeRange(actor));
    }

    @Test
    void checkMeleeRangeTrueWhenWithinReach() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        Mobj prey = makeMonster(8 * Fixed.FRACUNIT, 0, Info.MT_PLAYER);
        actor.target = prey;
        // sight is unobstructed in the synthetic single-sector world.
        assertTrue(PEnemy.pCheckMeleeRange(actor));
    }

    @Test
    void checkMeleeRangeFalseWhenTooFar() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        Mobj prey = makeMonster(500 * Fixed.FRACUNIT, 0, Info.MT_PLAYER);
        actor.target = prey;
        assertFalse(PEnemy.pCheckMeleeRange(actor));
    }

    // --- P_LookForPlayers ---

    @Test
    void lookForPlayersFindsLivePlayer() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.angle = 0;
        actor.lastlook = 0;
        Player p = makePlayer1(50 * Fixed.FRACUNIT, 0);

        boolean found = PEnemy.pLookForPlayers(actor, /*allaround*/ true);

        assertTrue(found);
        assertSame(p.mo, actor.target);
    }

    @Test
    void lookForPlayersIgnoresDeadPlayer() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.lastlook = 0;
        Player p = makePlayer1(50 * Fixed.FRACUNIT, 0);
        p.health = 0;

        boolean found = PEnemy.pLookForPlayers(actor, true);
        assertFalse(found);
        assertNull(actor.target);
    }

    // 180° front-only filter: a player directly behind is rejected
    // (unless within MELEERANGE).
    @Test
    void lookForPlayersFrontOnlyFiltersBehindPlayer() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.angle = 0; // facing east
        actor.lastlook = 0;
        // Player behind (west) and far away.
        makePlayer1(-500 * Fixed.FRACUNIT, 0);

        boolean found = PEnemy.pLookForPlayers(actor, /*allaround*/ false);
        assertFalse(found);
    }

    // --- A_Look ---

    // soundtarget set + player visible → wake up + state flip.
    @Test
    void aLookWakesOnSoundtarget() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        Player p = makePlayer1(20 * Fixed.FRACUNIT, 0);
        sec.soundtarget = p.mo;

        PEnemy.aLook(actor);

        assertSame(p.mo, actor.target);
        assertSame(Info.states[actor.info.seestate], actor.state);
    }

    // MF_AMBUSH: noise alone doesn't wake; needs sight check too.
    // With visible player → still wakes.
    @Test
    void aLookAmbushRequiresSightLine() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.flags |= Mobj.MF_AMBUSH;
        Player p = makePlayer1(20 * Fixed.FRACUNIT, 0);
        sec.soundtarget = p.mo;

        PEnemy.aLook(actor);
        // Synthetic single-sector world — sight is unobstructed.
        assertSame(p.mo, actor.target);
    }

    // No soundtarget, no players → stays in spawnstate (no state
    // change).
    @Test
    void aLookDoesNothingWithoutPlayer() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        State before = actor.state;

        PEnemy.aLook(actor);

        assertSame(before, actor.state);
        assertNull(actor.target);
    }

    // --- A_FaceTarget ---

    @Test
    void aFaceTargetTurnsToFaceTarget() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        Mobj target = makeMonster(50 * Fixed.FRACUNIT, 0, Info.MT_PLAYER);
        actor.target = target;
        actor.angle = doom.render.Tables.ANG180; // facing west

        PEnemy.aFaceTarget(actor);
        // East target → angle ~ 0.
        assertEquals(0, actor.angle);
        // MF_AMBUSH cleared.
        assertEquals(0, actor.flags & Mobj.MF_AMBUSH);
    }

    @Test
    void aFaceTargetSkipsWithoutTarget() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.angle = 12345;
        actor.target = null;

        PEnemy.aFaceTarget(actor);
        assertEquals(12345, actor.angle); // unchanged
    }

    // --- A_Chase ---

    // No target → look for one; if none, drop to spawnstate.
    @Test
    void aChaseDropsToSpawnstateWhenNoTarget() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        actor.target = null;

        PEnemy.aChase(actor);

        assertSame(Info.states[actor.info.spawnstate], actor.state);
    }

    // Target dead → same drop path.
    @Test
    void aChaseDropsWhenTargetIsCorpse() {
        Mobj actor = makeMonster(0, 0, Info.MT_POSSESSED);
        Mobj target = makeMonster(50 * Fixed.FRACUNIT, 0, Info.MT_PLAYER);
        target.flags &= ~Mobj.MF_SHOOTABLE; // dead → not shootable
        actor.target = target;

        PEnemy.aChase(actor);
        assertSame(Info.states[actor.info.spawnstate], actor.state);
    }

    // --- Direction LUTs match C ---

    @Test
    void directionConstantsMatchCSource() {
        assertEquals(0, PEnemy.DI_EAST);
        assertEquals(1, PEnemy.DI_NORTHEAST);
        assertEquals(2, PEnemy.DI_NORTH);
        assertEquals(8, PEnemy.DI_NODIR);
        assertEquals(9, PEnemy.NUMDIRS);
    }

    @Test
    void oppositeLutMatchesCSource() {
        assertEquals(PEnemy.DI_WEST, PEnemy.opposite[PEnemy.DI_EAST]);
        assertEquals(PEnemy.DI_EAST, PEnemy.opposite[PEnemy.DI_WEST]);
        assertEquals(PEnemy.DI_NORTH, PEnemy.opposite[PEnemy.DI_SOUTH]);
        assertEquals(PEnemy.DI_SOUTH, PEnemy.opposite[PEnemy.DI_NORTH]);
        assertEquals(PEnemy.DI_NODIR, PEnemy.opposite[PEnemy.DI_NODIR]);
    }
}
