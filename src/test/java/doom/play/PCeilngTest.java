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

class PCeilngTest {

    @BeforeEach
    void reset() {
        for (int i = 0; i < PCeilng.MAXCEILINGS; i++) {
            PCeilng.activeceilings[i] = null;
        }
        PTick.pInitThinkers();
        // Plane-mover tests drive the crush path via changeSectorResult,
        // not by spinning up a fully-populated blockmap. Engage the
        // override to short-circuit pChangeSector's real body.
        PMap.changeSectorOverride = true;
        PMap.changeSectorResult = false;
        PTick.leveltime = 0;
        PSetup.numsectors = 0;
        PSetup.sectors = null;
        PSetup.numlines = 0;
        PSetup.lines = null;
    }

    // addActiveCeiling drops into the first null slot.
    @Test
    void addActiveCeilingFillsFirstNullSlot() {
        PCeilng.Ceiling c = new PCeilng.Ceiling();

        PCeilng.pAddActiveCeiling(c);

        assertSame(c, PCeilng.activeceilings[0]);
    }

    @Test
    void addActiveCeilingSkipsFilledSlots() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        PCeilng.Ceiling b = new PCeilng.Ceiling();
        PCeilng.activeceilings[0] = a;

        PCeilng.pAddActiveCeiling(b);

        assertSame(a, PCeilng.activeceilings[0]);
        assertSame(b, PCeilng.activeceilings[1]);
    }

    // removeActiveCeiling clears the slot + sector.specialdata + unlinks
    // the thinker via removed flag (see deviation 14).
    @Test
    void removeActiveCeilingClearsSlotAndSpecialData() {
        Sector sec = new Sector();
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        sec.specialdata = c;
        PTick.pAddThinker(c);
        PCeilng.activeceilings[3] = c;

        PCeilng.pRemoveActiveCeiling(c);

        assertNull(PCeilng.activeceilings[3]);
        assertNull(sec.specialdata);
        assertTrue(c.removed);
    }

    // evCeilingCrushStop flips matching ceiling to in-stasis, saves
    // olddirection, nulls acp1, and returns 1 when at least one matched.
    @Test
    void evCeilingCrushStopFreezesMatchingTag() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        a.tag = 5;
        a.direction = -1;
        a.function.acp1 = PCeilng::tMoveCeiling;
        PCeilng.activeceilings[0] = a;

        Line line = new Line();
        line.tag = 5;

        int rtn = PCeilng.evCeilingCrushStop(line);

        assertEquals(1, rtn);
        assertEquals(0, a.direction);
        assertEquals(-1, a.olddirection);
        assertNull(a.function.acp1);
    }

    // Non-matching tags: ceiling untouched, returns 0.
    @Test
    void evCeilingCrushStopReturnsZeroWhenNoMatch() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        a.tag = 5;
        a.direction = 1;
        a.function.acp1 = PCeilng::tMoveCeiling;
        PCeilng.activeceilings[0] = a;

        Line line = new Line();
        line.tag = 9;

        int rtn = PCeilng.evCeilingCrushStop(line);

        assertEquals(0, rtn);
        assertEquals(1, a.direction); // untouched
        assertNotNull(a.function.acp1);
    }

    // Already-paused ceiling (direction == 0) is skipped.
    @Test
    void evCeilingCrushStopSkipsAlreadyPaused() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        a.tag = 5;
        a.direction = 0;
        PCeilng.activeceilings[0] = a;

        Line line = new Line();
        line.tag = 5;

        int rtn = PCeilng.evCeilingCrushStop(line);

        assertEquals(0, rtn);
    }

    // pActivateInStasisCeiling: a paused ceiling gets its acp1 + direction
    // restored from olddirection.
    @Test
    void activateInStasisCeilingRestoresDirection() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        a.tag = 7;
        a.direction = 0;       // paused
        a.olddirection = 1;
        a.function.acp1 = null;
        PCeilng.activeceilings[0] = a;

        Line line = new Line();
        line.tag = 7;

        PCeilng.pActivateInStasisCeiling(line);

        assertEquals(1, a.direction);
        assertNotNull(a.function.acp1);
    }

    // activateInStasis ignores unmatched tags.
    @Test
    void activateInStasisCeilingIgnoresUnmatchedTag() {
        PCeilng.Ceiling a = new PCeilng.Ceiling();
        a.tag = 7;
        a.direction = 0;
        a.olddirection = 1;
        PCeilng.activeceilings[0] = a;

        Line line = new Line();
        line.tag = 99;

        PCeilng.pActivateInStasisCeiling(line);

        assertEquals(0, a.direction);
    }

    // In-stasis ceiling (direction == 0) is a no-op inside T_MoveCeiling.
    @Test
    void tMoveCeilingInStasisIsNoOp() {
        Sector sec = new Sector();
        sec.ceilingheight = 200;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = 0;
        c.type = PCeilng.crushAndRaise;

        PCeilng.tMoveCeiling(c);

        assertEquals(200, sec.ceilingheight);
        assertEquals(0, c.direction);
    }

    // Moving UP a ceiling, speed < dist — ceiling moves one step.
    @Test
    void tMoveCeilingUpNormalMovesOneStep() {
        Sector sec = new Sector();
        sec.ceilingheight = 100;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = 1;
        c.speed = 10;
        c.topheight = 200;
        c.type = PCeilng.raiseToHighest;

        PCeilng.tMoveCeiling(c);

        assertEquals(110, sec.ceilingheight);
    }

    // Moving UP and hitting topheight (raiseToHighest) removes it.
    @Test
    void tMoveCeilingRaiseToHighestRemovesOnPastdest() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = 1;
        c.speed = 10;       // overshoots
        c.topheight = 200;
        c.type = PCeilng.raiseToHighest;
        sec.specialdata = c;
        PTick.pAddThinker(c);
        PCeilng.activeceilings[0] = c;

        PCeilng.tMoveCeiling(c);

        assertEquals(200, sec.ceilingheight);
        assertNull(PCeilng.activeceilings[0]);
        assertNull(sec.specialdata);
        assertTrue(c.removed);
    }

    // crushAndRaise going up past dest flips direction to -1.
    @Test
    void tMoveCeilingCrushAndRaiseFlipsOnTopHit() {
        Sector sec = new Sector();
        sec.ceilingheight = 195;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = 1;
        c.speed = 10;
        c.topheight = 200;
        c.type = PCeilng.crushAndRaise;

        PCeilng.tMoveCeiling(c);

        assertEquals(200, sec.ceilingheight);
        assertEquals(-1, c.direction);
    }

    // Moving DOWN a lowerToFloor ceiling to its floor removes it.
    @Test
    void tMoveCeilingLowerToFloorRemovesOnPastdest() {
        Sector sec = new Sector();
        sec.ceilingheight = 105;
        sec.floorheight = 100;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = -1;
        c.speed = 10;
        c.bottomheight = sec.floorheight;
        c.type = PCeilng.lowerToFloor;
        sec.specialdata = c;
        PTick.pAddThinker(c);
        PCeilng.activeceilings[0] = c;

        PCeilng.tMoveCeiling(c);

        assertEquals(100, sec.ceilingheight);
        assertNull(PCeilng.activeceilings[0]);
        assertTrue(c.removed);
    }

    // crushAndRaise hitting bottom reverses to up AND resets speed to CEILSPEED.
    @Test
    void tMoveCeilingCrushAndRaiseResetsSpeedAtBottom() {
        Sector sec = new Sector();
        sec.ceilingheight = 105;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = -1;
        c.speed = PCeilng.CEILSPEED / 8; // slowed from a prior crush
        c.bottomheight = 100;
        c.type = PCeilng.crushAndRaise;

        PCeilng.tMoveCeiling(c);

        assertEquals(1, c.direction);
        assertEquals(PCeilng.CEILSPEED, c.speed);
    }

    // A crush event mid-move (P_ChangeSector returns true) on lowerAndCrush
    // slows the ceiling to CEILSPEED/8. Values are small ints so T_MovePlane
    // takes the normal-step branch (not pastdest) where crush can fire.
    @Test
    void tMoveCeilingLowerAndCrushSlowsOnCrush() {
        Sector sec = new Sector();
        sec.ceilingheight = 200;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = -1;
        c.speed = 10;
        c.bottomheight = 100;
        c.type = PCeilng.lowerAndCrush;
        c.crush = true;
        PMap.changeSectorResult = true; // force a crush return

        PCeilng.tMoveCeiling(c);

        // C sets speed to CEILSPEED/8 on a crushAndRaise-family crush.
        // Not a relative "slow" — it's an absolute reset.
        assertEquals(PCeilng.CEILSPEED / 8, c.speed);
    }

    // A crush on a non-crusher type (e.g. lowerToFloor) does NOT slow.
    @Test
    void tMoveCeilingCrushOnLowerToFloorDoesNotSlow() {
        Sector sec = new Sector();
        sec.ceilingheight = 200;
        PCeilng.Ceiling c = new PCeilng.Ceiling();
        c.sector = sec;
        c.direction = -1;
        c.speed = 10;
        c.bottomheight = 100;
        c.type = PCeilng.lowerToFloor;
        c.crush = false;
        PMap.changeSectorResult = true;

        PCeilng.tMoveCeiling(c);

        assertEquals(10, c.speed); // untouched
    }

    // evDoCeiling lowerToFloor: spawns a ceiling, seeds bottomheight to
    // floorheight (no +8 adjustment for lowerToFloor).
    @Test
    void evDoCeilingLowerToFloorSeedsBottom() {
        Sector sec = new Sector();
        sec.tag = 11;
        sec.floorheight = 50 * Fixed.FRACUNIT;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 11;

        int rtn = PCeilng.evDoCeiling(line, PCeilng.lowerToFloor);

        assertEquals(1, rtn);
        PCeilng.Ceiling c = (PCeilng.Ceiling) sec.specialdata;
        assertNotNull(c);
        assertEquals(sec.floorheight, c.bottomheight);
        assertEquals(-1, c.direction);
        assertEquals(PCeilng.CEILSPEED, c.speed);
        assertSame(c, PCeilng.activeceilings[0]);
    }

    // evDoCeiling lowerAndCrush: bottomheight = floor + 8*FRACUNIT.
    @Test
    void evDoCeilingLowerAndCrushAddsEightUnits() {
        Sector sec = new Sector();
        sec.tag = 12;
        sec.floorheight = 50 * Fixed.FRACUNIT;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 12;

        PCeilng.evDoCeiling(line, PCeilng.lowerAndCrush);

        PCeilng.Ceiling c = (PCeilng.Ceiling) sec.specialdata;
        assertEquals(50 * Fixed.FRACUNIT + 8 * Fixed.FRACUNIT, c.bottomheight);
    }

    // evDoCeiling raiseToHighest: topheight comes from surrounding ceilings;
    // an isolated sector reports 0 per P_FindHighestCeilingSurrounding.
    @Test
    void evDoCeilingRaiseToHighestUsesSurroundingSeed() {
        Sector sec = new Sector();
        sec.tag = 13;
        sec.ceilingheight = 100;
        sec.lines = new Line[0];
        sec.linecount = 0;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 13;

        PCeilng.evDoCeiling(line, PCeilng.raiseToHighest);

        PCeilng.Ceiling c = (PCeilng.Ceiling) sec.specialdata;
        assertEquals(0, c.topheight); // isolated sector → seed 0
        assertEquals(1, c.direction);
    }

    // evDoCeiling fastCrushAndRaise: speed = CEILSPEED*2, crush = true,
    // bottomheight = floor + 8*FRACUNIT.
    @Test
    void evDoCeilingFastCrushDoublesSpeed() {
        Sector sec = new Sector();
        sec.tag = 14;
        sec.floorheight = 0;
        sec.ceilingheight = 200 * Fixed.FRACUNIT;
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 14;

        PCeilng.evDoCeiling(line, PCeilng.fastCrushAndRaise);

        PCeilng.Ceiling c = (PCeilng.Ceiling) sec.specialdata;
        assertTrue(c.crush);
        assertEquals(PCeilng.CEILSPEED * 2, c.speed);
        assertEquals(8 * Fixed.FRACUNIT, c.bottomheight);
    }

    // evDoCeiling skips a sector that already has specialdata.
    @Test
    void evDoCeilingSkipsSectorsWithSpecialData() {
        Sector sec = new Sector();
        sec.tag = 15;
        sec.specialdata = new Object(); // placeholder thinker
        PSetup.sectors = new Sector[] { sec };
        PSetup.numsectors = 1;

        Line line = new Line();
        line.tag = 15;

        int rtn = PCeilng.evDoCeiling(line, PCeilng.lowerToFloor);

        assertEquals(0, rtn);
        assertNull(PCeilng.activeceilings[0]);
    }
}
