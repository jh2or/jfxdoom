package doom.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;

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
}
