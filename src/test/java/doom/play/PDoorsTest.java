package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.render.Side;
import doom.system.Doomdef;
import doom.system.Dstrings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PDoorsTest {

    @BeforeEach
    void reset() {
        PTick.pInitThinkers();
        PMap.changeSectorResult = false;
        PTick.leveltime = 0;
        PSetup.numsectors = 0;
        PSetup.sectors = null;
    }

    // --- T_VerticalDoor direction 0 (waiting at top) ---

    // Normal door: countdown > 0 just decrements, no transition.
    @Test
    void tVerticalDoorWaitingDecrements() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 0;
        d.topcountdown = 5;
        d.type = PDoors.normal;

        PDoors.tVerticalDoor(d);

        assertEquals(4, d.topcountdown);
        assertEquals(0, d.direction);
    }

    // Normal: countdown hits 0 → flip to -1 (start closing).
    @Test
    void tVerticalDoorNormalStartsClosingAtTimeout() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 0;
        d.topcountdown = 1;
        d.type = PDoors.normal;

        PDoors.tVerticalDoor(d);

        assertEquals(-1, d.direction);
    }

    // blazeRaise: same, flips to -1.
    @Test
    void tVerticalDoorBlazeRaiseClosesAtTimeout() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 0;
        d.topcountdown = 1;
        d.type = PDoors.blazeRaise;

        PDoors.tVerticalDoor(d);

        assertEquals(-1, d.direction);
    }

    // close30ThenOpen: unique — timeout flips to +1 (reopen).
    @Test
    void tVerticalDoorClose30ThenOpenReopensAtTimeout() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 0;
        d.topcountdown = 1;
        d.type = PDoors.close30ThenOpen;

        PDoors.tVerticalDoor(d);

        assertEquals(1, d.direction);
    }

    // --- direction 2 (initial wait, raiseIn5Mins only) ---

    @Test
    void tVerticalDoorInitialWaitDecrementsOnRaiseIn5Mins() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 2;
        d.topcountdown = 10;
        d.type = PDoors.raiseIn5Mins;

        PDoors.tVerticalDoor(d);

        assertEquals(9, d.topcountdown);
        assertEquals(2, d.direction);
    }

    // raiseIn5Mins timeout: direction → 1, type → normal (so on close
    // it behaves as a regular door thereafter).
    @Test
    void tVerticalDoorRaiseIn5MinsTimeoutConvertsToNormal() {
        Sector sec = new Sector();
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 2;
        d.topcountdown = 1;
        d.type = PDoors.raiseIn5Mins;

        PDoors.tVerticalDoor(d);

        assertEquals(1, d.direction);
        assertEquals(PDoors.normal, d.type);
    }

    // --- direction -1 (DOWN) ---

    // Down, not past dest: just moves.
    @Test
    void tVerticalDoorDownMovesOneStep() {
        Sector sec = new Sector();
        sec.ceilingheight = 100;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;
        d.type = PDoors.normal;

        PDoors.tVerticalDoor(d);

        assertEquals(90, sec.ceilingheight);
        assertEquals(-1, d.direction);
    }

    // Down reaches floor (pastdest) for `normal` → remove door +
    // clear specialdata.
    @Test
    void tVerticalDoorDownPastDestRemovesNormal() {
        Sector sec = new Sector();
        sec.ceilingheight = 5;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;    // overshoots 0
        d.type = PDoors.normal;
        sec.specialdata = d;
        PTick.pAddThinker(d);

        PDoors.tVerticalDoor(d);

        assertEquals(0, sec.ceilingheight);
        assertNull(sec.specialdata);
        assertTrue(d.removed);
    }

    // Down + pastdest + close30ThenOpen: reset to waiting with
    // 35*30 tic countdown (the 30-second delay).
    @Test
    void tVerticalDoorDownPastDestClose30ThenOpenResetsToWait() {
        Sector sec = new Sector();
        sec.ceilingheight = 5;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;
        d.type = PDoors.close30ThenOpen;

        PDoors.tVerticalDoor(d);

        assertEquals(0, d.direction);
        assertEquals(35 * 30, d.topcountdown);
    }

    // Down + crushed (blocked by mobj) on `normal` → reopen (direction=1).
    @Test
    void tVerticalDoorDownCrushedReopensOnNormal() {
        Sector sec = new Sector();
        sec.ceilingheight = 50;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;
        d.type = PDoors.normal;
        PMap.changeSectorResult = true; // force crush return

        PDoors.tVerticalDoor(d);

        assertEquals(1, d.direction);
    }

    // Down + crushed on `close` (DO NOT GO BACK UP) → keeps going down.
    @Test
    void tVerticalDoorDownCrushedCloseStaysDown() {
        Sector sec = new Sector();
        sec.ceilingheight = 50;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;
        d.type = PDoors.close;
        PMap.changeSectorResult = true;

        PDoors.tVerticalDoor(d);

        assertEquals(-1, d.direction); // unchanged
    }

    // blazeClose same: no reopen on crush.
    @Test
    void tVerticalDoorDownCrushedBlazeCloseStaysDown() {
        Sector sec = new Sector();
        sec.ceilingheight = 50;
        sec.floorheight = 0;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = -1;
        d.speed = 10;
        d.type = PDoors.blazeClose;
        PMap.changeSectorResult = true;

        PDoors.tVerticalDoor(d);

        assertEquals(-1, d.direction);
    }

    // --- direction 1 (UP) ---

    // Up, not past dest: moves one step.
    @Test
    void tVerticalDoorUpMovesOneStep() {
        Sector sec = new Sector();
        sec.ceilingheight = 50;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 1;
        d.speed = 10;
        d.topheight = 200;
        d.type = PDoors.normal;

        PDoors.tVerticalDoor(d);

        assertEquals(60, sec.ceilingheight);
        assertEquals(1, d.direction);
    }

    // Up past dest on `normal` → waiting at top, topcountdown = topwait.
    @Test
    void tVerticalDoorUpPastDestNormalWaitsAtTop() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 1;
        d.speed = 10;       // overshoots
        d.topheight = 200;
        d.topwait = 150;
        d.type = PDoors.normal;

        PDoors.tVerticalDoor(d);

        assertEquals(200, sec.ceilingheight);
        assertEquals(0, d.direction);
        assertEquals(150, d.topcountdown);
    }

    // Up past dest on `open` → remove door (no wait, no close).
    @Test
    void tVerticalDoorUpPastDestOpenRemoves() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 1;
        d.speed = 10;
        d.topheight = 200;
        d.type = PDoors.open;
        sec.specialdata = d;
        PTick.pAddThinker(d);

        PDoors.tVerticalDoor(d);

        assertEquals(200, sec.ceilingheight);
        assertNull(sec.specialdata);
        assertTrue(d.removed);
    }

    // Up past dest on `close30ThenOpen` → remove door (one-shot reopen).
    @Test
    void tVerticalDoorUpPastDestClose30ThenOpenRemoves() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 1;
        d.speed = 10;
        d.topheight = 200;
        d.type = PDoors.close30ThenOpen;
        sec.specialdata = d;
        PTick.pAddThinker(d);

        PDoors.tVerticalDoor(d);

        assertNull(sec.specialdata);
        assertTrue(d.removed);
    }

    // blazeRaise at top: waits then closes (same as normal).
    @Test
    void tVerticalDoorUpPastDestBlazeRaiseWaits() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PDoors.VLDoor d = new PDoors.VLDoor();
        d.sector = sec;
        d.direction = 1;
        d.speed = 10;
        d.topheight = 200;
        d.topwait = 150;
        d.type = PDoors.blazeRaise;

        PDoors.tVerticalDoor(d);

        assertEquals(0, d.direction);
        assertEquals(150, d.topcountdown);
    }

    // --- EV_DoDoor ---

    // normal: spawns door, topheight = lowestCeilingSurrounding - 4.
    @Test
    void evDoDoorNormalSeedsDoor() {
        Sector sec = new Sector();
        sec.tag = 10;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 10;

        int rtn = PDoors.evDoDoor(line, PDoors.normal);

        assertEquals(1, rtn);
        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertNotNull(d);
        assertEquals(1, d.direction);
        assertEquals(PDoors.VDOORSPEED, d.speed);
        assertEquals(PDoors.VDOORWAIT, d.topwait);
        assertEquals(PDoors.normal, d.type);
        // Isolated sector: pFindLowestCeilingSurrounding returns MAXINT.
        // -4*FRACUNIT off that would overflow; just verify direction/type.
    }

    // blazeRaise: speed = VDOORSPEED * 4, direction = 1.
    @Test
    void evDoDoorBlazeRaiseQuadruplesSpeed() {
        Sector sec = new Sector();
        sec.tag = 11;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 11;

        PDoors.evDoDoor(line, PDoors.blazeRaise);

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertEquals(PDoors.VDOORSPEED * 4, d.speed);
        assertEquals(1, d.direction);
    }

    // close: direction = -1.
    @Test
    void evDoDoorCloseStartsGoingDown() {
        Sector sec = new Sector();
        sec.tag = 12;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 12;

        PDoors.evDoDoor(line, PDoors.close);

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertEquals(-1, d.direction);
        assertEquals(PDoors.VDOORSPEED, d.speed);
    }

    // close30ThenOpen: topheight = sec.ceilingheight (not -4 offset).
    @Test
    void evDoDoorClose30ThenOpenUsesCurrentCeiling() {
        Sector sec = new Sector();
        sec.tag = 13;
        sec.ceilingheight = 200;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 13;

        PDoors.evDoDoor(line, PDoors.close30ThenOpen);

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertEquals(200, d.topheight);
        assertEquals(-1, d.direction);
    }

    // Sector with existing specialdata is skipped.
    @Test
    void evDoDoorSkipsSectorsWithSpecialData() {
        Sector sec = new Sector();
        sec.tag = 14;
        sec.specialdata = new Object();
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 14;

        int rtn = PDoors.evDoDoor(line, PDoors.normal);

        assertEquals(0, rtn);
    }

    // --- P_SpawnDoorCloseIn30 ---

    @Test
    void spawnDoorCloseIn30SeedsWaitState() {
        Sector sec = new Sector();
        sec.special = 99;

        PDoors.pSpawnDoorCloseIn30(sec);

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertNotNull(d);
        assertEquals(0, d.direction);          // waiting
        assertEquals(PDoors.normal, d.type);
        assertEquals(30 * 35, d.topcountdown); // 30 seconds
        assertEquals(PDoors.VDOORSPEED, d.speed);
        assertEquals(0, sec.special);           // cleared
        assertSame(d.function.acp1, d.function.acp1); // bound
        assertNotNull(d.function.acp1);
    }

    // --- P_SpawnDoorRaiseIn5Mins ---

    @Test
    void spawnDoorRaiseIn5MinsSeedsInitialWaitState() {
        Sector sec = new Sector();
        sec.special = 50;
        sec.ceilingheight = 0;
        sec.lines = new Line[0];
        sec.linecount = 0;

        PDoors.pSpawnDoorRaiseIn5Mins(sec, 0);

        PDoors.VLDoor d = (PDoors.VLDoor) sec.specialdata;
        assertNotNull(d);
        assertEquals(2, d.direction);            // INITIAL WAIT
        assertEquals(PDoors.raiseIn5Mins, d.type);
        assertEquals(5 * 60 * 35, d.topcountdown); // 5 minutes
        assertEquals(PDoors.VDOORWAIT, d.topwait);
        assertEquals(0, sec.special);             // cleared
    }

    // ----- EV_DoLockedDoor -----

    // Helper: build a player-owning Mobj with the requested cards set.
    private static Mobj playerWithCards(int... heldCards) {
        Mobj m = new Mobj();
        m.player = new Player();
        for (int c : heldCards) {
            m.player.cards[c] = true;
        }
        return m;
    }

    // Helper: 1-sector world, tag-matched.
    private static Line worldOneSector(int special, int tag) {
        Sector sec = new Sector();
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.tag = tag;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.special = special;
        line.tag = tag;
        return line;
    }

    // No player on mobj → 0, no message, no door.
    @Test
    void evDoLockedDoorWithoutPlayerReturnsZero() {
        Line line = worldOneSector(99, 7);
        Mobj nonPlayer = new Mobj();   // no .player

        int rtn = PDoors.evDoLockedDoor(line, PDoors.blazeOpen, nonPlayer);

        assertEquals(0, rtn);
        assertNull(PSetup.sectors[0].specialdata);
    }

    // Blue lock, no card → message PD_BLUEO, returns 0, no door spawned.
    @Test
    void evDoLockedDoorBlueWithoutCardSetsMessage() {
        Line line = worldOneSector(99, 7);
        Mobj p = playerWithCards();   // empty inventory

        int rtn = PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p);

        assertEquals(0, rtn);
        assertEquals(Dstrings.PD_BLUEO, p.player.message);
        assertNull(PSetup.sectors[0].specialdata);
    }

    // Blue lock with bluecard → falls through to EV_DoDoor (returns 1, spawns).
    @Test
    void evDoLockedDoorBlueWithCardSpawnsDoor() {
        Line line = worldOneSector(99, 7);
        Mobj p = playerWithCards(Doomdef.it_bluecard);

        int rtn = PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p);

        assertEquals(1, rtn);
        assertNotNull(PSetup.sectors[0].specialdata);
    }

    // Blueskull also unlocks (either-or).
    @Test
    void evDoLockedDoorBlueWithSkullSpawnsDoor() {
        Line line = worldOneSector(133, 7);
        Mobj p = playerWithCards(Doomdef.it_blueskull);

        assertEquals(1, PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p));
    }

    // Red lock paths.
    @Test
    void evDoLockedDoorRedNeedsRedKey() {
        Line line = worldOneSector(134, 8);
        Mobj p = playerWithCards();

        assertEquals(0, PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p));
        assertEquals(Dstrings.PD_REDO, p.player.message);

        line.special = 134; // unchanged
        Mobj p2 = playerWithCards(Doomdef.it_redskull);
        assertEquals(1, PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p2));
    }

    // Yellow lock paths.
    @Test
    void evDoLockedDoorYellowNeedsYellowKey() {
        Line line = worldOneSector(136, 9);
        Mobj p = playerWithCards();

        assertEquals(0, PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p));
        assertEquals(Dstrings.PD_YELLOWO, p.player.message);

        Mobj p2 = playerWithCards(Doomdef.it_yellowcard);
        assertEquals(1, PDoors.evDoLockedDoor(line, PDoors.blazeOpen, p2));
    }

    // ----- EV_VerticalDoor -----

    // Helper: a 1-sector "use" world. The line's side[1^side] sidedef
    // points to `sec` so EV_VerticalDoor can pick the sector up.
    private static Line useDoorWorld(int special) {
        Sector sec = new Sector();
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Side back = new Side();
        back.sector = sec;
        // side[0]=front (unused here), side[1]=back (the one EV_VerticalDoor reads).
        PSetup.sides = new Side[] { new Side(), back };
        PSetup.numsides = 2;

        Line line = new Line();
        line.special = special;
        line.sidenum[0] = 0;
        line.sidenum[1] = 1;
        return line;
    }

    // No player + lock → return without spawning.
    @Test
    void evVerticalDoorBlueLockNoPlayerNoDoor() {
        Line line = useDoorWorld(26);
        Mobj nonPlayer = new Mobj();

        PDoors.evVerticalDoor(line, nonPlayer);

        assertNull(PSetup.sectors[0].specialdata);
    }

    // Blue lock + no key → message PD_BLUEK, no door.
    @Test
    void evVerticalDoorBlueLockNoKeySetsMessage() {
        Line line = useDoorWorld(26);
        Mobj p = playerWithCards();

        PDoors.evVerticalDoor(line, p);

        assertEquals(Dstrings.PD_BLUEK, p.player.message);
        assertNull(PSetup.sectors[0].specialdata);
    }

    // Blue lock + bluecard → spawns a normal door (type 26 → normal).
    @Test
    void evVerticalDoorBlueLockWithKeySpawnsNormalDoor() {
        Line line = useDoorWorld(26);
        Mobj p = playerWithCards(Doomdef.it_bluecard);

        PDoors.evVerticalDoor(line, p);

        PDoors.VLDoor d = (PDoors.VLDoor) PSetup.sectors[0].specialdata;
        assertNotNull(d);
        assertEquals(PDoors.normal, d.type);
        assertEquals(1, d.direction);             // going up
        assertEquals(PDoors.VDOORSPEED, d.speed);
    }

    // "Open" flavor (line.special=32 with bluecard): door type=open AND
    // line.special is cleared to 0 (one-shot).
    @Test
    void evVerticalDoorOpenFlavorClearsLineSpecial() {
        Line line = useDoorWorld(32);
        Mobj p = playerWithCards(Doomdef.it_bluecard);

        PDoors.evVerticalDoor(line, p);

        PDoors.VLDoor d = (PDoors.VLDoor) PSetup.sectors[0].specialdata;
        assertEquals(PDoors.open, d.type);
        assertEquals(0, line.special);
    }

    // line.special=1 (manual raise) — no lock, normal door spawn.
    @Test
    void evVerticalDoorSpecial1SpawnsNormalDoor() {
        Line line = useDoorWorld(1);
        Mobj p = playerWithCards();   // no key needed for unlocked

        PDoors.evVerticalDoor(line, p);

        PDoors.VLDoor d = (PDoors.VLDoor) PSetup.sectors[0].specialdata;
        assertNotNull(d);
        assertEquals(PDoors.normal, d.type);
    }

    // Re-press while existing door is moving DOWN: flips back to UP.
    @Test
    void evVerticalDoorRepressOnClosingDoorReversesUp() {
        Line line = useDoorWorld(1);
        // Pre-seed an existing closing door on the sector.
        PDoors.VLDoor existing = new PDoors.VLDoor();
        existing.direction = -1;
        existing.sector = PSetup.sectors[0];
        PSetup.sectors[0].specialdata = existing;

        Mobj p = playerWithCards();

        PDoors.evVerticalDoor(line, p);

        // Existing door was reversed; no new door created (specialdata
        // still references the same VLDoor).
        assertSame(existing, PSetup.sectors[0].specialdata);
        assertEquals(1, existing.direction);
    }

    // Re-press while door is going UP / waiting (direction != -1):
    // player triggers immediate close.
    @Test
    void evVerticalDoorRepressOnOpenDoorByPlayerStartsClose() {
        Line line = useDoorWorld(1);
        PDoors.VLDoor existing = new PDoors.VLDoor();
        existing.direction = 1; // going up
        existing.sector = PSetup.sectors[0];
        PSetup.sectors[0].specialdata = existing;

        Mobj p = playerWithCards();

        PDoors.evVerticalDoor(line, p);

        assertSame(existing, PSetup.sectors[0].specialdata);
        assertEquals(-1, existing.direction);
    }

    // Re-press by NON-PLAYER on a non-closing door: bad guys never close
    // doors → no change.
    @Test
    void evVerticalDoorRepressByMonsterDoesNothing() {
        Line line = useDoorWorld(1);
        PDoors.VLDoor existing = new PDoors.VLDoor();
        existing.direction = 0;  // waiting
        existing.sector = PSetup.sectors[0];
        PSetup.sectors[0].specialdata = existing;

        Mobj monster = new Mobj();   // no .player

        PDoors.evVerticalDoor(line, monster);

        // Direction unchanged.
        assertEquals(0, existing.direction);
        assertSame(existing, PSetup.sectors[0].specialdata);
    }

    // Re-press on an "open"-type door (line.special outside the
    // re-trigger set 1/26/27/28/117) does NOT alter the existing door —
    // the early-return falls through to the new-door spawn. But because
    // sec.specialdata is non-null the new spawn would clobber it. The
    // C C does spawn a second door in that case (no `continue`); we
    // mirror that.
    @Test
    void evVerticalDoorOnOpenStyleClobbersExistingDoor() {
        Line line = useDoorWorld(31);   // "open" style (clears special)
        PDoors.VLDoor existing = new PDoors.VLDoor();
        existing.direction = 0;
        existing.sector = PSetup.sectors[0];
        PSetup.sectors[0].specialdata = existing;

        Mobj p = playerWithCards();

        PDoors.evVerticalDoor(line, p);

        // The new door has type=open and replaces the existing reference.
        PDoors.VLDoor d = (PDoors.VLDoor) PSetup.sectors[0].specialdata;
        assertNotNull(d);
        assertEquals(PDoors.open, d.type);
        assertTrue(d != existing);
    }

    // Blazing raise (117): speed = 4× VDOORSPEED.
    @Test
    void evVerticalDoorBlazeRaiseUsesQuadrupleSpeed() {
        Line line = useDoorWorld(117);
        Mobj p = playerWithCards();

        PDoors.evVerticalDoor(line, p);

        PDoors.VLDoor d = (PDoors.VLDoor) PSetup.sectors[0].specialdata;
        assertEquals(PDoors.blazeRaise, d.type);
        assertEquals(PDoors.VDOORSPEED * 4, d.speed);
    }
}
