package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Node;
import doom.render.RMain;
import doom.render.Sector;
import doom.render.Subsector;
import doom.system.Doomstat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PMobjTest {

    // Synthetic single-sector world — same shape as PMapTest's
    // openWorld but lifecycle tests don't need a blockmap line.

    @BeforeEach
    void resetWorld() {
        PSetup.sectors = null;
        PSetup.numsectors = 0;
        PSetup.subsectors = null;
        PSetup.numsubsectors = 0;
        PSetup.nodes = null;
        PSetup.numnodes = 0;
        PSetup.lines = null;
        PSetup.numlines = 0;
        PSetup.bmaporgx = -64 * Fixed.FRACUNIT;
        PSetup.bmaporgy = -64 * Fixed.FRACUNIT;
        PSetup.bmapwidth = 1;
        PSetup.bmapheight = 1;
        PSetup.blocklinks = new Mobj[1];
        PSetup.blockmaplump = new int[] { -64, -64, 1, 1, 5, -1 };
        PSetup.blockmap = PSetup.blockmaplump;

        PMap.tmthing = null;
        PMap.tmflags = 0;
        PMap.tmx = 0;
        PMap.tmy = 0;
        PMap.tmfloorz = 0;
        PMap.tmceilingz = 0;
        PMap.tmdropoffz = 0;
        PMap.floatok = false;
        PMap.ceilingline = null;
        PMap.numspechit = 0;
        PMap.changeSectorResult = false;

        PTick.pInitThinkers();
        Doomstat.gameskill = Doomstat.sk_medium;

        RMain.validcount++;
        PMobj.attackrange = 0;
    }

    private static Sector openWorld(int floor, int ceiling) {
        Sector sec = new Sector();
        sec.floorheight = floor;
        sec.ceilingheight = ceiling;
        sec.thinglist = null;

        Subsector ss = new Subsector();
        ss.sector = sec;

        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;
        PSetup.subsectors = new Subsector[] { ss };
        PSetup.numsubsectors = 1;
        PSetup.numnodes = 0;
        PSetup.nodes = new Node[0];

        return sec;
    }

    // --- P_SpawnMobj ---

    @Test
    void spawnMobjInitializesCoreFields() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);

        assertNotNull(m);
        assertEquals(Info.MT_PLAYER, m.type);
        assertSame(Info.mobjinfo[Info.MT_PLAYER], m.info);
        assertEquals(0, m.x);
        assertEquals(0, m.y);
        assertEquals(0, m.z);   // ONFLOORZ → snapped to sector floor
        assertEquals(16 * Fixed.FRACUNIT, m.radius);
        assertEquals(56 * Fixed.FRACUNIT, m.height);
        assertEquals(100, m.health);
        assertEquals(Info.mobjinfo[Info.MT_PLAYER].flags, m.flags);
        // PSetThingPosition wires the subsector pointer.
        assertSame(sec, m.subsector.sector);
        // Thinker function wired so pMobjThinker fires next tick.
        assertNotNull(m.function.acp1);
    }

    @Test
    void spawnMobjSnapsToCeilingWhenZIsONCEILINGZ() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        // MT_TFOG has MF_NOBLOCKMAP|MF_NOGRAVITY — handy for a
        // floating ceiling-z spawn.
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONCEILINGZ, Info.MT_TFOG);
        // info.height is fog's height; m.z = ceilingz - height.
        int expected = sec.ceilingheight - Info.mobjinfo[Info.MT_TFOG].height;
        assertEquals(expected, m.z);
    }

    @Test
    void spawnMobjUsesExplicitZWhenNotSentinel() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        int z = 50 * Fixed.FRACUNIT;
        Mobj m = PMobj.pSpawnMobj(0, 0, z, Info.MT_TFOG);
        assertEquals(z, m.z);
    }

    // Nightmare skill skips the reactiontime init.
    @Test
    void spawnMobjOmitsReactiontimeOnNightmare() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_nightmare;

        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        assertEquals(0, m.reactiontime);  // not info.reactiontime (8)
    }

    // --- P_SetMobjState ---

    @Test
    void setMobjStateAdvancesSpriteFrameAndTics() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);

        // S_POSS_RUN1 is a 4-tic walk-cycle frame on SPR_POSS.
        boolean alive = PMobj.pSetMobjState(m, Info.S_POSS_RUN1);

        assertTrue(alive);
        assertSame(Info.states[Info.S_POSS_RUN1], m.state);
        assertEquals(Info.SPR_POSS, m.sprite);
        assertEquals(Info.states[Info.S_POSS_RUN1].tics, m.tics);
    }

    // S_NULL transition removes the mobj and returns false.
    @Test
    void setMobjStateToSNullRemovesMobj() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);

        boolean alive = PMobj.pSetMobjState(m, Info.S_NULL);

        assertFalse(alive);
        assertTrue(m.removed);
        assertNull(m.state);
    }

    // States with tics=0 cycle straight through to nextstate (the
    // do-while loop). S_LIGHTDONE has tics=4, but a chain of zero-tic
    // states would loop until landing on a positive-tic frame.
    // Verify the do-while drains a 0-tic state correctly: pick a
    // synthetic test path. We use S_PUNCHUP (tics=1) → S_PUNCHUP — it
    // self-loops with tics=1, so the do-while exits after one iter.
    @Test
    void setMobjStateExitsLoopOnFirstPositiveTicEntry() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);

        boolean alive = PMobj.pSetMobjState(m, Info.S_PUNCHUP);
        assertTrue(alive);
        assertSame(Info.states[Info.S_PUNCHUP], m.state);
        assertEquals(1, m.tics);
    }

    // --- P_RemoveMobj ---

    @Test
    void removeMobjUnlinksAndMarksThinker() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_TFOG);

        // Pre-condition: linked into the (otherwise-empty) world.
        assertSame(m, sec.thinglist);

        PMobj.pRemoveMobj(m);

        // Sector list head is back to null after unset.
        assertNull(sec.thinglist);
        // Thinker marked for removal — P_RunThinkers will sweep.
        assertTrue(m.removed);
    }

    // --- P_ExplodeMissile ---

    @Test
    void explodeMissileZeroesMomentumAndAdvancesToDeathState() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        // MT_TROOPSHOT: imp fireball — has a deathstate.
        Mobj m = PMobj.pSpawnMobj(0, 0, 100 * Fixed.FRACUNIT, Info.MT_TROOPSHOT);
        m.momx = 1024; m.momy = 2048; m.momz = -512;

        PMobj.pExplodeMissile(m);

        assertEquals(0, m.momx);
        assertEquals(0, m.momy);
        assertEquals(0, m.momz);
        // MF_MISSILE bit cleared.
        assertEquals(0, m.flags & Mobj.MF_MISSILE);
        assertSame(Info.states[Info.mobjinfo[Info.MT_TROOPSHOT].deathstate],
                m.state);
        assertTrue(m.tics >= 1);
    }

    // --- P_SpawnPuff ---

    @Test
    void spawnPuffPlacesFloatingMobj() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        // attackrange != MELEERANGE → uses the default S_PUFF1
        // spawn (no S_PUFF3 punch-spark redirect).
        PMobj.attackrange = PMobj.MISSILERANGE;

        PMobj.pSpawnPuff(0, 0, 50 * Fixed.FRACUNIT);

        // pSpawnMobj added a thinker; the most-recently-added is the
        // puff. Walk the cap-prev chain to find it.
        Thinker last = PTick.thinkercap.prev;
        assertNotNull(last);
        assertTrue(last instanceof Mobj);
        Mobj puff = (Mobj) last;
        assertEquals(Info.MT_PUFF, puff.type);
        assertEquals(Fixed.FRACUNIT, puff.momz);
        assertTrue(puff.tics >= 1);
    }

    // attackrange == MELEERANGE → puff goes straight to S_PUFF3.
    @Test
    void spawnPuffAtMeleeRangeUsesPuff3() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        PMobj.attackrange = PMobj.MELEERANGE;

        PMobj.pSpawnPuff(0, 0, 50 * Fixed.FRACUNIT);
        Mobj puff = (Mobj) PTick.thinkercap.prev;
        assertSame(Info.states[Info.S_PUFF3], puff.state);
    }

    // --- P_SpawnBlood ---

    @Test
    void spawnBloodDamageVariantsPickDifferentStates() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        // High damage (>12) → default S_BLOOD1.
        PMobj.pSpawnBlood(0, 0, 50 * Fixed.FRACUNIT, /*damage*/ 20);
        Mobj high = (Mobj) PTick.thinkercap.prev;
        // S_BLOOD1 == mobjinfo[MT_BLOOD].spawnstate — i.e. unchanged.
        assertSame(Info.states[Info.mobjinfo[Info.MT_BLOOD].spawnstate],
                high.state);

        // Medium damage [9,12] → S_BLOOD2.
        PMobj.pSpawnBlood(0, 0, 50 * Fixed.FRACUNIT, 10);
        Mobj med = (Mobj) PTick.thinkercap.prev;
        assertNotSame(high, med);
        assertSame(Info.states[Info.S_BLOOD2], med.state);

        // Low damage (<9) → S_BLOOD3.
        PMobj.pSpawnBlood(0, 0, 50 * Fixed.FRACUNIT, 5);
        Mobj low = (Mobj) PTick.thinkercap.prev;
        assertSame(Info.states[Info.S_BLOOD3], low.state);
    }

    // --- P_MobjThinker integration ---

    // Static mobj (tics=-1, no momentum) does nothing per tic.
    @Test
    void mobjThinkerStaticMobjUnchanged() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        // MT_PUFF lasts a few tics. Pin it to a static state by
        // setting tics to -1 directly.
        Mobj m = PMobj.pSpawnMobj(0, 0, 50 * Fixed.FRACUNIT, Info.MT_TFOG);
        m.tics = -1;
        m.momx = m.momy = m.momz = 0;
        m.flags |= Mobj.MF_NOGRAVITY; // already set on TFOG, defensive

        State before = m.state;
        PMobj.pMobjThinker(m);

        assertSame(before, m.state);
        assertEquals(-1, m.tics);
    }

    // Active mobj decrements its tics.
    @Test
    void mobjThinkerDecrementsTics() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, 50 * Fixed.FRACUNIT, Info.MT_TFOG);
        m.tics = 5;

        PMobj.pMobjThinker(m);

        assertEquals(4, m.tics);
    }

    // --- Constants match C ---

    @Test
    void constantsMatchCHeader() {
        assertEquals(4 * Fixed.FRACUNIT, PMobj.FLOATSPEED);
        assertEquals(Fixed.FRACUNIT, PMobj.GRAVITY);
        assertEquals(30 * Fixed.FRACUNIT, PMobj.MAXMOVE);
        assertEquals(64 * Fixed.FRACUNIT, PMobj.MELEERANGE);
        assertEquals(32 * 64 * Fixed.FRACUNIT, PMobj.MISSILERANGE);
        assertEquals(Integer.MIN_VALUE, PMobj.ONFLOORZ);
        assertEquals(Integer.MAX_VALUE, PMobj.ONCEILINGZ);
        assertEquals(0x1000, PMobj.STOPSPEED);
        assertEquals(0xe800, PMobj.FRICTION);
    }
}
