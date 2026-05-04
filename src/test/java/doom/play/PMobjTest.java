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
        PMap.changeSectorOverride = true;
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
        assertEquals(128, PMobj.ITEMQUESIZE);
    }

    // ----- P_RemoveMobj item-respawn enqueue -----

    @BeforeEach
    void resetItemQueue() {
        // Clear the ring buffer between tests.
        for (int i = 0; i < PMobj.ITEMQUESIZE; i++) {
            PMobj.itemrespawnque[i] = null;
            PMobj.itemrespawntime[i] = 0;
        }
        PMobj.iquehead = 0;
        PMobj.iquetail = 0;
        PTick.leveltime = 0;
        Doomstat.deathmatch = 0;
    }

    // MF_SPECIAL && !MF_DROPPED && not MT_INV/MT_INS → enqueue.
    @Test
    void removeMobjEnqueuesSpecialItemForRespawn() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        // Manually craft an MF_SPECIAL pickup. MT_CLIP (ammo clip)
        // is special by default.
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_CLIP);
        m.spawnpoint = new Mapthing(10, 20, 90, 2007, 0);
        PTick.leveltime = 1234;

        PMobj.pRemoveMobj(m);

        assertEquals(1, PMobj.iquehead);
        assertSame(m.spawnpoint, PMobj.itemrespawnque[0]);
        assertEquals(1234, PMobj.itemrespawntime[0]);
    }

    // MF_DROPPED items (e.g. ammo dropped by enemies) skip the queue.
    @Test
    void removeMobjSkipsDroppedItems() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_CLIP);
        m.flags |= Mobj.MF_DROPPED;
        m.spawnpoint = new Mapthing(0, 0, 0, 0, 0);

        PMobj.pRemoveMobj(m);

        assertEquals(0, PMobj.iquehead);  // not enqueued
    }

    // Non-MF_SPECIAL mobjs (monsters, props) don't enqueue.
    @Test
    void removeMobjSkipsNonSpecialMobjs() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Mobj m = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        // Possessed has no MF_SPECIAL.
        assertEquals(0, m.flags & Mobj.MF_SPECIAL);

        PMobj.pRemoveMobj(m);

        assertEquals(0, PMobj.iquehead);
    }

    // ----- P_RespawnSpecials -----

    // Skipped unless deathmatch == 2 (-altdeath).
    @Test
    void respawnSpecialsNoOpUnlessAltdeath() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.deathmatch = 1;

        // Pretend the queue has an entry.
        PMobj.itemrespawnque[0] = new Mapthing(0, 0, 0, 2007, 0);
        PMobj.itemrespawntime[0] = 0;
        PMobj.iquehead = 1;
        PTick.leveltime = 60 * 35;

        int before = countMobjThinkers();
        PMobj.pRespawnSpecials();
        // No new mobj spawned.
        assertEquals(before, countMobjThinkers());
    }

    // Skipped if the head item has aged < 30 seconds.
    @Test
    void respawnSpecialsWaits30Seconds() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.deathmatch = 2;

        PMobj.itemrespawnque[0] = new Mapthing(0, 0, 0, 2007, 0);
        PMobj.itemrespawntime[0] = 0;
        PMobj.iquehead = 1;
        PTick.leveltime = 29 * 35;  // not yet 30s

        int before = countMobjThinkers();
        PMobj.pRespawnSpecials();
        assertEquals(before, countMobjThinkers());
        assertEquals(0, PMobj.iquetail);  // queue tail didn't advance
    }

    // Aged enough → spawns IFOG + the item, advances tail.
    @Test
    void respawnSpecialsSpawnsItemAfter30Seconds() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.deathmatch = 2;

        // doomednum 2007 = MT_CLIP (clip ammo).
        PMobj.itemrespawnque[0] = new Mapthing(0, 0, 0, 2007, 0);
        PMobj.itemrespawntime[0] = 0;
        PMobj.iquehead = 1;
        PTick.leveltime = 35 * 35;  // 35 seconds

        int before = countMobjThinkers();
        PMobj.pRespawnSpecials();

        // Two mobjs spawned: an IFOG and the item.
        assertEquals(before + 2, countMobjThinkers());
        assertEquals(1, PMobj.iquetail);  // tail advanced past entry
    }

    // ----- P_NightmareRespawn -----

    @Test
    void nightmareRespawnRecreatesMobjAtSpawnpoint() {
        Sector sec = openWorld(0, 200 * Fixed.FRACUNIT);

        // Spawn a possessed monster at (0, 0); pretend it had been
        // placed by P_SpawnMapThing at the same position.
        Mobj orig = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_POSSESSED);
        orig.spawnpoint = new Mapthing(0, 0, /*angle*/ 90, /*type*/ 3004,
                /*options*/ Mapthing.MTF_AMBUSH);

        int before = countMobjThinkers();

        PMobj.pNightmareRespawn(orig);

        // Original is removed, two TFOGs + new monster = 3 new mobjs,
        // 1 removal. Net: +3 alive, -1 removal -> +2 mobjs alive.
        // (Removed thinkers stay until P_RunThinkers sweeps; just
        // verify the new monster and that the original is marked.)
        assertTrue(orig.removed);
        // A new MT_POSSESSED with MF_AMBUSH set was created.
        Mobj newest = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_POSSESSED, newest.type);
        assertNotSame(orig, newest);
        assertTrue((newest.flags & Mobj.MF_AMBUSH) != 0);
        assertEquals(18, newest.reactiontime);
    }

    // Helper: count mobj-thinkers in the active list.
    private static int countMobjThinkers() {
        int n = 0;
        for (Thinker t = PTick.thinkercap.next;
                t != PTick.thinkercap;
                t = t.next) {
            if (t instanceof Mobj && !t.removed) {
                n++;
            }
        }
        return n;
    }

    // ----- P_SpawnMissile / P_SpawnPlayerMissile -----

    // Spawning a missile from source toward dest sets target =
    // source, momentum aligned toward dest, and registers as a
    // thinker.
    @Test
    void spawnMissileTargetsAndAngles() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj source = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        // Place dest to the east at +500 fixed-units.
        Mobj dest = PMobj.pSpawnMobj(500 * Fixed.FRACUNIT, 0,
                PMobj.ONFLOORZ, Info.MT_POSSESSED);

        Mobj missile = PMobj.pSpawnMissile(source, dest, Info.MT_TROOPSHOT);
        assertNotNull(missile);
        assertSame(source, missile.target);
        // East-facing momentum: momx > 0, momy ~ 0.
        assertTrue(missile.momx > 0,
                "expected +momx for east-facing missile, was " + missile.momx);
        assertTrue(Math.abs(missile.momy) < missile.info.speed / 8,
                "expected near-zero momy, was " + missile.momy);
    }

    // pSpawnPlayerMissile auto-aims; with no targets in range the
    // C drops to slope=0 and uses the source's facing angle. Verify
    // the missile spawns + targets the source.
    @Test
    void spawnPlayerMissileSpawnsForwardWithoutTarget() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mobj source = PMobj.pSpawnMobj(0, 0, PMobj.ONFLOORZ, Info.MT_PLAYER);
        source.angle = 0; // east
        // Empty world — no auto-aim target.

        int beforeThinkers = countMobjThinkers();
        PMobj.pSpawnPlayerMissile(source, Info.MT_TROOPSHOT);

        // A missile thinker was added.
        assertTrue(countMobjThinkers() > beforeThinkers);
        // The most-recent mobj thinker is the missile.
        Mobj last = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_TROOPSHOT, last.type);
        assertSame(source, last.target);
    }

    // ----- P_SpawnPlayer + P_SpawnMapThing -----

    @BeforeEach
    void resetPlayerState() {
        // Default to all four seats inactive — tests opt-in.
        for (int i = 0; i < doom.system.Doomdef.MAXPLAYERS; i++) {
            doom.system.GGame.playeringame[i] = false;
            doom.system.GGame.players[i] = new Player();
        }
        doom.system.GGame.consoleplayer = 0;
        Doomstat.totalkills = 0;
        Doomstat.totalitems = 0;
        Doomstat.netgame = false;
        Doomstat.nomonsters = false;
        // Wipe deathmatch / playerstart pools.
        for (int i = 0; i < PSetup.MAX_DEATHMATCH_STARTS; i++) {
            PSetup.deathmatchstarts[i] = null;
        }
        PSetup.deathmatchPIndex = 0;
        for (int i = 0; i < doom.system.Doomdef.MAXPLAYERS; i++) {
            PSetup.playerstarts[i] = null;
        }
    }

    // Player 1 spawn (mthing.type == 1) wires Player.mo and the
    // visible playerstate fields.
    @Test
    void spawnPlayerWiresPlayerAndMobj() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        doom.system.GGame.playeringame[0] = true;
        Player p = doom.system.GGame.players[0];
        p.health = 100;

        Mapthing mt = new Mapthing(0, 0, /*angle*/ 90, /*type*/ 1, /*options*/ 0);
        PMobj.pSpawnPlayer(mt);

        assertNotNull(p.mo);
        assertSame(p, p.mo.player);
        assertEquals(100, p.mo.health);
        assertEquals(Player.PST_LIVE, p.playerstate);
        assertEquals(PMobj.VIEWHEIGHT, p.viewheight);
        assertEquals(0, p.refire);
        assertNull(p.message);
    }

    // Player not in game (playeringame[]==false) → no spawn.
    @Test
    void spawnPlayerSkipsWhenNotInGame() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        doom.system.GGame.playeringame[0] = false;

        Mapthing mt = new Mapthing(0, 0, 0, 1, 0);
        PMobj.pSpawnPlayer(mt);

        assertNull(doom.system.GGame.players[0].mo);
    }

    // Players 2-4 get the color-translation flag bits set.
    @Test
    void spawnPlayer2GetsTransshiftFlag() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        doom.system.GGame.playeringame[1] = true;
        doom.system.GGame.players[1].health = 100;

        Mapthing mt = new Mapthing(0, 0, 0, 2, 0);
        PMobj.pSpawnPlayer(mt);

        Mobj mo = doom.system.GGame.players[1].mo;
        assertNotNull(mo);
        // (type-1) << MF_TRANSSHIFT bits land in flags.
        int expected = (2 - 1) << Mobj.MF_TRANSSHIFT;
        assertEquals(expected, mo.flags & Mobj.MF_TRANSLATION);
    }

    // PST_REBORN flips back to LIVE via gPlayerReborn before spawn.
    @Test
    void spawnPlayerHonorsPstRebornBranch() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        doom.system.GGame.playeringame[0] = true;
        doom.system.GGame.players[0].playerstate = Player.PST_REBORN;

        Mapthing mt = new Mapthing(0, 0, 0, 1, 0);
        PMobj.pSpawnPlayer(mt);

        // gPlayerReborn restores health=100, then SpawnPlayer flips
        // playerstate to LIVE.
        assertEquals(Player.PST_LIVE, doom.system.GGame.players[0].playerstate);
        assertEquals(100, doom.system.GGame.players[0].mo.health);
    }

    // Deathmatch start (type=11) appends to deathmatchstarts.
    @Test
    void spawnMapThingDeathmatchStartAppendsToPool() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        Mapthing dm = new Mapthing(100, 200, 45, 11, 0);
        PMobj.pSpawnMapThing(dm);

        assertEquals(1, PSetup.deathmatchPIndex);
        assertNotNull(PSetup.deathmatchstarts[0]);
        // Source mthing must be defensively copied — mutating it must
        // not bleed back into the pool.
        assertNotSame(dm, PSetup.deathmatchstarts[0]);
        assertEquals(100, PSetup.deathmatchstarts[0].x);
        assertEquals(200, PSetup.deathmatchstarts[0].y);
    }

    // Deathmatch pool is bounded at MAX_DEATHMATCH_STARTS=10.
    @Test
    void spawnMapThingDeathmatchStartCapsAt10() {
        openWorld(0, 200 * Fixed.FRACUNIT);

        for (int i = 0; i < 12; i++) {
            PMobj.pSpawnMapThing(new Mapthing(i, i, 0, 11, 0));
        }

        assertEquals(10, PSetup.deathmatchPIndex);
    }

    // Player-start (type 1-4) records the spot AND, in cooperative
    // (deathmatch == 0), spawns the player mobj.
    @Test
    void spawnMapThingPlayerStartRecordsSpotAndSpawnsCoop() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.deathmatch = 0;
        doom.system.GGame.playeringame[0] = true;
        doom.system.GGame.players[0].health = 100;

        PMobj.pSpawnMapThing(new Mapthing(50, 60, 0, 1, 0));

        assertNotNull(PSetup.playerstarts[0]);
        assertEquals(50, PSetup.playerstarts[0].x);
        // Coop spawn fired.
        assertNotNull(doom.system.GGame.players[0].mo);
    }

    // In deathmatch the player-start is recorded but NOT spawned —
    // G_DeathMatchSpawnPlayer handles that later.
    @Test
    void spawnMapThingPlayerStartInDeathmatchSkipsCoopSpawn() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.deathmatch = 1;
        doom.system.GGame.playeringame[0] = true;

        PMobj.pSpawnMapThing(new Mapthing(50, 60, 0, 1, 0));

        assertNotNull(PSetup.playerstarts[0]);
        assertNull(doom.system.GGame.players[0].mo);
    }

    // A monster (type 3004 = Former Human) at medium skill (bit 2)
    // spawns + bumps totalkills + uses MF_AMBUSH if option set.
    @Test
    void spawnMapThingMonsterSpawnsAtAppropriateSkill() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_medium;

        // options bit 2 = present at medium-skill.
        PMobj.pSpawnMapThing(new Mapthing(0, 0, 0, /*type*/ 3004,
                /*options*/ 2 | Mapthing.MTF_AMBUSH));

        assertEquals(1, Doomstat.totalkills);
        Mobj mo = (Mobj) PTick.thinkercap.prev;
        assertEquals(Info.MT_POSSESSED, mo.type);
        assertTrue((mo.flags & Mobj.MF_AMBUSH) != 0);
        // spawnpoint is the per-mobj defensive copy.
        assertNotNull(mo.spawnpoint);
        assertEquals(3004, mo.spawnpoint.type);
    }

    // Skill filter: options=1 (baby only) → no spawn at sk_medium.
    @Test
    void spawnMapThingSkillFilterRejectsWrongTier() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_medium;

        int before = countMobjThinkers();
        PMobj.pSpawnMapThing(new Mapthing(0, 0, 0, 3004, /*baby only*/ 1));

        assertEquals(before, countMobjThinkers());
        assertEquals(0, Doomstat.totalkills);
    }

    // Multiplayer-only flag (bit 16) skips in single-player.
    @Test
    void spawnMapThingMultiplayerOnlySkipsInSinglePlayer() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.netgame = false;

        int before = countMobjThinkers();
        PMobj.pSpawnMapThing(new Mapthing(0, 0, 0, 3004, /*multi+medium*/ 16 | 2));

        assertEquals(before, countMobjThinkers());
    }

    // -nomonsters skips MF_COUNTKILL types (and MT_SKULL).
    @Test
    void spawnMapThingNomonstersSkipsCountkillTypes() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_medium;
        Doomstat.nomonsters = true;

        int before = countMobjThinkers();
        PMobj.pSpawnMapThing(new Mapthing(0, 0, 0, 3004, 2));

        assertEquals(before, countMobjThinkers());
        assertEquals(0, Doomstat.totalkills);
    }

    // Items (MF_COUNTITEM) get totalitems++ on spawn. doomednum 2013
    // is MT_MISC12 (Soulsphere) — flagged MF_SPECIAL|MF_COUNTITEM.
    // Green armor (2018) is MF_SPECIAL only and would not bump.
    @Test
    void spawnMapThingItemBumpsTotalitems() {
        openWorld(0, 200 * Fixed.FRACUNIT);
        Doomstat.gameskill = Doomstat.sk_medium;

        PMobj.pSpawnMapThing(new Mapthing(0, 0, 0, /*soulsphere*/ 2013,
                /*all skills*/ 7));

        assertEquals(1, Doomstat.totalitems);
    }
}
